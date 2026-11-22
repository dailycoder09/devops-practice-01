# An offline check of the whole configuration: a plan against pretend AWS answers. It creates nothing and needs no AWS login.
# Run:  terraform init -backend=false && terraform test        (Terraform 1.7 or newer)

mock_provider "aws" {
  alias = "primary"
}

mock_provider "aws" {
  alias = "secondary"
}

mock_provider "random" {}

mock_provider "time" {}

override_data {
  target = module.primary.data.aws_availability_zones.available
  values = { names = ["zone-a", "zone-b", "zone-c"] }
}

override_data {
  target = module.secondary.data.aws_availability_zones.available
  values = { names = ["zone-a", "zone-b", "zone-c"] }
}

override_data {
  target = data.aws_ip_ranges.route53_health_checkers
  values = { cidr_blocks = ["15.177.0.0/18", "54.183.255.128/26"] }
}

override_data {
  target = data.aws_caller_identity.current
  values = { account_id = "123456789012" }
}

override_data {
  target = data.aws_iam_policy_document.ec2_trust
  values = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
}

override_data {
  target = data.aws_iam_policy_document.s3_trust
  values = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
}

variables {
  domain_name   = "example.com"
  allowed_cidrs = ["203.0.113.7/32"]
  jar_path      = "versions.tf" # any existing file: the plan only reads its hash
  notify_email  = "me@example.com"
}

run "plan_with_new_zone_and_email" {
  command = plan

  assert {
    condition     = output.app_url == "http://droplink.example.com"
    error_message = "The app address should be droplink.example.com."
  }

  assert {
    condition     = aws_route53_record.primary.set_identifier == "primary" && aws_route53_record.secondary.set_identifier == "secondary"
    error_message = "Both failover records must exist."
  }

  assert {
    condition     = output.buckets.primary == "droplink-tf-123456789012-us-west-2" && output.buckets.secondary == "droplink-tf-123456789012-us-east-1"
    error_message = "Bucket names are not the expected ones."
  }
}

run "plan_with_existing_zone_and_no_email" {
  command = plan

  variables {
    create_hosted_zone = false
    notify_email       = ""
    subdomain          = ""
  }

  override_data {
    target = data.aws_route53_zone.existing[0]
    values = { zone_id = "Z0000000000000" }
  }

  assert {
    condition     = output.app_url == "http://example.com"
    error_message = "Without a subdomain the app address is the domain itself."
  }
}
