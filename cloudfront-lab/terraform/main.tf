# The CloudFront lab as code: what you built by hand in Labs 0 to 5, in one `terraform apply`.
#
#   viewer -> CloudFront ─ default (*)  -> S3 bucket (private, OAC)        cached; HTML 60 s, assets 1 year
#                        └ /api/*       -> Lambda function URL (OAC, IAM)   cache decided by the API's Cache-Control

data "aws_caller_identity" "current" {}

locals {
  name        = "${var.name_prefix}-tf"
  bucket_name = "${local.name}-${data.aws_caller_identity.current.account_id}"
  site_dir    = "${path.module}/../site"
  site_files  = toset([for f in fileset(local.site_dir, "**") : f if !endswith(f, ".DS_Store")])

  content_types = {
    html = "text/html; charset=utf-8"
    css  = "text/css"
    js   = "text/javascript"
    json = "application/json"
    jpg  = "image/jpeg"
    md   = "text/markdown"
  }

  # The Function URL without https:// and the trailing slash: that is how CloudFront wants an origin
  lambda_host = trimsuffix(trimprefix(aws_lambda_function_url.api.function_url, "https://"), "/")
}

# ------------------------------------------------------------------ S3: the private bucket with the website (Labs 0 and 1)

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

# Only this distribution may read objects, and list the bucket (so a missing file is a 404, not a 403)
data "aws_iam_policy_document" "bucket" {
  statement {
    sid       = "AllowCloudFrontRead"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.site.arn}/*"]
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
  bucket = aws_s3_bucket.site.id
  policy = data.aws_iam_policy_document.bucket.json

  depends_on = [aws_s3_bucket_public_access_block.site]
}

# The files of ../site, with the cache headers of Lab 3: HTML short, versioned assets for a year, the rest a day
resource "aws_s3_object" "site" {
  for_each = local.site_files

  bucket       = aws_s3_bucket.site.id
  key          = each.value
  source       = "${local.site_dir}/${each.value}"
  etag         = filemd5("${local.site_dir}/${each.value}")
  content_type = lookup(local.content_types, reverse(split(".", each.value))[0], "application/octet-stream")
  cache_control = (
    endswith(each.value, ".html") ? "public, max-age=60" :
    startswith(each.value, "assets/") ? "public, max-age=31536000, immutable" :
    "public, max-age=86400"
  )
}

# ------------------------------------------------------------------ Lambda: the tiny API (Lab 2)

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

resource "aws_lambda_function" "api" {
  function_name    = "${local.name}-api"
  role             = aws_iam_role.api.arn
  runtime          = "python3.12"
  handler          = "lambda_function.lambda_handler"
  filename         = data.archive_file.api.output_path
  source_code_hash = data.archive_file.api.output_base64sha256
  timeout          = 15
  depends_on       = [aws_iam_role_policy_attachment.api_logs]
}

# Not a public URL (unlike the console lab): it needs AWS_IAM, and only CloudFront (through OAC) may call it
resource "aws_lambda_function_url" "api" {
  function_name      = aws_lambda_function.api.function_name
  authorization_type = "AWS_IAM"
}

resource "aws_lambda_permission" "cloudfront_url" {
  statement_id           = "AllowCloudFrontInvokeUrl"
  action                 = "lambda:InvokeFunctionUrl"
  function_name          = aws_lambda_function.api.function_name
  principal              = "cloudfront.amazonaws.com"
  source_arn             = aws_cloudfront_distribution.site.arn
  function_url_auth_type = "AWS_IAM"
}

resource "aws_lambda_permission" "cloudfront_invoke" {
  statement_id  = "AllowCloudFrontInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.api.function_name
  principal     = "cloudfront.amazonaws.com"
  source_arn    = aws_cloudfront_distribution.site.arn
}

# ------------------------------------------------------------------ CloudFront: policies

resource "aws_cloudfront_origin_access_control" "s3" {
  name                              = "${local.name}-s3"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_origin_access_control" "lambda" {
  name                              = "${local.name}-lambda"
  origin_access_control_origin_type = "lambda"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

data "aws_cloudfront_cache_policy" "optimized" {
  name = "Managed-CachingOptimized"
}

# Lab 2: nothing is kept unless the API asks (default 0), for at most 300 s; every query string is part of the cache key
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

# Lab 4: security headers on every answer
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
      content_security_policy = "default-src 'self'; img-src 'self' data:; frame-ancestors 'none'"
      override                = true
    }
  }

  custom_headers_config {
    items {
      header   = "X-Lab-Policy"
      value    = "${local.name}-security"
      override = true
    }
  }
}

# Lab 5: the two edge functions
resource "aws_cloudfront_function" "request" {
  count   = var.enable_edge_functions ? 1 : 0
  name    = "${local.name}-edge-request"
  runtime = "cloudfront-js-2.0"
  publish = true
  code    = file("${path.module}/../functions/edge-request.js")
}

resource "aws_cloudfront_function" "response" {
  count   = var.enable_edge_functions ? 1 : 0
  name    = "${local.name}-edge-response"
  runtime = "cloudfront-js-2.0"
  publish = true
  code    = file("${path.module}/../functions/edge-response.js")
}

# ------------------------------------------------------------------ CloudFront: the distribution

resource "aws_cloudfront_distribution" "site" {
  enabled             = true
  comment             = "${local.name} (CloudFront lab)"
  default_root_object = "index.html"
  price_class         = "PriceClass_100"
  http_version        = "http2and3"

  origin {
    origin_id                = "s3"
    domain_name              = aws_s3_bucket.site.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.s3.id
  }

  origin {
    origin_id                = "api"
    domain_name              = local.lambda_host
    origin_access_control_id = aws_cloudfront_origin_access_control.lambda.id

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

    dynamic "function_association" {
      for_each = var.enable_edge_functions ? [1] : []
      content {
        event_type   = "viewer-request"
        function_arn = aws_cloudfront_function.request[0].arn
      }
    }

    dynamic "function_association" {
      for_each = var.enable_edge_functions ? [1] : []
      content {
        event_type   = "viewer-response"
        function_arn = aws_cloudfront_function.response[0].arn
      }
    }
  }

  ordered_cache_behavior {
    path_pattern           = "/api/*"
    target_origin_id       = "api"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true
    cache_policy_id        = aws_cloudfront_cache_policy.api.id
    # no origin request policy: CloudFront sets the Host header to the Function URL's own host name (Lab 2, Break 1)
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }
}
