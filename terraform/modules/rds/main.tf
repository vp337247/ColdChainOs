variable "app_name" { type = string }
variable "environment" { type = string }
variable "vpc_id" { type = string }
variable "subnet_ids" { type = list(string) }
variable "ecs_security_group_id" { type = string }
variable "instance_class" { default = "db.m6g.xlarge" }
variable "allocated_storage" { default = 250 }
variable "multi_az" { default = true }
variable "db_name" { default = "coldchainos_db" }
variable "db_username" { default = "coldchain_admin" }

# Security Group for RDS PostgreSQL
resource "aws_security_group" "rds" {
  name        = "${var.app_name}-${var.environment}-rds-sg"
  description = "Allow inbound PostgreSQL from ECS tasks only"
  vpc_id      = var.vpc_id

  ingress {
    description     = "PostgreSQL from ECS"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [var.ecs_security_group_id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.app_name}-${var.environment}-rds-sg"
  }
}

# Subnet Group
resource "aws_db_subnet_group" "main" {
  name       = "${var.app_name}-${var.environment}-db-subnet-group"
  subnet_ids = var.subnet_ids

  tags = {
    Name = "${var.app_name}-${var.environment}-db-subnet-group"
  }
}

# Random Master Password
resource "random_password" "db_password" {
  length  = 24
  special = false
}

# AWS Secrets Manager Secret for DB credentials
resource "aws_secretsmanager_secret" "db_credentials" {
  name                    = "${var.app_name}/${var.environment}/database"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "db_credentials" {
  secret_id = aws_secretsmanager_secret.db_credentials.id
  secret_string = jsonencode({
    username = var.db_username
    password = random_password.db_password.result
    dbname   = var.db_name
  })
}

# Parameter Group for PostgreSQL 16 optimization
resource "aws_db_parameter_group" "pg16" {
  name   = "${var.app_name}-${var.environment}-pg16-params"
  family = "postgres16"

  parameter {
    name  = "shared_preload_libraries"
    value = "pg_stat_statements"
  }

  parameter {
    name  = "work_mem"
    value = "16384" # 16MB
  }
}

# Amazon RDS PostgreSQL 16 Instance
resource "aws_db_instance" "postgres" {
  identifier             = "${var.app_name}-${var.environment}-postgres"
  engine                 = "postgres"
  engine_version         = "16.2"
  instance_class         = var.instance_class
  allocated_storage      = var.allocated_storage
  max_allocated_storage  = 1000
  storage_type           = "gp3"
  storage_encrypted      = true
  multi_az               = var.multi_az

  db_name  = var.db_name
  username = var.db_username
  password = random_password.db_password.result

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  parameter_group_name   = aws_db_parameter_group.pg16.name

  backup_retention_period = 14
  backup_window           = "03:00-04:00"
  maintenance_window      = "Sun:04:30-Sun:05:30"
  deletion_protection     = var.environment == "prod" ? true : false
  skip_final_snapshot     = var.environment == "prod" ? false : true
  final_snapshot_identifier = "${var.app_name}-${var.environment}-final-snapshot"

  tags = {
    Name = "${var.app_name}-${var.environment}-postgres"
  }
}

output "endpoint" { value = aws_db_instance.postgres.endpoint }
output "address" { value = aws_db_instance.postgres.address }
output "secret_arn" { value = aws_secretsmanager_secret.db_credentials.arn }
