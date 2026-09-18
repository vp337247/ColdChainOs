# =========================================================================
# ColdChainOS Production Environment (High Availability & GxP Compliance)
# =========================================================================
aws_region             = "us-east-1"
app_name               = "coldchainos"
environment            = "prod"

db_instance_class      = "db.m6g.xlarge"
db_allocated_storage   = 250

redis_node_type        = "cache.m6g.large"

ecs_cpu                = "1024"  # 1.0 vCPU
ecs_memory             = "2048"  # 2.0 GB RAM
ecs_task_desired_count = 3       # Multi-AZ distribution (3 tasks across 3 AZs)
