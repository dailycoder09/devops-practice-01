output "alb_dns_name" {
  value = aws_lb.main.dns_name
}

output "alb_zone_id" {
  value = aws_lb.main.zone_id
}

# Whoever uses the bucket waits until versioning is on (replication needs it)
output "bucket_id" {
  value      = aws_s3_bucket.files.id
  depends_on = [aws_s3_bucket_versioning.files]
}

output "bucket_arn" {
  value      = aws_s3_bucket.files.arn
  depends_on = [aws_s3_bucket_versioning.files]
}

output "asg_name" {
  value = aws_autoscaling_group.app.name
}

output "vpc_id" {
  value = aws_vpc.main.id
}
