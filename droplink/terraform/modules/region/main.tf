# One complete DropLink region: network, private S3 bucket, load balancer and an Auto Scaling group of app servers.
# The provider (region) is chosen by the caller.

terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}

data "aws_availability_zones" "available" {
  state = "available"
}

# Latest Amazon Linux 2023 (x86_64) from Amazon
data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-x86_64"]
  }
  filter {
    name   = "architecture"
    values = ["x86_64"]
  }
  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

locals {
  azs           = slice(data.aws_availability_zones.available.names, 0, 2)
  public_cidrs  = [cidrsubnet(var.vpc_cidr, 8, 0), cidrsubnet(var.vpc_cidr, 8, 1)]
  private_cidrs = [cidrsubnet(var.vpc_cidr, 8, 10), cidrsubnet(var.vpc_cidr, 8, 11)]

  # The launch script of the project, without its first line (#!/bin/bash). The six values are set before it.
  launch_script = substr(file("${path.module}/../../../deploy/user-data.sh"), length("#!/bin/bash\n"), -1)
  user_data = join("\n", [
    "#!/bin/bash",
    "BUCKET=\"${var.bucket_name}\"",
    "TABLE=\"${var.table_name}\"",
    "TOPIC_ARN=\"${var.notify_email == "" ? "" : aws_sns_topic.downloads[0].arn}\"",
    "SECRET=\"${var.secret}\"",
    "JAR=\"${var.jar_key}\"",
    "PUBLIC_URL=\"\"",
    local.launch_script,
  ])
}

# ------------------------------------------------------------------ network: 2 public + 2 private subnets, one NAT gateway

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = var.name }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = var.name }
}

resource "aws_subnet" "public" {
  count                   = 2
  vpc_id                  = aws_vpc.main.id
  cidr_block              = local.public_cidrs[count.index]
  availability_zone       = local.azs[count.index]
  map_public_ip_on_launch = false
  tags                    = { Name = "${var.name}-public-${count.index + 1}" }
}

resource "aws_subnet" "private" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  cidr_block        = local.private_cidrs[count.index]
  availability_zone = local.azs[count.index]
  tags              = { Name = "${var.name}-private-${count.index + 1}" }
}

resource "aws_eip" "nat" {
  domain = "vpc"
  tags   = { Name = var.name }
}

# One NAT gateway for both private subnets (cheaper; if its zone fails, the servers in the other zone cannot reach AWS APIs until it is back)
resource "aws_nat_gateway" "main" {
  allocation_id = aws_eip.nat.id
  subnet_id     = aws_subnet.public[0].id
  tags          = { Name = var.name }
  depends_on    = [aws_internet_gateway.main]
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${var.name}-public" }
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.main.id
}

resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${var.name}-private" }
}

resource "aws_route" "private_nat" {
  route_table_id         = aws_route_table.private.id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.main.id
}

resource "aws_route_table_association" "private" {
  count          = 2
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}

# ------------------------------------------------------------------ security groups

