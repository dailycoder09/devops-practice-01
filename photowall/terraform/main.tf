# PhotoWall as code: what you build by hand in Phases 1 to 5, in one `terraform apply`.
#
#   visitor -> CloudFront --- default (*) --> S3 bucket (private, OAC): the page, the photos, the thumbnails
#                         \-- /api/*      --> API Gateway (HTTP API) --> Lambda --> DynamoDB (and signed uploads to S3)

data "aws_caller_identity" "current" {}

locals {
  name         = "${var.name_prefix}-tf"
  bucket_name  = "${local.name}-${data.aws_caller_identity.current.account_id}"
  use_domain   = var.domain_name != ""
  upload_key   = var.upload_key != "" ? var.upload_key : random_password.upload_key.result
  site_dir     = "${path.module}/../site"
  site_files   = toset([for f in fileset(local.site_dir, "**") : f if !endswith(f, ".DS_Store")])
  sample       = jsondecode(file("${local.site_dir}/data/photos.json")).photos
  api_host     = replace(aws_apigatewayv2_api.api.api_endpoint, "https://", "")
  site_origins = concat(["https://${aws_cloudfront_distribution.site.domain_name}"], local.use_domain ? ["https://${var.domain_name}"] : [])

  content_types = {
    html = "text/html; charset=utf-8"
    css  = "text/css"
    js   = "text/javascript"
    json = "application/json"
    jpg  = "image/jpeg"
    md   = "text/markdown"
  }
}

resource "random_password" "upload_key" {
  length  = 16
  special = false
}

# ------------------------------------------------------------------ S3: one private bucket (Phase 1)

resource "aws_s3_bucket" "site" {
  bucket        = local.bucket_name
  force_destroy = true # lab: terraform destroy also removes the files
}

resource "aws_s3_bucket_public_access_block" "site" {
  bucket                  = aws_s3_bucket.site.id
  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}

# CloudFront may read only these parts of the bucket, and list it (so a missing file is a 404, not a 403)
data "aws_iam_policy_document" "bucket" {
  statement {
    sid     = "AllowCloudFrontRead"
    actions = ["s3:GetObject"]
    resources = [
      "${aws_s3_bucket.site.arn}/index.html",
      "${aws_s3_bucket.site.arn}/assets/*",
      "${aws_s3_bucket.site.arn}/data/*",
      "${aws_s3_bucket.site.arn}/photos/*",
      "${aws_s3_bucket.site.arn}/thumbs/*",
    ]
    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.site.arn]
    }
  }
  statement {
    sid       = "AllowCloudFrontList"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.site.arn]
    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.site.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "site" {
  bucket     = aws_s3_bucket.site.id
  policy     = data.aws_iam_policy_document.bucket.json
  depends_on = [aws_s3_bucket_public_access_block.site]
}

# Phase 4: the browser sends the photo straight to S3 with a signed address, so S3 must allow that from the site
resource "aws_s3_bucket_cors_configuration" "site" {
  bucket = aws_s3_bucket.site.id
  cors_rule {
    allowed_origins = local.site_origins
    allowed_methods = ["PUT"]
    allowed_headers = ["*"]
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }
}

# The files of ../site: the page and the list a minute, everything else a year (the photos never change under their name)
resource "aws_s3_object" "site" {
  for_each = local.site_files

  bucket       = aws_s3_bucket.site.id
  key          = each.value
  source       = "${local.site_dir}/${each.value}"
  etag         = filemd5("${local.site_dir}/${each.value}")
  content_type = lookup(local.content_types, reverse(split(".", each.value))[0], "application/octet-stream")
  cache_control = (
    startswith(each.value, "assets/") || startswith(each.value, "photos/") || startswith(each.value, "thumbs/")
    ? "public, max-age=31536000, immutable"
    : "public, max-age=60"
  )
}

# ------------------------------------------------------------------ DynamoDB: the photos (Phase 2)

resource "aws_dynamodb_table" "photos" {
  name         = local.name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"

  attribute {
    name = "id"
    type = "S"
  }
}

# The sample photos. ignore_changes: the likes of visitors must not be reset by the next apply.
resource "aws_dynamodb_table_item" "sample" {
  for_each   = { for p in local.sample : p.id => p }
  table_name = aws_dynamodb_table.photos.name
  hash_key   = aws_dynamodb_table.photos.hash_key
  item = jsonencode({
    id         = { S = each.value.id }
    title      = { S = each.value.title }
    likes      = { N = "0" }
    created_at = { S = each.value.created_at }
  })

  lifecycle {
    ignore_changes = [item]
  }
}

