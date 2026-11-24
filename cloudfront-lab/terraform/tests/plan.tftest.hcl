# An offline check of the whole configuration against pretend AWS answers: it creates nothing and needs no AWS login.
# Run:  terraform init -backend=false && terraform test        (Terraform 1.7 or newer)

mock_provider "aws" {}

override_data {
  target = data.aws_caller_identity.current
  values = { account_id = "123456789012" }
}

override_data {
  target = data.aws_iam_policy_document.bucket
  values = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
}

run "plan_with_edge_functions" {
  command = plan

  assert {
    condition     = output.bucket == "cf-lab-tf-123456789012"
    error_message = "Unexpected bucket name."
  }

  assert {
    condition     = length(aws_s3_object.site) >= 9
    error_message = "The website files were not all found."
  }

  assert {
    condition     = aws_s3_object.site["index.html"].cache_control == "public, max-age=60" && aws_s3_object.site["assets/app.v1.css"].cache_control == "public, max-age=31536000, immutable"
    error_message = "HTML must be cached for 60 s and the versioned assets for a year."
  }

  assert {
    condition     = aws_s3_object.site["index.html"].content_type == "text/html; charset=utf-8" && aws_s3_object.site["data/big.json"].content_type == "application/json"
    error_message = "Content types are wrong."
  }

  assert {
    condition     = length(aws_cloudfront_function.request) == 1 && length(aws_cloudfront_function.response) == 1
    error_message = "The edge functions should be created."
  }
}

run "plan_without_edge_functions" {
  command = plan

  variables {
    enable_edge_functions = false
  }

  assert {
    condition     = length(aws_cloudfront_function.request) == 0 && length(aws_cloudfront_function.response) == 0
    error_message = "No edge functions should be created."
  }
}
