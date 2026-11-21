# All environment-specific values arrive through variables or tfvars files.
# No credential, account identifier or secret is committed in this module.

variable "environment" {
  description = "Deployment environment. Selects one of the isolated accounts."
  type        = string

  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "environment must be staging or production."
  }
}

variable "account_id" {
  description = "AWS account id dedicated to this environment; the provider refuses to target any other account."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "account_id must be a 12-digit account id."
  }
}

variable "region" {
  description = "Approved hosting region for this environment."
  type        = string
}

variable "availability_zones" {
  description = "Two availability zones in region, used for private subnets and multi-AZ services."
  type        = list(string)

  validation {
    condition     = length(var.availability_zones) >= 2
    error_message = "at least two availability zones are required."
  }
}

variable "vpc_cidr" {
  description = "IPv4 CIDR for the dedicated environment VPC."
  type        = string
}

variable "admin_cidrs" {
  description = "Operator networks permitted to reach the managed TLS ingress."
  type        = list(string)
  default     = ["127.0.0.1/32"]

  validation {
    condition = alltrue([for cidr in var.admin_cidrs :
      startswith(cidr, "10.") ||
      startswith(cidr, "192.168.") ||
      startswith(cidr, "127.") ||
      can(regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.", cidr))])
    error_message = "admin_cidrs must be RFC1918 or loopback ranges, never public."
  }
}

variable "bucket_name" {
  description = "Globally unique name of the private encrypted object bucket."
  type        = string
}

variable "db_identifier" {
  description = "RDS instance identifier for this environment."
  type        = string
}

variable "db_instance_class" {
  description = "RDS instance class."
  type        = string
  default     = "db.r6g.large"
}

variable "db_master_username" {
  description = "Master user name. The password is generated and held by RDS in Secrets Manager."
  type        = string
  default     = "smartclinic"
}

variable "backup_retention_days" {
  description = "Automated backup retention window in days; the offline gate requires a positive value."
  type        = number
  default     = 14

  validation {
    condition     = var.backup_retention_days >= 7
    error_message = "backup_retention_days must be at least 7."
  }
}

variable "db_multi_az" {
  description = "Enable a synchronous standby in a second availability zone."
  type        = bool
  default     = true
}

variable "certificate_arn" {
  description = "ARN of the pre-provisioned ACM certificate for the TLS listener."
  type        = string
}

variable "api_image_uri" {
  description = "Immutable image digest reference for the API, supplied by the delivery pipeline."
  type        = string
}

variable "worker_image_uri" {
  description = "Immutable image digest reference for the workers, supplied by the delivery pipeline."
  type        = string
}

variable "api_port" {
  description = "Port the API container listens on."
  type        = number
  default     = 8080
}

variable "api_cpu" {
  description = "Fargate CPU units for one API task."
  type        = number
  default     = 512
}

variable "api_memory" {
  description = "Fargate memory in MiB for one API task."
  type        = number
  default     = 1024
}

variable "worker_cpu" {
  description = "Fargate CPU units for one worker task."
  type        = number
  default     = 512
}

variable "worker_memory" {
  description = "Fargate memory in MiB for one worker task."
  type        = number
  default     = 1024
}

variable "api_desired_count" {
  description = "Baseline number of API tasks."
  type        = number
  default     = 2
}

variable "worker_desired_count" {
  description = "Baseline number of worker tasks."
  type        = number
  default     = 2
}

variable "worker_max_count" {
  description = "Upper bound for worker scale-out."
  type        = number
  default     = 10
}

variable "log_retention_days" {
  description = "Retention for structured application logs in CloudWatch."
  type        = number
  default     = 90
}
