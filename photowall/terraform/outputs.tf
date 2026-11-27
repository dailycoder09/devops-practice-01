output "url" {
  description = "Open this."
  value       = local.use_domain ? "https://${var.domain_name}" : "https://${aws_cloudfront_distribution.site.domain_name}"
}

output "cloudfront_url" {
  value = "https://${aws_cloudfront_distribution.site.domain_name}"
}

output "name_servers" {
  description = "Set these as the name servers of domain_name at your registrar (only when a zone was created)."
  value       = local.use_domain && var.create_hosted_zone ? aws_route53_zone.site[0].name_servers : []
}

output "upload_key" {
  description = "The word to type in the Upload key box."
  value       = local.upload_key
  sensitive   = true
}

output "bucket" {
  value = aws_s3_bucket.site.bucket
}

output "api_url_direct" {
  description = "The API Gateway address, without CloudFront (to compare with the cached one)."
  value       = aws_apigatewayv2_api.api.api_endpoint
}

output "try_it" {
  description = "Commands to run from the photowall folder."
  value       = <<-EOT
    bash scripts/check.sh https://${aws_cloudfront_distribution.site.domain_name}/api/photos 3
    bash scripts/check.sh https://${aws_cloudfront_distribution.site.domain_name}/thumbs/sample01.jpg 2
    terraform -chdir=terraform output -raw upload_key
  EOT
}
