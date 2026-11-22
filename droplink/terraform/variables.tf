variable "domain_name" {
  description = "Your domain, for example example.com. The app is reached at <subdomain>.<domain_name>."
  type        = string
}

variable "subdomain" {
  description = "Host name in front of the domain. Empty = the domain itself."
  type        = string
  default     = "droplink"
}

variable "create_hosted_zone" {
  description = "true: create a new Route 53 hosted zone for domain_name (then point your registrar to its name servers, see the output). false: use a zone that already exists in this account."
  type        = bool
  default     = true
}

variable "allowed_cidrs" {
  description = "Who may open the load balancers on port 80, for example [\"203.0.113.7/32\"] (your IP) or [\"0.0.0.0/0\"] (everybody). The Route 53 health checkers are always allowed too, so failover works with a restricted list."
  type        = list(string)

  validation {
    condition     = length(var.allowed_cidrs) > 0
    error_message = "allowed_cidrs must have at least one entry. Use [\"0.0.0.0/0\"] to allow everybody."
  }
}

variable "notify_email" {
  description = "E-mail address for the 'file downloaded' message (SNS). Empty = no e-mail. AWS sends one confirmation mail per region: click both."
  type        = string
  default     = ""
}

variable "primary_region" {
  type    = string
  default = "us-west-2"
}

variable "secondary_region" {
  type    = string
  default = "us-east-1"
}

variable "name_prefix" {
  description = "Start of every name. Keep it different from anything you made by hand (for example a role called droplink-ec2-role)."
  type        = string
  default     = "droplink-tf"

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9-]{0,14}[a-z0-9]$", var.name_prefix))
    error_message = "name_prefix: 2 to 16 characters, lowercase letters, digits and hyphens (it is part of bucket and load balancer names)."
  }
}

variable "instance_type" {
  type    = string
  default = "m5.large"
}

variable "jar_path" {
  description = "The built DropLink jar. Terraform uploads it to both buckets. Build it with OUT=$PWD/droplink.jar bash ../deploy/cloudshell-build.sh"
  type        = string
  default     = "droplink.jar"
}

variable "primary_capacity" {
  description = "Instances in the primary region (Auto Scaling group)."
  type        = object({ min = number, desired = number, max = number })
  default     = { min = 2, desired = 2, max = 4 }
}

variable "secondary_capacity" {
  description = "Instances in the secondary region. Warm standby: always at least one instance running."
  type        = object({ min = number, desired = number, max = number })
  default     = { min = 1, desired = 1, max = 2 }
}

variable "primary_vpc_cidr" {
  type    = string
  default = "10.0.0.0/16"
}

variable "secondary_vpc_cidr" {
  type    = string
  default = "10.1.0.0/16"
}
