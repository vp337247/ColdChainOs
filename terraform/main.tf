terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.40"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # In production, store state remotely in S3 with DynamoDB locking:
  # backend "s3" {
  #   bucket         = "coldchainos-terraform-state"
  #   key            = "coldchainos/production/terraform.tfstate"
  #   region         = "us-east-1"
  #   dynamodb_table = "coldchainos-terraform-locks"
  #   encrypt        = true
  # }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "ColdChainOS"
      Environment = var.environment
      ManagedBy   = "Terraform"
      Compliance  = "FDA-21CFR-Part11-GxP"
    }
  }
}

# 1. VPC & Networking
module "vpc" {
  source      = "./modules/vpc"
  app_name    = var.app_name
  environment = var.environment
}

# 2. Application Load Balancer
module "alb" {
  source             = "./modules/alb"
  app_name           = var.app_name
  environment        = var.environment
  vpc_id             = module.vpc.vpc_id
  public_subnet_ids  = module.vpc.public_subnets
}

# 3. ECS Fargate Cluster & Service (Creates ECS Security Group)
module "ecs" {
  source                = "./modules/ecs"
  app_name              = var.app_name
  environment           = var.environment
  vpc_id                = module.vpc.vpc_id
  private_subnet_ids    = module.vpc.private_subnets
  alb_security_group_id = module.alb.alb_security_group_id
  target_group_arn      = module.alb.target_group_arn
  db_address            = module.rds.address
  db_secret_arn         = module.rds.secret_arn
  redis_endpoint        = module.elasticache.primary_endpoint_address
  cpu                   = var.ecs_cpu
  memory                = var.ecs_memory
  desired_count         = var.ecs_task_desired_count
}

# 4. Amazon RDS PostgreSQL 16 Multi-AZ
module "rds" {
  source                = "./modules/rds"
  app_name              = var.app_name
  environment           = var.environment
  vpc_id                = module.vpc.vpc_id
  subnet_ids            = module.vpc.isolated_subnets
  ecs_security_group_id = module.ecs.ecs_security_group_id
  instance_class        = var.db_instance_class
  allocated_storage     = var.db_allocated_storage
  multi_az              = var.environment == "prod" ? true : false
}

# 5. Amazon ElastiCache Redis 7
module "elasticache" {
  source                = "./modules/elasticache"
  app_name              = var.app_name
  environment           = var.environment
  vpc_id                = module.vpc.vpc_id
  subnet_ids            = module.vpc.isolated_subnets
  ecs_security_group_id = module.ecs.ecs_security_group_id
  node_type             = var.redis_node_type
  num_cache_nodes       = var.environment == "prod" ? 2 : 1
}
