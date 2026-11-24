output "url" {
  description = "Open this."
  value       = "https://${aws_cloudfront_distribution.site.domain_name}"
}

output "distribution_id" {
  value = aws_cloudfront_distribution.site.id
}

output "bucket" {
  value = aws_s3_bucket.site.bucket
}

output "try_it" {
  description = "Commands to run from the cloudfront-lab folder."
  value       = <<-EOT
    bash scripts/check.sh https://${aws_cloudfront_distribution.site.domain_name}/index.html 3
    BODY=1 bash scripts/check.sh "https://${aws_cloudfront_distribution.site.domain_name}/api/time?ttl=20" 3
    curl -sI https://${aws_cloudfront_distribution.site.domain_name}/old-about.html | head -3
  EOT
}
