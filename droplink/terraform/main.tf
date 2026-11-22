# DropLink in two regions with DNS failover (Route 53). Warm standby: the second region always runs.
#
#   Route 53  droplink.<your domain>  --PRIMARY-->   ALB (primary region)   -> Auto Scaling group (private subnets)
#                                     --SECONDARY--> ALB (secondary region) -> Auto Scaling group (private subnets)
#   Shared data: one DynamoDB global table (the links) and two S3 buckets that replicate each other (the files)

data "aws_caller_identity" "current" {
  provider = aws.primary
}

locals {
  account_id = data.aws_caller_identity.current.account_id
  fqdn       = var.subdomain == "" ? var.domain_name : "${var.subdomain}.${var.domain_name}"

  table_name       = var.name_prefix
  primary_bucket   = "${var.name_prefix}-${local.account_id}-${var.primary_region}"
  secondary_bucket = "${var.name_prefix}-${local.account_id}-${var.secondary_region}"
  primary_topic    = "${var.name_prefix}-downloads"
  secondary_topic  = "${var.name_prefix}-downloads"

  # The jar name contains a hash of the file: a new jar gives new servers (instance refresh)
  jar_key = "droplink-${substr(filesha256(var.jar_path), 0, 12)}.jar"

  health_checker_regions = ["us-east-1", "us-west-1", "us-west-2"]
  cors_origins           = ["http://${local.fqdn}", "https://${local.fqdn}"]
}

# The secret that signs the cookie of password-protected links. The same in both regions, so a failover keeps people unlocked.
# It lives in the Terraform state (and in the launch template user data): fine for a lab, not for production.
resource "random_password" "secret" {
  length  = 48
  special = false
}

# Where the Route 53 health checkers come from. They must be allowed on the load balancers, otherwise every check fails.
data "aws_ip_ranges" "route53_health_checkers" {
  provider = aws.primary
  services = ["route53_healthchecks"]
  regions  = local.health_checker_regions
}

# ------------------------------------------------------------------ IAM (global): one role for the servers of both regions

data "aws_iam_policy_document" "ec2_trust" {
  provider = aws.primary
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "app" {
  provider           = aws.primary
  name               = "${var.name_prefix}-ec2-role"
  assume_role_policy = data.aws_iam_policy_document.ec2_trust.json
}

resource "aws_iam_policy" "app" {
  provider    = aws.primary
  name        = "${var.name_prefix}-app"
  description = "What the DropLink servers may do: their buckets, the table, the topics"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "UploadDownloadDeleteFiles"
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]
        Resource = ["arn:aws:s3:::${local.primary_bucket}/files/*", "arn:aws:s3:::${local.secondary_bucket}/files/*"]
      },
      {
        Sid      = "ListSoMissingFilesAnswer404"
        Effect   = "Allow"
        Action   = "s3:ListBucket"
        Resource = ["arn:aws:s3:::${local.primary_bucket}", "arn:aws:s3:::${local.secondary_bucket}"]
      },
      {
        Sid      = "ReadTheJarRelease"
        Effect   = "Allow"
        Action   = "s3:GetObject"
        Resource = ["arn:aws:s3:::${local.primary_bucket}/releases/*", "arn:aws:s3:::${local.secondary_bucket}/releases/*"]
      },
      {
        Sid    = "LinkTable"
        Effect = "Allow"
        Action = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:DeleteItem", "dynamodb:Query", "dynamodb:BatchWriteItem"]
        Resource = [
          "arn:aws:dynamodb:${var.primary_region}:${local.account_id}:table/${local.table_name}",
          "arn:aws:dynamodb:${var.secondary_region}:${local.account_id}:table/${local.table_name}",
        ]
      },
      {
        Sid    = "DownloadMail"
        Effect = "Allow"
        Action = "sns:Publish"
        Resource = [
          "arn:aws:sns:${var.primary_region}:${local.account_id}:${local.primary_topic}",
          "arn:aws:sns:${var.secondary_region}:${local.account_id}:${local.secondary_topic}",
        ]
      },
    ]
  })
}

