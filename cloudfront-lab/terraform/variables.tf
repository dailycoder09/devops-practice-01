variable "region" {
  description = "Region of the bucket and the Lambda function."
  type        = string
  default     = "us-west-2"
}

variable "name_prefix" {
  description = "Start of every name (lowercase letters, digits, hyphens). The resources get a -tf part, so they do not clash with what you made by hand in the console labs."
  type        = string
  default     = "cf-lab"

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9-]{1,18}[a-z0-9]$", var.name_prefix))
    error_message = "name_prefix: 3 to 20 characters, lowercase letters, digits and hyphens."
  }
}

variable "enable_edge_functions" {
  description = "true: attach the two CloudFront Functions of Lab 5 (clean URLs, redirect, block /admin, an extra header)."
  type        = bool
  default     = true
}
