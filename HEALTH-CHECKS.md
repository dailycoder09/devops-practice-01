# Health checks reference

Use this page when you create the target groups on the load balancer (ALB).

Checked on 2026-11-19 on this Mac, with all five services (product, inventory, user, cart, order) running locally (profile `dev`). Anything that was not tested is marked **not tested**.

## 1. What to put in each target group

| Target group | App | Protocol | Port | Health check path | Success codes |
|---|---|---|---|---|---|
| `product-tg` | product-service | HTTP | 8081 | `/actuator/health` | 200 |
| `inventory-tg` | inventory-service | HTTP | 8082 | `/actuator/health` | 200 |
| `ui-tg` | ui-app (nginx) | HTTP | 8080 | `/healthz` | 200 |
| `user-tg` (planned, with the AWS stages) | user-service | HTTP | 8083 | `/actuator/health` | 200 |
| `cart-tg` (planned, with the AWS stages) | cart-service | HTTP | 8084 | `/actuator/health` | 200 |
| `order-tg` (planned, with the AWS stages) | order-service | HTTP | 8085 | `/actuator/health` | 200 |

Listener rules (the first two are from your old ALB notes; the others match `deploy/local/gateway/gateway.conf`):

| Priority | If the path is | Forward to |
|---|---|---|
| 10 | `/api/products*` | `product-tg` |
| 20 | `/api/inventory*` | `inventory-tg` |
| 30 (planned) | `/api/users*` | `user-tg` |
| 40 (planned) | `/api/cart*` | `cart-tg` |
| 50 (planned) | `/api/orders*` | `order-tg` |
| default | everything else | `ui-tg` |

Use plain "Forward to one target group" actions, not weighted forwarding. Register **both VMs** in each target group.

The load balancer sends each health check straight to the target, on the port and path set in the target group. So `/actuator/health` does not need its own listener rule. The target group itself must be used by a listener rule, or its targets show as `unused`.

## 2. Health check settings

| Setting | AWS default | Allowed range | Suggested for practice |
|---|---|---|---|
| Interval | 30 s | 5 to 300 s | 15 s |
| Timeout | 5 s | 2 to 120 s | 5 s |
| Healthy threshold | 5 | 2 to 10 | 2 |
| Unhealthy threshold | 2 | 2 to 10 | 2 |
| Success codes | 200 | 200 to 499 | 200 |

The defaults are from the AWS page linked at the bottom. The last column is my suggestion, not an AWS value.

- With the defaults, a new target needs 5 good checks at 30 s each (about 2.5 minutes) before it gets traffic. With 2 checks at 15 s it is about 30 seconds. That matters when a sandbox session is only about 4 hours.
- Keep the success code at `200`. If you allow `200-499`, a `404` would count as healthy and hide real errors.
- The Spring Boot apps take about 20 to 40 seconds to start (see `deploy/local/up.sh`). Targets will show `initial` or `unhealthy` until then. That is normal.

## 3. product-service and inventory-service

Both services have the same settings. Every path below was called on both services (ports 8081 and 8082) and answered the same way. `user-service` (8083), `cart-service` (8084) and `order-service` (8085) use the same actuator paths; their `/actuator/health` was checked on 2026-11-19 and answered `200` with no token (liveness and readiness were checked on product and inventory only). The login feature does not change any health path: health, liveness and readiness stay public, so the load balancer never needs a token.