resource "aws_iam_role_policy_attachment" "app" {
  provider   = aws.primary
  role       = aws_iam_role.app.name
  policy_arn = aws_iam_policy.app.arn
}

# Session Manager: a browser terminal to the servers (no SSH, no open port) to read the logs
resource "aws_iam_role_policy_attachment" "ssm" {
  provider   = aws.primary
  role       = aws_iam_role.app.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "app" {
  provider   = aws.primary
  name       = "${var.name_prefix}-ec2-role"
  role       = aws_iam_role.app.name
  depends_on = [aws_iam_role_policy_attachment.app, aws_iam_role_policy_attachment.ssm] # the servers need the permissions when they boot
}

# A new instance profile is not usable at once everywhere in AWS: wait a little before the servers are created
resource "time_sleep" "iam_propagation" {
  create_duration = "20s"
  triggers        = { profile_name = aws_iam_instance_profile.app.name }
}

# ------------------------------------------------------------------ DynamoDB: one global table, a copy in each region

resource "aws_dynamodb_table" "links" {
  provider         = aws.primary
  name             = local.table_name
  billing_mode     = "PAY_PER_REQUEST"
  hash_key         = "pk"
  range_key        = "sk"
  stream_enabled   = true # a global table needs the stream
  stream_view_type = "NEW_AND_OLD_IMAGES"

  attribute {
    name = "pk"
    type = "S"
  }
  attribute {
    name = "sk"
    type = "S"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  replica {
    region_name = var.secondary_region
  }
}

# ------------------------------------------------------------------ the two regions

module "primary" {
  source    = "./modules/region"
  providers = { aws = aws.primary }

  name                  = "${var.name_prefix}-primary"
  vpc_cidr              = var.primary_vpc_cidr
  bucket_name           = local.primary_bucket
  table_name            = local.table_name
  topic_name            = local.primary_topic
  notify_email          = var.notify_email
  instance_profile_name = time_sleep.iam_propagation.triggers["profile_name"]
  instance_type         = var.instance_type
  secret                = random_password.secret.result
  jar_path              = var.jar_path
  jar_key               = local.jar_key
  allowed_cidrs         = var.allowed_cidrs
  health_checker_cidrs  = data.aws_ip_ranges.route53_health_checkers.cidr_blocks
  cors_origins          = local.cors_origins
  capacity              = var.primary_capacity
}

module "secondary" {
  source    = "./modules/region"
  providers = { aws = aws.secondary }

  name                  = "${var.name_prefix}-secondary"
  vpc_cidr              = var.secondary_vpc_cidr
  bucket_name           = local.secondary_bucket
  table_name            = local.table_name
  topic_name            = local.secondary_topic
  notify_email          = var.notify_email
  instance_profile_name = time_sleep.iam_propagation.triggers["profile_name"]
  instance_type         = var.instance_type
  secret                = random_password.secret.result
  jar_path              = var.jar_path
  jar_key               = local.jar_key
  allowed_cidrs         = var.allowed_cidrs
  health_checker_cidrs  = data.aws_ip_ranges.route53_health_checkers.cidr_blocks
  cors_origins          = local.cors_origins
  capacity              = var.secondary_capacity

  # the table copy in this region exists once the global table is made
  depends_on = [aws_dynamodb_table.links]
}

# ------------------------------------------------------------------ S3: the two buckets replicate each other (files/ only)

data "aws_iam_policy_document" "s3_trust" {
  provider = aws.primary
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["s3.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "replication" {
  provider           = aws.primary
  name               = "${var.name_prefix}-s3-replication"
  assume_role_policy = data.aws_iam_policy_document.s3_trust.json
}

resource "aws_iam_role_policy" "replication" {
  provider = aws.primary
  name     = "replicate-files"
  role     = aws_iam_role.replication.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:GetReplicationConfiguration", "s3:ListBucket"]
        Resource = [module.primary.bucket_arn, module.secondary.bucket_arn]
      },
      {
        Effect   = "Allow"
        Action   = ["s3:GetObjectVersionForReplication", "s3:GetObjectVersionAcl", "s3:GetObjectVersionTagging"]
        Resource = ["${module.primary.bucket_arn}/files/*", "${module.secondary.bucket_arn}/files/*"]
      },
      {
        Effect   = "Allow"
        Action   = ["s3:ReplicateObject", "s3:ReplicateDelete", "s3:ReplicateTags"]
        Resource = ["${module.primary.bucket_arn}/files/*", "${module.secondary.bucket_arn}/files/*"]
      },
    ]
  })
}

