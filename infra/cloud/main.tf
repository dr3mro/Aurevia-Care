terraform {
  required_version = ">= 1.10.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # State never lives in this repository. The S3 backend is only partially
  # configured here so staging and production each pass their own encrypted
  # bucket, state key and KMS key with -backend-config at plan/apply time.
  # Terraform >= 1.10 supplies native S3 state locking via use_lockfile=true
  # in the backend config; see README.md for the exact commands.
  backend "s3" {}
}

provider "aws" {
  region              = var.region
  allowed_account_ids = [var.account_id]

  default_tags {
    tags = local.tags
  }
}

locals {
  name_prefix = "${var.environment}-smartclinic"

  tags = {
    Environment = var.environment
    ManagedBy   = "terraform"
    Service     = "smartclinic"
    DataClass   = "synthetic-or-approved"
  }
}
