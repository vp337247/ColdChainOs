output "alb_dns_name" {
  description = "Public DNS name of the Application Load Balancer"
  value       = module.alb.alb_dns_name
}

output "ecr_repository_url" {
  description = "Amazon ECR Docker Repository URL for container pushes"
  value       = module.ecs.ecr_repository_url
}

output "rds_endpoint" {
  description = "Amazon RDS PostgreSQL 16 connection endpoint"
  value       = module.rds.endpoint
}

output "elasticache_primary_endpoint" {
  description = "Amazon ElastiCache Redis primary endpoint"
  value       = module.elasticache.primary_endpoint_address
}

output "ecs_cluster_name" {
  description = "Amazon ECS Cluster Name"
  value       = module.ecs.cluster_name
}

output "ecs_service_name" {
  description = "Amazon ECS Service Name"
  value       = module.ecs.service_name
}
