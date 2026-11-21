# The same promoted image digest runs the API and the independently scalable
# workers. Work arrives through an encrypted SQS queue with a dead-letter queue.

resource "aws_ecr_repository" "api" {
  name                 = "${local.name_prefix}/api"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = false

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_repository" "worker" {
  name                 = "${local.name_prefix}/worker"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = false

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_sqs_queue" "jobs_dead_letter" {
  name                       = "${local.name_prefix}-jobs-dead-letter"
  message_retention_days     = 14
  kms_master_key_id          = aws_kms_key.data.arn
  sqs_managed_sse_enabled    = false

  tags = { Name = "${local.name_prefix}-jobs-dead-letter" }
}

resource "aws_sqs_queue" "jobs" {
  name                       = "${local.name_prefix}-jobs"
  visibility_timeout_seconds = 120
  message_retention_days     = 14
  kms_master_key_id          = aws_kms_key.data.arn
  sqs_managed_sse_enabled    = false

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.jobs_dead_letter.arn
    maxReceiveCount     = 5
  })

  tags = { Name = "${local.name_prefix}-jobs" }
}

resource "aws_ecs_cluster" "main" {
  name = local.name_prefix

  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_cloudwatch_log_group" "api" {
  name              = "/ecs/${local.name_prefix}/api"
  retention_in_days = var.log_retention_days
  kms_key_id        = aws_kms_key.observability.arn
}

resource "aws_kms_key" "observability" {
  description             = "${local.name_prefix} structured log encryption"
  enable_key_rotation     = true
  deletion_window_in_days = 30

  # CloudWatch Logs needs an explicit service grant in the key policy; the
  # runtime role's IAM policy alone is not sufficient for an encrypted group.
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AccountRoot"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${var.account_id}:root" }
        Action    = "kms:*"
        Resource  = "*"
      },
      {
        Sid       = "CloudWatchLogs"
        Effect    = "Allow"
        Principal = { Service = "logs.${var.region}.amazonaws.com" }
        Action = [
          "kms:Encrypt",
          "kms:Decrypt",
          "kms:ReEncrypt*",
          "kms:GenerateDataKey*",
          "kms:DescribeKey",
        ]
        Resource = "*"
        Condition = {
          StringEquals = { "kms:CallerAccount" = var.account_id }
          StringLike   = { "kms:ViaService" = "logs.${var.region}.amazonaws.com" }
        }
      },
    ]
  })

  tags = { Name = "${local.name_prefix}-logs-key" }
}

resource "aws_cloudwatch_log_group" "worker" {
  name              = "/ecs/${local.name_prefix}/worker"
  retention_in_days = var.log_retention_days
  kms_key_id        = aws_kms_key.observability.arn
}

resource "aws_ecs_task_definition" "api" {
  family                   = "${local.name_prefix}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.api_cpu
  memory                   = var.api_memory
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.api.arn

  container_definitions = jsonencode([{
    name      = "api"
    image     = var.api_image_uri
    essential = true

    portMappings = [{
      containerPort = var.api_port
      hostPort      = var.api_port
      protocol      = "tcp"
    }]

    environment = [
      { name = "SPRING_PROFILES_ACTIVE", value = "cloud" },
      { name = "SMARTCLINIC_REGION", value = var.region },
      {
        name  = "SMARTCLINIC_DATABASE_SECRET_ARN"
        value = aws_db_instance.main.master_user_secret[0].secret_arn
      },
    ]

    # ECS resolves the Secrets Manager reference at task start. Only the
    # password field is injected; no secret value is stored in this source.
    secrets = [
      {
        name      = "SPRING_DATASOURCE_PASSWORD"
        valueFrom = "${aws_db_instance.main.master_user_secret[0].secret_arn}:password::"
      },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.api.name
        "awslogs-region"        = var.region
        "awslogs-stream-prefix" = "api"
      }
    }

    healthCheck = {
      command     = ["CMD-SHELL", "curl -fsS http://127.0.0.1:${var.api_port}/health/live || exit 1"]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 60
    }
  }])
}

resource "aws_ecs_task_definition" "worker" {
  family                   = "${local.name_prefix}-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.worker_cpu
  memory                   = var.worker_memory
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.worker.arn

  container_definitions = jsonencode([{
    name      = "worker"
    image     = var.worker_image_uri
    essential = true
    command   = ["worker"]

    environment = [
      { name = "SPRING_PROFILES_ACTIVE", value = "cloud" },
      { name = "SMARTCLINIC_REGION", value = var.region },
      { name = "SMARTCLINIC_QUEUE_URL", value = aws_sqs_queue.jobs.id },
      {
        name  = "SMARTCLINIC_DATABASE_SECRET_ARN"
        value = aws_db_instance.main.master_user_secret[0].secret_arn
      },
    ]

    secrets = [
      {
        name      = "SPRING_DATASOURCE_PASSWORD"
        valueFrom = "${aws_db_instance.main.master_user_secret[0].secret_arn}:password::"
      },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.worker.name
        "awslogs-region"        = var.region
        "awslogs-stream-prefix" = "worker"
      }
    }
  }])
}

