terraform {
  required_version = ">= 1.5"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.40"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.4"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = { Project = "photowall", ManagedBy = "terraform" }
  }
}

# CloudFront certificates (ACM) and WAF for CloudFront must be in us-east-1, whatever region the rest is in
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"
  default_tags {
    tags = { Project = "photowall", ManagedBy = "terraform" }
  }
}