# ------------------------------------------------------------------ Lambda and API Gateway (Phases 2 to 4)

data "archive_file" "api" {
  type        = "zip"
  source_file = "${path.module}/../api/lambda_function.py"
  output_path = "${path.module}/api.zip"
}

resource "aws_iam_role" "api" {
  name = "${local.name}-api"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "api_logs" {
  role       = aws_iam_role.api.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy" "api" {
  name = "photowall-data"
  role = aws_iam_role.api.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "PhotoTable"
        Effect   = "Allow"
        Action   = ["dynamodb:Scan", "dynamodb:UpdateItem", "dynamodb:PutItem"]
        Resource = aws_dynamodb_table.photos.arn
      },
      {
        Sid      = "PhotoFiles"
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]
        Resource = ["${aws_s3_bucket.site.arn}/photos/*", "${aws_s3_bucket.site.arn}/thumbs/*"]
      },
      {
        Sid      = "ListSoMissingFilesAnswer404"
        Effect   = "Allow"
        Action   = "s3:ListBucket"
        Resource = aws_s3_bucket.site.arn
      },
    ]
  })
}

resource "aws_lambda_function" "api" {
  function_name    = "${local.name}-api"
  role             = aws_iam_role.api.arn
  runtime          = "python3.12"
  handler          = "lambda_function.lambda_handler"
  filename         = data.archive_file.api.output_path
  source_code_hash = data.archive_file.api.output_base64sha256
  timeout          = 10
  memory_size      = 256

  environment {
    variables = {
      TABLE            = aws_dynamodb_table.photos.name
      BUCKET           = aws_s3_bucket.site.bucket
      UPLOAD_KEY       = local.upload_key
      LIST_TTL_SECONDS = tostring(var.list_ttl_seconds)
    }
  }

  depends_on = [aws_iam_role_policy_attachment.api_logs]
}

resource "aws_apigatewayv2_api" "api" {
  name          = "${local.name}-api"
  protocol_type = "HTTP"
}

resource "aws_apigatewayv2_integration" "api" {
  api_id                 = aws_apigatewayv2_api.api.id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_function.api.invoke_arn
  payload_format_version = "2.0"
}

resource "aws_apigatewayv2_route" "api" {
  api_id    = aws_apigatewayv2_api.api.id
  route_key = "ANY /api/{proxy+}"
  target    = "integrations/${aws_apigatewayv2_integration.api.id}"
}

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.api.id
  name        = "$default"
  auto_deploy = true
}

resource "aws_lambda_permission" "api_gateway" {
  statement_id  = "AllowApiGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.api.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.api.execution_arn}/*/*"
}

# ------------------------------------------------------------------ CloudFront (Phases 1, 2 and 5)

