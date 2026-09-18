# =========================================================================
# ColdChainOS Development Environment (Cost-Optimized)
# =========================================================================
aws_region             = "us-east-1"
app_name               = "coldchainos"
environment            = "dev"

db_instance_class      = "db.t4g.medium"
db_allocated_storage   = 50

redis_node_type        = "cache.t4g.micro"

ecs_cpu                = "512"   # 0.5 vCPU
ecs_memory             = "1024"  # 1 GB RAM
ecs_task_desired_count = 1
