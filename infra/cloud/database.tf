# Private, encrypted, backed-up PostgreSQL. Credentials are generated and held
# by RDS in Secrets Manager; no password is known to this module.

resource "aws_kms_key" "data" {
  description             = "${local.name_prefix} database and queue encryption"
  enable_key_rotation     = true
  deletion_window_in_days = 30

  tags = { Name = "${local.name_prefix}-data-key" }
}

resource "aws_kms_alias" "data" {
  name          = "alias/${local.name_prefix}-data"
  target_key_id = aws_kms_key.data.key_id
}

resource "aws_db_subnet_group" "main" {
  name       = "${local.name_prefix}-database"
  subnet_ids = aws_subnet.private[*].id

  tags = { Name = "${local.name_prefix}-database-subnets" }
}

resource "aws_db_instance" "main" {
  identifier = var.db_identifier
  engine     = "postgres"
  # Pin the engine minor line so staging and production apply the same digest
  # of schema-compatible software promoted through the delivery pipeline.
  engine_version = "16.3"
  instance_class = var.db_instance_class

  allocated_storage = 100
  storage_type      = "gp3"
  storage_encrypted = true
  kms_key_id        = aws_kms_key.data.arn

  backup_retention_period = var.backup_retention_days
  backup_window           = "03:00-04:00"
  maintenance_window      = "sun:04:30-sun:05:30"
  multi_az                = var.db_multi_az

  publicly_accessible = false
  deletion_protection = true
  skip_final_snapshot = false
  final_snapshot_identifier = "${var.db_identifier}-final"

  iam_database_authentication_enabled = true
  manage_master_user_password         = true
  master_username                     = var.db_master_username

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.database.id]

  enabled_cloudwatch_logs_exports  = ["postgresql", "upgrade"]
  performance_insights_enabled     = true
  performance_insights_kms_key_id  = aws_kms_key.data.arn

  tags = { Name = "${local.name_prefix}-database" }
}
