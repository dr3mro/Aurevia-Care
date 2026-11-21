# Private, encrypted object storage for quarantined uploads, approved originals,
# rendered reports and expiring exports. ACLs and public access are disabled and
# a bucket policy denies every request that is not TLS.

resource "aws_kms_key" "objects" {
  description             = "${local.name_prefix} object encryption"
  enable_key_rotation     = true
  deletion_window_in_days = 30

  tags = { Name = "${local.name_prefix}-objects-key" }
}

resource "aws_kms_alias" "objects" {
  name          = "alias/${local.name_prefix}-objects"
  target_key_id = aws_kms_key.objects.key_id
}

resource "aws_s3_bucket" "documents" {
  bucket = var.bucket_name

  tags = { Name = "${local.name_prefix}-documents" }
}

resource "aws_s3_bucket_ownership_controls" "documents" {
  bucket = aws_s3_bucket.documents.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "documents" {
  bucket                  = aws_s3_bucket.documents.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "documents" {
  bucket = aws_s3_bucket.documents.id

  rule {
    apply_server_side_encryption_by_default {
      kms_master_key_id = aws_kms_key.objects.arn
      sse_algorithm     = "aws:kms"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_versioning" "documents" {
  bucket = aws_s3_bucket.documents.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "documents" {
  bucket = aws_s3_bucket.documents.id

  rule {
    id     = "expire-exports"
    status = "Enabled"

    filter {
      prefix = "exports/"
    }

    expiration {
      days = 30
    }
  }
}

resource "aws_s3_bucket_policy" "documents" {
  bucket = aws_s3_bucket.documents.id

  # The wildcard principal is a Deny guardrail only: it refuses insecure
  # transport and grants no access. Allow statements are always scoped.
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Action    = ["s3:*"]
      Principal = "*"
      Resource = [
        aws_s3_bucket.documents.arn,
        "${aws_s3_bucket.documents.arn}/*",
      ]
      Condition = {
        Bool = { "aws:SecureTransport" = "false" }
      }
    }]
  })
}