resource "aws_security_group" "alb" {
  name        = "${var.name}-alb"
  description = "DropLink load balancer: port 80 from the allowed addresses and the Route 53 health checkers"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${var.name}-alb" }
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  for_each          = toset(distinct(concat(var.allowed_cidrs, var.health_checker_cidrs)))
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = each.value
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "alb_all" {
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_security_group" "vm" {
  name        = "${var.name}-vm"
  description = "DropLink servers: port 8080 only from the load balancer"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${var.name}-vm" }
}

resource "aws_vpc_security_group_ingress_rule" "vm_from_alb" {
  security_group_id            = aws_security_group.vm.id
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "vm_all" {
  security_group_id = aws_security_group.vm.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# ------------------------------------------------------------------ S3: private bucket for the files and the jar

resource "aws_s3_bucket" "files" {
  bucket        = var.bucket_name
  force_destroy = true # lab: terraform destroy also removes the files
  tags          = { Name = var.bucket_name }
}

resource "aws_s3_bucket_public_access_block" "files" {
  bucket                  = aws_s3_bucket.files.id
  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}

# Versioning is needed for cross-region replication
resource "aws_s3_bucket_versioning" "files" {
  bucket = aws_s3_bucket.files.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_cors_configuration" "files" {
  bucket = aws_s3_bucket.files.id
  cors_rule {
    allowed_origins = concat(var.cors_origins, ["http://${aws_lb.main.dns_name}"])
    allowed_methods = ["PUT", "GET", "HEAD"]
    allowed_headers = ["*"]
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "files" {
  bucket = aws_s3_bucket.files.id

  rule {
    id     = "expire-files-after-8-days"
    status = "Enabled"
    filter {
      prefix = "files/"
    }
    expiration {
      days = 8
    }
    noncurrent_version_expiration {
      noncurrent_days = 1
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  rule {
    id     = "remove-delete-markers"
    status = "Enabled"
    filter {
      prefix = "files/"
    }
    expiration {
      expired_object_delete_marker = true
    }
  }

  depends_on = [aws_s3_bucket_versioning.files]
}

# The jar the servers download at first boot
resource "aws_s3_object" "jar" {
  bucket      = aws_s3_bucket.files.id
  key         = "releases/${var.jar_key}"
  source      = var.jar_path
  source_hash = filemd5(var.jar_path)
}

# ------------------------------------------------------------------ SNS: e-mail when a file is downloaded (optional)

resource "aws_sns_topic" "downloads" {
  count = var.notify_email == "" ? 0 : 1
  name  = var.topic_name
}

resource "aws_sns_topic_subscription" "email" {
  count     = var.notify_email == "" ? 0 : 1
  topic_arn = aws_sns_topic.downloads[0].arn
  protocol  = "email"
  endpoint  = var.notify_email
}

# ------------------------------------------------------------------ load balancer

resource "aws_lb" "main" {
  name                       = var.name
  load_balancer_type         = "application"
  internal                   = false
  security_groups            = [aws_security_group.alb.id]
  subnets                    = aws_subnet.public[*].id
  drop_invalid_header_fields = true
  tags                       = { Name = var.name }
}

resource "aws_lb_target_group" "app" {
  name                 = "${var.name}-tg"
  port                 = 8080
  protocol             = "HTTP"
  vpc_id               = aws_vpc.main.id
  target_type          = "instance"
  deregistration_delay = 30

  health_check {
    path                = "/actuator/health"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }
}

# ------------------------------------------------------------------ the servers

resource "aws_launch_template" "app" {
  name_prefix            = "${var.name}-"
  image_id               = data.aws_ami.al2023.id
  instance_type          = var.instance_type
  vpc_security_group_ids = [aws_security_group.vm.id]
  update_default_version = true
  user_data              = base64encode(local.user_data)

  iam_instance_profile {
    name = var.instance_profile_name
  }

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  block_device_mappings {
    device_name = "/dev/xvda"
    ebs {
      volume_size           = 20
      volume_type           = "gp3"
      encrypted             = true
      delete_on_termination = true
    }
  }

  tag_specifications {
    resource_type = "instance"
    tags          = { Name = var.name }
  }
}

resource "aws_autoscaling_group" "app" {
  name                      = var.name
  min_size                  = var.capacity.min
  desired_capacity          = var.capacity.desired
  max_size                  = var.capacity.max
  vpc_zone_identifier       = aws_subnet.private[*].id
  target_group_arns         = [aws_lb_target_group.app.arn]
  health_check_type         = "ELB"
  health_check_grace_period = 300 # first boot installs Java and downloads the jar

  launch_template {
    id      = aws_launch_template.app.id
    version = aws_launch_template.app.latest_version
  }

  # A new jar or changed settings means a new launch template version: replace the servers a few at a time
  instance_refresh {
    strategy = "Rolling"
    preferences {
      min_healthy_percentage = 50
    }
  }

  tag {
    key                 = "Name"
    value               = var.name
    propagate_at_launch = true
  }

  # The servers need the jar in S3 and a way out to the internet the moment they start
  depends_on = [aws_s3_object.jar, aws_route.private_nat, aws_route_table_association.private]
}
