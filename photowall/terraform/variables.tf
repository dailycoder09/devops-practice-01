variable "region" {
  description = "Region of the bucket, the table, the Lambda function and the API."
  type        = string
  default     = "us-west-2"
}

variable "name_prefix" {
  description = "Start of every name. The resources get a -tf part, so they do not clash with what you made by hand in the console phases."
  type        = string
  default     = "photowall"

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9-]{1,18}[a-z0-9]$", var.name_prefix))
    error_message = "name_prefix: 3 to 20 characters, lowercase letters, digits and hyphens."
  }
}

variable "upload_key" {
  description = "The word people must send to upload a photo. Empty = Terraform makes one (see the output)."
  type        = string
  default     = ""
  sensitive   = true
}

variable "list_ttl_seconds" {
  description = "How long CloudFront may keep the list of photos (0 to 300)."
  type        = number
  default     = 30

  validation {
    condition     = var.list_ttl_seconds >= 0 && var.list_ttl_seconds <= 300
    error_message = "list_ttl_seconds must be between 0 and 300."
  }
}

variable "domain_name" {
  description = "Your own address for the site, for example photowall.shaikhirfan.com. Empty = use the cloudfront.net address only."
  type        = string
  default     = ""
}

variable "create_hosted_zone" {
  description = "true: create a Route 53 hosted zone named domain_name (then set its name servers at your registrar). false: use a zone that already exists."
  type        = bool
  default     = true
}

variable "enable_waf" {
  description = "true: add a WAF rate limit on the likes. It costs money outside a sandbox and may be blocked in a sandbox."
  type        = bool
  default     = false
}