resource "aws_lb" "api" {
  name                       = "${local.name_prefix}-api"
  internal                   = false
  load_balancer_type         = "application"
  security_groups            = [aws_security_group.load_balancer.id]
  subnets                    = aws_subnet.public[*].id
  idle_timeout               = 60
  enable_deletion_protection = var.environment == "production"

  tags = { Name = "${local.name_prefix}-api" }
}

resource "aws_lb_target_group" "api" {
  name        = "${local.name_prefix}-api"
  port        = var.api_port
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = aws_vpc.main.id

  health_check {
    path                = "/health/live"
    matcher             = "200"
    interval            = 30
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.api.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}

resource "aws_lb_listener" "http_redirect" {
  load_balancer_arn = aws_lb.api.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_ecs_service" "api" {
  name                              = "${local.name_prefix}-api"
  cluster                           = aws_ecs_cluster.main.id
  task_definition                   = aws_ecs_task_definition.api.arn
  desired_count                     = var.api_desired_count
  launch_type                       = "FARGATE"
  health_check_grace_period_seconds = 60

  network_configuration {
    subnets          = aws_subnet.private[*].id
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "api"
    container_port   = var.api_port
  }

  depends_on = [aws_lb_listener.https]
}

resource "aws_ecs_service" "worker" {
  name            = "${local.name_prefix}-worker"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.worker.arn
  desired_count   = var.worker_desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = aws_subnet.private[*].id
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = false
  }
}

# API tasks scale on utilisation; workers scale on queue backlog and age, as
# required by the deployment design handoff.
resource "aws_appautoscaling_target" "api" {
  max_capacity       = var.api_desired_count * 4
  min_capacity       = var.api_desired_count
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.api.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "api_cpu" {
  name               = "${local.name_prefix}-api-cpu"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.api.resource_id
  scalable_dimension = aws_appautoscaling_target.api.scalable_dimension
  service_namespace  = aws_appautoscaling_target.api.service_namespace

  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_in_cooldown  = 300
    scale_out_cooldown = 60

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

resource "aws_appautoscaling_target" "worker" {
  max_capacity       = var.worker_max_count
  min_capacity       = var.worker_desired_count
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.worker.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "worker_backlog_out" {
  name               = "${local.name_prefix}-worker-backlog-out"
  policy_type        = "StepScaling"
  resource_id        = aws_appautoscaling_target.worker.resource_id
  scalable_dimension = aws_appautoscaling_target.worker.scalable_dimension
  service_namespace  = aws_appautoscaling_target.worker.service_namespace

  step_scaling_policy_configuration {
    adjustment_type         = "ChangeInCapacity"
    cooldown                = 120
    metric_aggregation_type = "Maximum"

    step_adjustment {
      scaling_adjustment          = 2
      metric_interval_lower_bound = 0
    }
  }
}

resource "aws_appautoscaling_policy" "worker_backlog_in" {
  name               = "${local.name_prefix}-worker-backlog-in"
  policy_type        = "StepScaling"
  resource_id        = aws_appautoscaling_target.worker.resource_id
  scalable_dimension = aws_appautoscaling_target.worker.scalable_dimension
  service_namespace  = aws_appautoscaling_target.worker.service_namespace

  step_scaling_policy_configuration {
    adjustment_type         = "ChangeInCapacity"
    cooldown                = 300
    metric_aggregation_type = "Maximum"

    step_adjustment {
      scaling_adjustment          = -1
      metric_interval_upper_bound = 0
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "worker_backlog" {
  alarm_name          = "${local.name_prefix}-worker-backlog"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Maximum"
  threshold           = 50

  dimensions = {
    QueueName = aws_sqs_queue.jobs.name
  }

  alarm_actions = [aws_appautoscaling_policy.worker_backlog_out.arn]
}

# Queue depth alone cannot see a small but stalled backlog; queue age scales
# workers independently when the oldest job approaches its timeout.
resource "aws_cloudwatch_metric_alarm" "worker_queue_age" {
  alarm_name          = "${local.name_prefix}-worker-queue-age"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "ApproximateAgeOfOldestMessage"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Maximum"
  threshold           = 90

  dimensions = {
    QueueName = aws_sqs_queue.jobs.name
  }

  alarm_actions = [aws_appautoscaling_policy.worker_backlog_out.arn]
}

resource "aws_cloudwatch_metric_alarm" "worker_drained" {
  alarm_name          = "${local.name_prefix}-worker-drained"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 5
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Maximum"
  threshold           = 5

  dimensions = {
    QueueName = aws_sqs_queue.jobs.name
  }

  alarm_actions = [aws_appautoscaling_policy.worker_backlog_in.arn]
}
