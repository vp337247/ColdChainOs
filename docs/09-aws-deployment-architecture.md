# ColdChainOS: AWS Production Solution Architecture & Deployment Guide (Amazon ECS Fargate)

This document defines the production cloud architecture for deploying **ColdChainOS** on **Amazon Web Services (AWS)** using **Amazon ECS on AWS Fargate**, eliminating Kubernetes cluster management overhead while providing serverless container autoscaling, zero-downtime deployments, and managed high availability.

---

## 1. High-Level AWS Cloud Architecture

```mermaid
graph TD
    subgraph Edge & Ingress
        Route53[Amazon Route 53<br/>DNS & Health Checks] -->|HTTPS 443| CloudFront[AWS CloudFront / WAF<br/>DDoS & Bot Mitigation]
        CloudFront -->|TLS SNI| ALB[AWS Application Load Balancer<br/>ACM SSL Certificate]
    end

    subgraph AWS VPC (3 Availability Zones)
        subgraph Public Subnets
            ALB
            NAT[NAT Gateways]
        end

        subgraph Private Subnets - Compute Layer
            subgraph Amazon ECS Cluster (Serverless AWS Fargate)
                Task1[ColdChainOS Task 1<br/>AZ-a]
                Task2[ColdChainOS Task 2<br/>AZ-b]
                Task3[ColdChainOS Task 3<br/>AZ-c]
            end
            ALB -->|Target Group :8080| Task1 & Task2 & Task3
        end

        subgraph Isolated Subnets - Data & Messaging Layer
            Task1 & Task2 & Task3 -->|HikariCP Pool| RDS[(Amazon RDS PostgreSQL 16<br/>Multi-AZ Primary & Standby)]
            Task1 & Task2 & Task3 -->|Lettuce Pool| ElastiCache[(Amazon ElastiCache Redis 7<br/>Multi-AZ Replication Group)]
            Task1 & Task2 & Task3 -->|Kafka Producer/Consumer| MSK[[Amazon MSK<br/>Managed Kafka 3.7 Cluster]]
        end
    end

    subgraph Security & Secrets
        Secrets[AWS Secrets Manager] -.->|Task Execution Role| Task1 & Task2 & Task3
        KMS[AWS KMS] -.->|Envelope Encryption| RDS & ElastiCache & MSK
    end
```

---

## 2. Infrastructure Sizing & AWS Services Mapping

| System Component | Local Dev Runtime | Production AWS Managed Service | AWS Sizing & Configuration |
| :--- | :--- | :--- | :--- |
| **Compute Application** | Spring Boot container | **Amazon ECS on AWS Fargate** | 3 Tasks minimum (Autoscaling 3–10 tasks based on CPU/Memory) |
| **Relational Storage** | Docker PostgreSQL 16 | **Amazon RDS for PostgreSQL 16** | `db.m6g.xlarge` (Multi-AZ synchronous replication, 250GB gp3) |
| **Cache & Rate Limiter** | Docker Redis 7 | **Amazon ElastiCache for Redis 7** | `cache.m6g.large` (Multi-AZ, 2 nodes with auto-failover) |
| **Streaming & Outbox** | Docker Kafka 3.7 KRaft | **Amazon MSK (Managed Streaming for Kafka)** | 3 $\times$ `kafka.m5.large` brokers across 3 AZs |
| **Container Registry** | Local Docker Daemon | **Amazon ECR (Elastic Container Registry)** | Immutable tag policy with Trivy / ECR basic scanning |
| **Load Balancing** | Docker exposed ports | **AWS Application Load Balancer (ALB)** | HTTPS 443 with ACM SSL Certificate & health checks |
| **Secrets Management** | `.env` local file | **AWS Secrets Manager / SSM Parameter Store** | Encrypted via customer-managed AWS KMS key |

---

## 3. Zero-Downtime Deployment Mechanics

ColdChainOS utilizes Amazon ECS rolling updates paired with Spring Boot Actuator health checks:

1. **ECS Minimum & Maximum Healthy Percent**:
   - `minimumHealthyPercent = 100`
   - `maximumPercent = 200`
   - ECS launches new Fargate tasks first before terminating old tasks.
2. **Readiness Verification (`/actuator/health/readiness`)**:
   - The new Fargate task boots up.
   - Flyway applies any incremental schema migrations.
   - HikariCP pool and Kafka consumers establish broker connections.
   - ALB Target Group health check verifies `/actuator/health/readiness` returns `HTTP 200 OK`.
3. **Traffic Shift**: ALB starts routing live traffic to the healthy new tasks. Old tasks receive a SIGTERM, enter a 30-second graceful shutdown period to complete in-flight transactions, and then terminate.

---

## 4. Disaster Recovery & High Availability (HA)

### 4.1 Automated Database Failover (RPO ~ 0, RTO < 60 seconds)
Amazon RDS Multi-AZ synchronously replicates commits to a standby replica in a second Availability Zone. If the primary instance or datacenter fails:
1. RDS automatically flips the DNS CNAME to the standby replica within 60 seconds.
2. HikariCP detects broken sockets and re-establishes connection pool checkout against the newly promoted primary without restarting the application.

### 4.2 Multi-Tenant Schema Backup Procedure
Individual tenant schemas can be backed up independently for point-in-time recovery (PITR):
```bash
# Export single tenant schema to S3 with KMS encryption
pg_dump -h <RDS_ENDPOINT> -U coldchain_admin -d coldchainos_db \
  --schema="tenant_pharma_corp" -Fc | aws s3 cp - s3://coldchain-backups/tenants/pharma_corp_$(date +%F).dump
```

---

## 5. Security & Regulatory Compliance (GxP / 21 CFR Part 11)

- **Encryption in Transit**: Strict TLS 1.3 enforced at ALB, inter-task communications, and connections to RDS, Redis, and MSK.
- **Encryption at Rest**: All RDS storage volumes, ElastiCache cluster nodes, and MSK partitions encrypted with dedicated AWS KMS keys.
- **IAM Roles for ECS Tasks**: No hardcoded AWS credentials stored inside containers. The ECS Task Role provides temporary least-privilege AWS credentials.
- **Cryptographic Audit Ledger**: Monotonic SHA-256 block chains stored in PostgreSQL schemas; immutable and tamper-evident even across database replication streams.
