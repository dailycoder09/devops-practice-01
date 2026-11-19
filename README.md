# AWS Learning Project: Product, Inventory and UI

We are learning AWS and DevOps with a real app. The app has three parts. We do not add new features. We add AWS services one stage at a time, and each stage fixes a real need of the app.

## What is in this folder

| Folder | What it is | Port |
|---|---|---|
| `product-service/` | Spring Boot 3.2 API for products (Java 17) | 8081 |
| `inventory-service/` | Spring Boot 3.2 API for stock (Java 17) | 8082 |
| `ui-app/` | React + TypeScript web app (Vite), served by nginx in a container | 5173 (dev), 8080 (container) |
| `deploy/` | `local/`: run the whole shop in containers on your Mac. `aws/`: the EC2 start-up script | 8000 (local gateway) |

The three apps and the `deploy/` folder are copies from `Microservices-demo`. Nothing else was copied. Keep `product-service/` and `inventory-service/` side by side: one inventory-service test reads `../product-service/src/main/resources/db/migration`.

Each project has its own `README.md` with more detail.

**Load balancer health checks:** see [HEALTH-CHECKS.md](HEALTH-CHECKS.md) for every health check path, the target group settings and what to do when a target is unhealthy.

## What you need on your Mac

- **Java 17.** The default `java` on this Mac is version 8, so set it in each terminal:
  `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`
  Maven then uses Java 17. But a plain `java` command still finds Java 8 first on this Mac, so start the jars with `"$JAVA_HOME/bin/java"` (as shown below).
- **Maven 3.9** and **Node 22** (with npm).
- **Podman** to build the container images (Docker is not installed here).
- Later: **AWS CLI** (not installed yet) and **Terraform** (installed).

## Run it on your Mac

Open three terminals.

```bash
# 1. product-service (port 8081)
cd product-service
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -q -DskipTests package
"$JAVA_HOME/bin/java" -jar target/*.jar

# 2. inventory-service (port 8082)
cd inventory-service
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -q -DskipTests package
"$JAVA_HOME/bin/java" -jar target/*.jar

# 3. ui-app (port 5173)
cd ui-app
npm ci
npm run dev
```

Open http://localhost:5173. The dev server sends `/api/products` to port 8081 and `/api/inventory` to port 8082. On AWS, the load balancer does that job.

Quick checks:

```bash
curl -s localhost:8081/actuator/health
curl -s localhost:8082/actuator/health
curl -s 'localhost:8081/api/products?size=1'
curl -s 'localhost:8082/api/inventory?size=1'
```

Both services keep their data in an H2 file database under `data/`. The tables are created at start-up by Flyway.

## Run the tests

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
(cd product-service && mvn -q test)
(cd inventory-service && mvn -q test)
(cd ui-app && npm test)
```

## Build the container images

```bash
podman build -t product-service:1.0 product-service
podman build -t inventory-service:1.0 inventory-service
podman build -t ui-app:1.0 ui-app
```

## Run the whole shop in containers (like AWS)

`deploy/local/` starts a local copy of the AWS layout: two simulated VMs (a and b), each with product-service, inventory-service and ui-app, behind a gateway that applies the same path rules as the load balancer.

```bash
deploy/local/up.sh      # build four images, start everything, wait until ready
deploy/local/down.sh    # stop and remove the containers and the network
```

Then open http://localhost:8000. It needs the Podman machine running with about 6 GB of memory (see `deploy/local/README.md`). The first build takes a few minutes.

`deploy/aws/user-data.sh` is the start-up script for an EC2 instance. It installs Docker, builds the three apps and starts them. It still clones the **old** repo (`dailycoder09/aws-practice`), so point it at this project's own GitHub repo before Stage 1.

## Settings you can change with environment variables

| Variable | Used by | Meaning |
|---|---|---|
| `INVENTORY_SERVICE_URL` | product-service | Where to find inventory-service (default `http://localhost:8082`) |
| `PRODUCT_SERVICE_URL` | inventory-service | Where to find product-service (default `http://localhost:8081`) |
| `IMAGE_BASE_URL` | product-service | Where the product photos are served from (for example an S3 address) |
| `CLOUD_METADATA_ENABLED` | both | Read EC2 instance details for the `server-info` endpoint (default on; it fails quietly off AWS) |
| `DB_FILE_PATH`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | both | Database location and login |

## The plan: AWS stages

Each stage ends with a working app that can be rebuilt from scratch in one sandbox session.

| Stage | What we do | AWS services |
|---|---|---|
| 0 | Copy the three apps, check they build, test and run, read what the sandbox allows | none yet |
| 1 | The baseline as Terraform: network, two servers, load balancer with path rules | VPC, EC2, ALB, Security Groups, IAM |
| 2 | One shared database instead of an H2 file on each server | RDS (PostgreSQL), Parameter Store or Secrets Manager |
| 3 | Photos in a bucket, and a CDN in front | S3, CloudFront |
| 4 | Containers instead of building on the server at boot | ECR, ECS |
| 5 | Automatic build and test, logs and alarms | GitHub Actions, CloudWatch, SNS |
| 6 | Scale and harden | Auto Scaling, NAT or VPC endpoints, WAF, ACM, Route 53 |
| Extras | Only if the sandbox allows it | Cognito, X-Ray, API Gateway, EKS |

Stages 1 to 6 are outlines. Before each stage we check that its services are allowed in the sandbox, then plan it in detail.

## Rules for this project

- Only these three services and the `deploy/` folder live here. No other code from the old repo.
- The old `Microservices-demo` folder is never changed.
- The owner makes all git commits. This folder is not a git repo yet.
- There is no order service. If you have one, tell us its path and it can become an extra stage.

## Sandbox notes (not yet confirmed)

We think the AWS access is a Pluralsight AWS sandbox. From Pluralsight's help pages: only `us-east-1` and `us-west-2`, sessions of about 4 hours, everything is deleted when a session ends, and some services or actions are blocked. Because of this, the environment must be rebuildable with one command (Terraform, local state). Do not try to work around blocked actions.

## Status

Stage 0:

- [x] Three projects copied, and checked to match the source
- [x] UI: `npm ci`, 185 tests pass, build works
- [x] `deploy/` folder copied (matches the source)
- [ ] Try `deploy/local/up.sh` (the whole shop in containers)
- [ ] Before Stage 1: point `deploy/aws/user-data.sh` at this project's own GitHub repo
- [x] product-service tests pass (Java 17)
- [x] inventory-service tests pass (Java 17)
- [ ] Local run of all three apps
- [ ] Podman image builds
- [ ] AWS CLI installed (you)
- [ ] Sandbox started and allowed services written down (you)
# devops-practice-01
