variable "app_name" { type = string }
variable "environment" { type = string }
variable "vpc_id" { type = string }
variable "subnet_ids" { type = list(string) }
variable "ecs_security_group_id" { type = string }
variable "node_type" { default = "cache.m6g.large" }
variable "num_cache_nodes" { default = 2 }

# Security Group for Redis
resource "aws_security_group" "redis" {
  name        = "${var.app_name}-${var.environment}-redis-sg"
  description = "Allow inbound Redis traffic from ECS tasks only"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Redis from ECS tasks"
    from_port       = 6379
    to_port         = 6379
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
    Name = "${var.app_name}-${var.environment}-redis-sg"
  }
}

# ElastiCache Subnet Group
resource "aws_elasticache_subnet_group" "main" {
  name       = "${var.app_name}-${var.environment}-redis-subnet-group"
  subnet_ids = var.subnet_ids
}

# Parameter Group for Redis 7 with LRU eviction
resource "aws_elasticache_parameter_group" "redis7" {
  name   = "${var.app_name}-${var.environment}-redis7-params"
  family = "redis7"

  parameter {
    name  = "maxmemory-policy"
    value = "volatile-lru"
  }
}

# Redis 7 Replication Group (Multi-AZ)
resource "aws_elasticache_replication_group" "redis" {
  replication_group_id       = "${var.app_name}-${var.environment}-redis"
  description                = "ColdChainOS Redis 7 Cache & Rate Limiter"
  node_type                  = var.node_type
  num_cache_clusters         = var.num_cache_nodes
  parameter_group_name       = aws_elasticache_parameter_group.redis7.name
  port                       = 6379
  subnet_group_name          = aws_elasticache_subnet_group.main.name
  security_group_ids         = [aws_security_group.redis.id]
  automatic_failover_enabled = var.num_cache_nodes > 1 ? true : false
  multi_az_enabled           = var.num_cache_nodes > 1 ? true : false
  at_rest_encryption_enabled = true
  transit_encryption_enabled = false # Set true if configuring SSL in application.yml

  tags = {
    Name = "${var.app_name}-${var.environment}-redis"
  }
}

output "primary_endpoint_address" {
  value = aws_elasticache_replication_group.redis.primary_endpoint_address
}
