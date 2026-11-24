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
  }
}

# CloudFront is a global service: the region below is for the bucket and the Lambda function.
provider "aws" {
  region = var.region
  default_tags {
    tags = { Project = "cloudfront-lab", ManagedBy = "terraform" }
  }
}
