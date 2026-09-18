variable "aws_region" {
  type        = string
  default     = "us-east-1"
  description = "AWS deployment region"
}

variable "app_name" {
  type        = string
  default     = "coldchainos"
  description = "Application name used for naming resources"
}

variable "environment" {
  type        = string
  default     = "prod"
  description = "Target deployment environment (dev, staging, prod)"
}

variable "db_instance_class" {
  type        = string
  default     = "db.m6g.xlarge"
  description = "RDS PostgreSQL instance type"
}

variable "db_allocated_storage" {
  type        = number
  default     = 250
  description = "RDS allocated storage in GB"
}

variable "redis_node_type" {
  type        = string
  default     = "cache.m6g.large"
  description = "ElastiCache Redis node type"
}

variable "ecs_cpu" {
  type        = string
  default     = "1024"
  description = "ECS Fargate CPU units (1024 = 1 vCPU)"
}

variable "ecs_memory" {
  type        = string
  default     = "2048"
  description = "ECS Fargate Memory in MB (2048 = 2 GB)"
}

variable "ecs_task_desired_count" {
  type        = number
  default     = 3
  description = "Desired number of running ECS Fargate tasks"
}