resource "aws_cloudfront_origin_access_control" "s3" {
  name                              = "${local.name}-s3"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

data "aws_cloudfront_cache_policy" "optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_origin_request_policy" "all_viewer_except_host" {
  name = "Managed-AllViewerExceptHostHeader"
}

# The API decides how long an answer may be kept (its Cache-Control), up to 300 s; the default is "not at all"
resource "aws_cloudfront_cache_policy" "api" {
  name        = "${local.name}-api-respect-origin"
  min_ttl     = 0
  default_ttl = 0
  max_ttl     = 300

  parameters_in_cache_key_and_forwarded_to_origin {
    enable_accept_encoding_gzip   = true
    enable_accept_encoding_brotli = true
    cookies_config {
      cookie_behavior = "none"
    }
    headers_config {
      header_behavior = "none"
    }
    query_strings_config {
      query_string_behavior = "all"
    }
  }
}

# Phase 5: security headers. connect-src names the S3 address the browser uploads to.
resource "aws_cloudfront_response_headers_policy" "security" {
  name = "${local.name}-security"

  security_headers_config {
    strict_transport_security {
      access_control_max_age_sec = 31536000
      include_subdomains         = true
      override                   = true
    }
    content_type_options {
      override = true
    }
    frame_options {
      frame_option = "DENY"
      override     = true
    }
    referrer_policy {
      referrer_policy = "strict-origin-when-cross-origin"
      override        = true
    }
    content_security_policy {
      content_security_policy = "default-src 'self'; img-src 'self'; connect-src 'self' https://${aws_s3_bucket.site.bucket}.s3.${var.region}.amazonaws.com; frame-ancestors 'none'"
      override                = true
    }
  }
}

resource "aws_cloudfront_distribution" "site" {
  enabled             = true
  comment             = "${local.name} (PhotoWall)"
  default_root_object = "index.html"
  price_class         = "PriceClass_100"
  http_version        = "http2and3"
  aliases             = local.use_domain ? [var.domain_name] : []
  web_acl_id          = var.enable_waf ? aws_wafv2_web_acl.likes[0].arn : null

  origin {
    origin_id                = "s3"
    domain_name              = aws_s3_bucket.site.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.s3.id
  }

  origin {
    origin_id   = "api"
    domain_name = local.api_host

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  default_cache_behavior {
    target_origin_id           = "s3"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = true
    cache_policy_id            = data.aws_cloudfront_cache_policy.optimized.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.security.id
  }

  ordered_cache_behavior {
    path_pattern             = "/api/*"
    target_origin_id         = "api"
    viewer_protocol_policy   = "redirect-to-https"
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    compress                 = true
    cache_policy_id          = aws_cloudfront_cache_policy.api.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = !local.use_domain
    acm_certificate_arn            = local.use_domain ? aws_acm_certificate_validation.site[0].certificate_arn : null
    ssl_support_method             = local.use_domain ? "sni-only" : null
    minimum_protocol_version       = local.use_domain ? "TLSv1.2_2021" : null
  }
}

# ------------------------------------------------------------------ Phase 5: your own address with HTTPS (only if domain_name is set)

resource "aws_route53_zone" "site" {
  count = local.use_domain && var.create_hosted_zone ? 1 : 0
  name  = var.domain_name
}

data "aws_route53_zone" "existing" {
  count        = local.use_domain && !var.create_hosted_zone ? 1 : 0
  name         = var.domain_name
  private_zone = false
}

locals {
  zone_id = !local.use_domain ? "" : var.create_hosted_zone ? aws_route53_zone.site[0].zone_id : data.aws_route53_zone.existing[0].zone_id
}

resource "aws_acm_certificate" "site" {
  count             = local.use_domain ? 1 : 0
  provider          = aws.us_east_1
  domain_name       = var.domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "validation" {
  for_each = local.use_domain ? {
    for option in aws_acm_certificate.site[0].domain_validation_options : option.domain_name => {
      name   = option.resource_record_name
      type   = option.resource_record_type
      record = option.resource_record_value
    }
  } : {}

  zone_id         = local.zone_id
  name            = each.value.name
  type            = each.value.type
  records         = [each.value.record]
  ttl             = 60
  allow_overwrite = true
}

# Waits until the public internet can see the validation record: the name servers of the zone must be set at your registrar
resource "aws_acm_certificate_validation" "site" {
  count                   = local.use_domain ? 1 : 0
  provider                = aws.us_east_1
  certificate_arn         = aws_acm_certificate.site[0].arn
  validation_record_fqdns = [for record in aws_route53_record.validation : record.fqdn]
}

resource "aws_route53_record" "site_a" {
  count   = local.use_domain ? 1 : 0
  zone_id = local.zone_id
  name    = var.domain_name
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.site.domain_name
    zone_id                = aws_cloudfront_distribution.site.hosted_zone_id
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "site_aaaa" {
  count   = local.use_domain ? 1 : 0
  zone_id = local.zone_id
  name    = var.domain_name
  type    = "AAAA"

  alias {
    name                   = aws_cloudfront_distribution.site.domain_name
    zone_id                = aws_cloudfront_distribution.site.hosted_zone_id
    evaluate_target_health = false
  }
}

# ------------------------------------------------------------------ Phase 5 (optional): a rate limit on the likes

resource "aws_wafv2_web_acl" "likes" {
  count    = var.enable_waf ? 1 : 0
  provider = aws.us_east_1
  name     = "${local.name}-likes"
  scope    = "CLOUDFRONT"

  default_action {
    allow {}
  }

  rule {
    name     = "rate-limit-likes"
    priority = 1

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = 20
        evaluation_window_sec = 60
        aggregate_key_type = "IP"

        scope_down_statement {
          byte_match_statement {
            search_string         = "/like"
            positional_constraint = "ENDS_WITH"
            field_to_match {
              uri_path {}
            }
            text_transformation {
              priority = 0
              type     = "NONE"
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "rate-limit-likes"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${local.name}-likes"
    sampled_requests_enabled   = true
  }
}
