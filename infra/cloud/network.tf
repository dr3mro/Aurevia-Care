# Dedicated VPC per environment. Data services and tasks stay in private
# subnets; only the managed load balancer is reachable from operator networks.

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "${local.name_prefix}-vpc" }
}

resource "aws_subnet" "public" {
  count                   = length(var.availability_zones)
  vpc_id                  = aws_vpc.main.id
  availability_zone       = var.availability_zones[count.index]
  cidr_block              = cidrsubnet(var.vpc_cidr, 4, count.index)
  map_public_ip_on_launch = false

  tags = { Name = "${local.name_prefix}-public-${count.index}" }
}

resource "aws_subnet" "private" {
  count             = length(var.availability_zones)
  vpc_id            = aws_vpc.main.id
  availability_zone = var.availability_zones[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, count.index + length(var.availability_zones))

  tags = { Name = "${local.name_prefix}-private-${count.index}" }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = { Name = "${local.name_prefix}-igw" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "${local.name_prefix}-public-routes" }
}

resource "aws_route_table_association" "public" {
  count          = length(aws_subnet.public)
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# Private subnets have no default route to the internet. Every AWS service the
# tasks need is reached through a VPC endpoint, so PHI-bearing tasks cannot
# make arbitrary outbound HTTPS calls and no NAT gateway is a single point of
# failure in one availability zone.
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id

  tags = { Name = "${local.name_prefix}-private-routes" }
}

resource "aws_route_table_association" "private" {
  count          = length(aws_subnet.private)
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}

# Object-store traffic from private subnets stays on the AWS network.
resource "aws_vpc_endpoint" "objects" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.private.id]

  tags = { Name = "${local.name_prefix}-objects-endpoint" }
}

resource "aws_security_group" "endpoints" {
  name        = "${local.name_prefix}-endpoints"
  description = "VPC interface endpoints for AWS services used by the tasks"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name_prefix}-endpoints" }
}

resource "aws_vpc_security_group_ingress_rule" "endpoints_https" {
  security_group_id = aws_security_group.endpoints.id
  cidr_ipv4         = var.vpc_cidr
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "Only private subnets reach the endpoint network interfaces"
}

resource "aws_vpc_security_group_egress_rule" "endpoints" {
  security_group_id = aws_security_group.endpoints.id
  cidr_ipv4         = var.vpc_cidr
  ip_protocol       = "-1"
  description       = "No internet egress from service endpoints"
}

locals {
  interface_endpoints = toset([
    "ecr.dkr",
    "ecr.api",
    "logs",
    "sqs",
    "secretsmanager",
    "kms",
    "sts",
  ])
}

resource "aws_vpc_endpoint" "services" {
  for_each = local.interface_endpoints

  vpc_id              = aws_vpc.main.id
  service_name        = "com.amazonaws.${var.region}.${each.value}"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = aws_subnet.private[*].id
  security_group_ids  = [aws_security_group.endpoints.id]
  private_dns_enabled = true

  tags = { Name = "${local.name_prefix}-${each.value}-endpoint" }
}

resource "aws_security_group" "load_balancer" {
  name        = "${local.name_prefix}-alb"
  description = "Managed TLS ingress for the staff web and API"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name_prefix}-alb" }
}

resource "aws_vpc_security_group_ingress_rule" "https" {
  for_each = toset(var.admin_cidrs)

  security_group_id = aws_security_group.load_balancer.id
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "Approved HTTPS operator networks"
}

resource "aws_vpc_security_group_ingress_rule" "http_redirect" {
  for_each = toset(var.admin_cidrs)

  security_group_id = aws_security_group.load_balancer.id
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  description       = "HTTP only for the HTTPS redirect"
}

resource "aws_vpc_security_group_egress_rule" "load_balancer" {
  security_group_id = aws_security_group.load_balancer.id
  cidr_ipv4         = var.vpc_cidr
  ip_protocol       = "tcp"
  from_port         = var.api_port
  to_port           = var.api_port
  description       = "Forward to API tasks only"
}

resource "aws_security_group" "tasks" {
  name        = "${local.name_prefix}-tasks"
  description = "API and worker tasks in private subnets"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name_prefix}-tasks" }
}

resource "aws_vpc_security_group_ingress_rule" "api_from_load_balancer" {
  security_group_id             = aws_security_group.tasks.id
  referenced_security_group_id  = aws_security_group.load_balancer.id
  ip_protocol                   = "tcp"
  from_port                     = var.api_port
  to_port                       = var.api_port
  description                   = "Only the managed load balancer reaches the API"
}

resource "aws_vpc_security_group_egress_rule" "tasks_private" {
  security_group_id = aws_security_group.tasks.id
  cidr_ipv4         = var.vpc_cidr
  ip_protocol       = "-1"
  description       = "Database and AWS service endpoints inside the VPC; no internet egress"
}

resource "aws_security_group" "database" {
  name        = "${local.name_prefix}-database"
  description = "Private PostgreSQL access from tasks only"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name_prefix}-database" }
}

resource "aws_vpc_security_group_ingress_rule" "database_from_tasks" {
  security_group_id            = aws_security_group.database.id
  referenced_security_group_id = aws_security_group.tasks.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "Only application tasks reach the database"
}

resource "aws_vpc_security_group_egress_rule" "database" {
  security_group_id = aws_security_group.database.id
  cidr_ipv4         = var.vpc_cidr
  ip_protocol       = "-1"
  description       = "No internet egress from the data tier"
}
