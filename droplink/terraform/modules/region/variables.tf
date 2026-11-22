variable "name" {
  description = "Name for everything in this region, for example droplink-tf-primary (at most 24 characters: it is part of load balancer and target group names)."
  type        = string
}

variable "vpc_cidr" {
  type = string
}

variable "bucket_name" {
  type = string
}

variable "table_name" {
  type = string
}

variable "topic_name" {
  type = string
}

variable "notify_email" {
  type    = string
  default = ""
}

variable "instance_profile_name" {
  type = string
}

variable "instance_type" {
  type = string
}

variable "secret" {
  description = "DROPLINK_SECRET: the same in every region and instance."
  type        = string
  sensitive   = true
}

variable "jar_path" {
  type = string
}

variable "jar_key" {
  description = "File name of the jar under releases/ in the bucket. It contains a hash of the jar, so a new jar means new instances."
  type        = string
}

variable "allowed_cidrs" {
  type = list(string)
}

variable "health_checker_cidrs" {
  type = list(string)
}

variable "cors_origins" {
  description = "Web addresses allowed to upload to and read from the bucket from a browser."
  type        = list(string)
}

variable "capacity" {
  type = object({ min = number, desired = number, max = number })
}
