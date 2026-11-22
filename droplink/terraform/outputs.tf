output "app_url" {
  description = "The address to open (works once the name servers below are set at your registrar, or when the zone already existed)."
  value       = "http://${local.fqdn}"
}

output "name_servers" {
  description = "Set these as the name servers of your domain at the registrar (only when create_hosted_zone = true)."
  value       = var.create_hosted_zone ? aws_route53_zone.main[0].name_servers : []
}

output "primary_alb_url" {
  description = "The primary load balancer directly (to test it without DNS)."
  value       = "http://${module.primary.alb_dns_name}"
}

output "secondary_alb_url" {
  description = "The secondary load balancer directly (to test it without DNS)."
  value       = "http://${module.secondary.alb_dns_name}"
}

output "buckets" {
  value = { primary = local.primary_bucket, secondary = local.secondary_bucket }
}

output "table" {
  value = local.table_name
}

output "autoscaling_groups" {
  description = "Names of the Auto Scaling groups (to scale the primary to 0 and watch the failover)."
  value       = { primary = module.primary.asg_name, secondary = module.secondary.asg_name }
}