resource "aws_s3_bucket_replication_configuration" "primary_to_secondary" {
  provider = aws.primary
  role     = aws_iam_role.replication.arn
  bucket   = module.primary.bucket_id

  rule {
    id       = "files-to-secondary"
    status   = "Enabled"
    priority = 1
    filter {
      prefix = "files/"
    }
    delete_marker_replication {
      status = "Enabled"
    }
    destination {
      bucket = module.secondary.bucket_arn
    }
  }

  depends_on = [aws_iam_role_policy.replication]
}

resource "aws_s3_bucket_replication_configuration" "secondary_to_primary" {
  provider = aws.secondary
  role     = aws_iam_role.replication.arn
  bucket   = module.secondary.bucket_id

  rule {
    id       = "files-to-primary"
    status   = "Enabled"
    priority = 1
    filter {
      prefix = "files/"
    }
    delete_marker_replication {
      status = "Enabled"
    }
    destination {
      bucket = module.primary.bucket_arn
    }
  }

  depends_on = [aws_iam_role_policy.replication]
}

# ------------------------------------------------------------------ Route 53: failover records and health checks

resource "aws_route53_zone" "main" {
  count    = var.create_hosted_zone ? 1 : 0
  provider = aws.primary
  name     = var.domain_name
}

data "aws_route53_zone" "existing" {
  count        = var.create_hosted_zone ? 0 : 1
  provider     = aws.primary
  name         = var.domain_name
  private_zone = false
}

locals {
  zone_id = var.create_hosted_zone ? aws_route53_zone.main[0].zone_id : data.aws_route53_zone.existing[0].zone_id
}

# A check from outside: Route 53 opens the load balancer on port 80 every 10 seconds. Two failures in a row = unhealthy.
resource "aws_route53_health_check" "primary" {
  provider          = aws.primary
  type              = "HTTP"
  fqdn              = module.primary.alb_dns_name
  port              = 80
  resource_path     = "/actuator/health"
  request_interval  = 10
  failure_threshold = 2
  regions           = local.health_checker_regions
  tags              = { Name = "${var.name_prefix}-primary" }
}

resource "aws_route53_health_check" "secondary" {
  provider          = aws.primary
  type              = "HTTP"
  fqdn              = module.secondary.alb_dns_name
  port              = 80
  resource_path     = "/actuator/health"
  request_interval  = 10
  failure_threshold = 2
  regions           = local.health_checker_regions
  tags              = { Name = "${var.name_prefix}-secondary" }
}

resource "aws_route53_record" "primary" {
  provider        = aws.primary
  zone_id         = local.zone_id
  name            = local.fqdn
  type            = "A"
  set_identifier  = "primary"
  health_check_id = aws_route53_health_check.primary.id

  failover_routing_policy {
    type = "PRIMARY"
  }

  alias {
    name                   = module.primary.alb_dns_name
    zone_id                = module.primary.alb_zone_id
    evaluate_target_health = true
  }
}

resource "aws_route53_record" "secondary" {
  provider        = aws.primary
  zone_id         = local.zone_id
  name            = local.fqdn
  type            = "A"
  set_identifier  = "secondary"
  health_check_id = aws_route53_health_check.secondary.id

  failover_routing_policy {
    type = "SECONDARY"
  }

  alias {
    name                   = module.secondary.alb_dns_name
    zone_id                = module.secondary.alb_zone_id
    evaluate_target_health = true
  }
}
