# ColdChainOS: AWS Production Solution Architecture & Deployment Guide

This document defines the production cloud architecture for deploying **ColdChainOS** on **Amazon Web Services (AWS)** using enterprise managed services, zero-downtime rolling updates, and strict multi-tenant isolation.

---

## 1. High-Level AWS Cloud Architecture

```mermaid
graph TD
    subgraph Edge & Ingress
        Route53[Amazon Route 53<br/>DNS & Health Checks] -->|HTTPS 443| CloudFront[AWS CloudFront / WAF<br/>DDoS & Bot Mitigation]
        CloudFront -->|TLS SNI| ALB[AWS Application Load Balancer<br/>ACM Certificate]
    end

    subgraph AWS VPC (3 Availability Zones)
        subgraph Public Subnets
            ALB
            NAT[NAT Gateways]
        end

        subgraph Private Subnets - Compute Layer
            subgraph Amazon EKS Cluster
                Pod1[ColdChainOS Pod 1<br/>AZ-a]
                Pod2[ColdChainOS Pod 2<br/>AZ-b]
                Pod3[ColdChainOS Pod 3<br/>AZ-c]
            end
            ALB -->|Target Group :8080| Pod1 & Pod2 & Pod3
        end

        subgraph Isolated Subnets - Data & Messaging Layer
            Pod1 & Pod2 & Pod3 -->|HikariCP Pool| RDS[(Amazon RDS PostgreSQL 16<br/>Multi-AZ Primary & Standby)]
            Pod1 & Pod2 & Pod3 -->|Lettuce Pool| ElastiCache[(Amazon ElastiCache Redis 7<br/>Multi-AZ Replication Group)]
            Pod1 & Pod2 & Pod3 -->|Kafka Producer/Consumer| MSK[[Amazon MSK<br/>Managed Kafka 3.7 Cluster]]
        end
    end

    subgraph Security & Secrets
        Secrets[AWS Secrets Manager] -.->|IRSA / External Secrets| Pod1 & Pod2 & Pod3
        KMS[AWS KMS] -.->|Envelope Encryption| RDS & ElastiCache & MSK
    end
```

---

## 2. Infrastructure Sizing & AWS Services Mapping

| System Component | Local Dev Runtime | Production AWS Managed Service | AWS Instance / Sizing Recommendation |
| :--- | :--- | :--- | :--- |
| **Compute Application** | Spring Boot container | **Amazon EKS (Elastic Kubernetes Service)** | 3 $\times$ `m6i.xlarge` nodes (HPA: 3–10 pods) |
| **Relational Storage** | Docker PostgreSQL 16 | **Amazon RDS for PostgreSQL 16** | `db.m6g.xlarge` (Multi-AZ, 250GB gp3, 3000 IOPS) |
| **Cache & Rate Limiter** | Docker Redis 7 | **Amazon ElastiCache for Redis 7** | `cache.m6g.large` (Multi-AZ, 2 nodes with auto-failover) |
| **Streaming & Outbox** | Docker Kafka 3.7 KRaft | **Amazon MSK (Managed Streaming for Kafka)** | 3 $\times$ `kafka.m5.large` brokers across 3 AZs |
| **Container Registry** | Local Docker Daemon | **Amazon ECR (Elastic Container Registry)** | Immutable tag policy with Trivy vulnerability scans |
| **Load Balancing** | Docker exposed ports | **AWS Application Load Balancer (ALB)** | AWS Load Balancer Controller with ACM SSL |
| **Secrets Management** | `.env` local file | **AWS Secrets Manager / SSM Parameter Store** | Encrypted via customer-managed AWS KMS key |

---

## 3. Zero-Downtime Deployment Mechanics

ColdChainOS utilizes Kubernetes **RollingUpdate** with Spring Boot Actuator health checks to guarantee zero dropped requests during new releases:

1. **Pre-Stop Lifecycle Hook**: When Kubernetes initiates a pod termination, the app enters a `30s` graceful shutdown period, finishing in-flight database transactions and Kafka commits before closing.
2. **Readiness Probe (`/actuator/health/readiness`)**:
   - The new container starts up.
   - Flyway applies any incremental schema migrations (backward-compatible).
   - HikariCP pool and Kafka consumers establish broker connections.
   - Kubernetes **only** routes live traffic to the pod once `/actuator/health/readiness` returns `HTTP 200 OK`.
3. **Traffic Shift**: AWS ALB routes new traffic to updated pods. Old pods terminate cleanly without connection resets.

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

- **Encryption in Transit**: Strict TLS 1.3 enforced at ALB, inter-pod communications, and connections to RDS, Redis, and MSK.
- **Encryption at Rest**: All RDS storage volumes, ElastiCache cluster nodes, and MSK partitions encrypted with dedicated AWS KMS keys.
- **IAM Roles for Service Accounts (IRSA)**: No hardcoded AWS credentials stored inside containers. Pods assume temporary IAM credentials using OpenID Connect (OIDC).
- **Cryptographic Audit Ledger**: Monotonic SHA-256 block chains stored in PostgreSQL schemas; immutable and tamper-evident even across database replication streams.