Health checks do not flood the logs: a successful call to a health path is written at `DEBUG`, not at `INFO` (the services' own tests check this), so a load balancer polling every 15 seconds does not fill the log files.

| Path | What it tells you | Answer when OK | Use it for |
|---|---|---|---|
| `/actuator/health` | The whole app: database, disk, and the app's own state | `200`, `{"status":"UP","components":{...}}` | **ALB target group**, container health check |
| `/actuator/health/liveness` | The process is alive | `200`, `{"status":"UP"}` | Restart decisions (ECS, Kubernetes) |
| `/actuator/health/readiness` | The app is ready for traffic | `200`, `{"status":"UP"}` | An alternative for the ALB (see section 6) |

What is inside `/actuator/health`: `db`, `diskSpace`, `ping`, `livenessState`, `readinessState`. No part of it calls the other service, so product-service stays healthy when inventory-service is down, and the other way round.

When the app is not healthy, Spring Boot's normal behaviour is status `503` with `"status":"DOWN"`. **Not tested here.**

In the `prod` profile the response hides the details (`show-details: when-authorized` in `application.yml`), so the body is shorter. The ALB only looks at the status code.

### Other endpoints that exist (not for health checks)

| Path | Answer | Notes |
|---|---|---|
| `/actuator/info` | `200`, `{}` | Empty at the moment |
| `/actuator/metrics` | `200` | List of metric names |
| `/actuator/prometheus` | `200`, text | For Stage 5 (metrics and alarms) |
| `/actuator/loggers` | `401` without a token, `403` with a `USER` token, `200` with an `ADMIN` token | Shows log levels and can change them |
| `/actuator/env` | `401` without a token, `403` with a `USER` token, `200` with an `ADMIN` token | Shows configuration (values are masked) |
| `/api-docs` | `200`, JSON | Slow (about 1 second). Not for health |
| `/swagger-ui.html` | `302` | A redirect. Not for health |

Since the login feature, `env` and `loggers` need an `ADMIN` token on every service (checked live on 2026-11-19 on product, inventory and user; on order `loggers` was checked live; for cart it is covered by that service's tests). The other rows still need no login. Keep ports 8081 to 8085 closed to the internet: allow them only from the load balancer's security group, as in your old ALB notes. The listener rules do not send `/actuator*` to the services, so the load balancer does not expose them.

## 4. ui-app (nginx)

| Path | Answer when OK | Where it comes from |
|---|---|---|
| `/healthz` | `200`, body `ok` | `ui-app/nginx.conf` (`location = /healthz`, access log off) |

**Not tested in a running container yet.** The image could not be built on this Mac (see the Stage 0 status in the README), so `/healthz` is read from `nginx.conf` only.

`/healthz` exists only in the container image. The Vite dev server (`npm run dev`) answers `200` for any path, including `/healthz`, with the HTML page. So testing `/healthz` on port 5173 proves nothing.

## 5. Health checks inside the container images

These are the `HEALTHCHECK` lines in the Dockerfiles. They are separate from the load balancer's checks. They are read from the files and **not tested** in a running container.

| Image | Command | Interval | Timeout | Start period | Retries |
|---|---|---|---|---|---|
| product-service | `wget --spider http://localhost:8081/actuator/health` | 30 s | 3 s | 40 s | 3 |
| inventory-service | `wget --spider http://localhost:8082/actuator/health` | 30 s | 3 s | 40 s | 3 |
| user-service | `wget --spider http://localhost:8083/actuator/health` | 30 s | 3 s | 40 s | 3 |
| cart-service | `wget --spider http://localhost:8084/actuator/health` | 30 s | 3 s | 40 s | 3 |
| order-service | `wget --spider http://localhost:8085/actuator/health` | 30 s | 3 s | 40 s | 3 |
| ui-app | `wget --spider http://localhost:8080/healthz` | 30 s | 3 s | 10 s | 3 |

Podman keeps `HEALTHCHECK` lines only for images built in Docker format. `deploy/local/up.sh` does this with `--format docker`. A plain `podman build` does not.

## 6. Which check to use at each stage

- **Stage 1 (EC2 and ALB):** the target group table in section 1.
- **Stage 2 (RDS):** `/actuator/health` includes the `db` check. If the database is unreachable, every instance reports DOWN at the same moment. AWS then "fails open": if a target group has only unhealthy targets, the load balancer sends requests to all of them anyway. Users see errors instead of a clean cut-off. Decide then whether to keep `/actuator/health` or to use `/actuator/health/readiness`. **Not tested:** which checks the readiness group includes. Look at the `groups` and the readiness response first.
- **Stage 4 (ECS):** use the container health check and give the service a health check grace period of at least the start time (about 40 seconds).
- **Stage 5 (CloudWatch):** alarm on the target group's healthy and unhealthy host counts.

## 7. A target is unhealthy: what to check

Look at **EC2 > Target groups > your group > Targets**. The status and reason code are shown there.

| Status or reason | Likely cause | What to do |
|---|---|---|
| `initial`, `Elb.InitialHealthChecking` | The app is still starting, or not enough good checks yet | Wait about a minute |
| `Target.Timeout` | No answer in time: the security group blocks the load balancer, the port is wrong, or the app is down | Instance security group must allow 8080 to 8085 **from the load balancer's security group**. Check the port |
| `Target.ResponseCodeMismatch` | The path answered with something other than the success code | Check the path spelling. Call it by hand (below) |
| `unused`, `Target.NotInUse` | The target group is not used by any listener rule, or the target is in an Availability Zone the load balancer does not use | Add a listener rule that forwards to the group. Enable the zone |
| `Target.FailedHealthChecks` | Checks failed in general | Look at the app log: `docker logs <container>` |

Call the endpoints by hand on the instance:

```bash
curl -i localhost:8081/actuator/health
curl -i localhost:8082/actuator/health
curl -i localhost:8083/actuator/health
curl -i localhost:8084/actuator/health
curl -i localhost:8085/actuator/health
curl -i localhost:8080/healthz
```

You want `HTTP/1.1 200`.

## 8. The local stand-in has no health path

The gateway in `deploy/local/` does not call any health path. nginx skips a backend that refuses connections for 10 seconds (`gateway.conf`). So these paths matter only on AWS.

The gateway and the UI's nginx now write one JSON access log line per request (client address, status, time, trace id). Their configuration passes `nginx -t`; they have not been run inside the project's own images (image builds fail on this Mac).

## Sources

- AWS: [Health checks for Application Load Balancer target groups](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/target-group-health-checks.html) (defaults, ranges, target states, reason codes, fail-open).
- This project: `ui-app/nginx.conf`, the three Dockerfiles, `product-service/src/main/resources/application.yml`, `inventory-service/src/main/resources/application.yml`, `deploy/local/`.
- Your old notes: `docs/aws-alb-routing.md` in `Microservices-demo` (listener rules, security groups).
