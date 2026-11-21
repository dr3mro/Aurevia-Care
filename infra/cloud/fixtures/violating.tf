# SYNTHETIC negative fixture. Every OPS-02 control below is deliberately
# broken so the offline gate must fail. Never apply this file.

resource "aws_s3_bucket" "public_fixture" {
  bucket = "synthetic-public-fixture"
  acl    = "public-read"
}

resource "aws_db_instance" "unencrypted_fixture" {
  identifier              = "synthetic-unencrypted-fixture"
  storage_encrypted       = false
  backup_retention_period = 0
}

resource "aws_iam_role_policy" "wildcard_fixture" {
  name = "synthetic-wildcard-fixture"
  role = "synthetic-role"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "*"
      Resource = "*"
    }]
  })
}
