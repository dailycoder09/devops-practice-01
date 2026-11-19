# AWS Learning Project: MicroShop

We are learning AWS and DevOps with a real app: a small online shop built from five Java services and a React web app. We build the shop first and run it on this Mac. Then we add AWS services one stage at a time, and each stage fixes a real need of the app.

## What is in this folder

| Folder | What it is | Port |
|---|---|---|
| `product-service/` | Product catalog API: list, search, categories, details (Java 17, Spring Boot 3.2) | 8081 |
| `inventory-service/` | Stock API: stock per product, low-stock list, safe reserve and release for orders | 8082 |
| `user-service/` | Register and log in; creates the login token; admin list of users | 8083 |
| `cart-service/` | One cart per logged-in user | 8084 |
| `order-service/` | Places orders from the cart, reserves stock, keeps the orders; admin list of orders | 8085 |
| `ui-app/` | The shop in the browser: React + TypeScript (Vite), served by nginx in a container | 5173 (dev), 8080 (container) |
| `deploy/` | `local/`: run the shop in containers on your Mac. `aws/`: the EC2 start-up script | 8000 (local gateway) |

The product, inventory and UI apps and the `deploy/` folder started as copies from `Microservices-demo`; the user, cart and order services are new, and the UI was redesigned. Keep `product-service/` and `inventory-service/` side by side: one inventory-service test reads `../product-service/src/main/resources/db/migration`.

Each service has its own `README.md` with more detail. Health check paths and load balancer settings are in [HEALTH-CHECKS.md](HEALTH-CHECKS.md).

## What you need on your Mac

- **Java 17.** The default `java` on this Mac is version 8, so set it in each terminal:
  `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`
  Maven then uses Java 17. But a plain `java` command still finds Java 8 first on this Mac, so start the jars with `"$JAVA_HOME/bin/java"` (as shown below).
  To make Java 17 the default in every new terminal, add these two lines to your `~/.zshrc` yourself (we do not edit your shell files):
  `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` and `export PATH="$JAVA_HOME/bin:$PATH"`
- **Maven 3.9** and **Node 22** (with npm). `jq` is handy for reading the logs.
- **Podman** to build the container images (Docker is not installed here).
- Later: **AWS CLI** (not installed yet) and **Terraform** (installed).

## Run it on your Mac

Open six terminals (one per service, plus the UI). Start order does not matter.

```bash
# 1. product-service (port 8081). IMAGE_BASE_URL=/photos points the products at the real photos in ui-app/public/photos.
cd product-service
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -q -DskipTests package
IMAGE_BASE_URL=/photos "$JAVA_HOME/bin/java" -jar target/product-service-1.0.0.jar

# 2. inventory-service (port 8082)
cd inventory-service
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -q -DskipTests package
"$JAVA_HOME/bin/java" -jar target/inventory-service-1.0.0.jar

# 3. user-service (port 8083)   4. cart-service (8084)   5. order-service (8085): the same two lines each
cd user-service      # then cart-service, then order-service
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -q -DskipTests package
"$JAVA_HOME/bin/java" -jar target/user-service-1.0.0.jar     # cart-service-1.0.0.jar, order-service-1.0.0.jar

# 6. ui-app (port 5173)
cd ui-app
npm ci
npm run dev
```

Open **http://localhost:5173**. The dev server passes `/api/products` to 8081, `/api/inventory` to 8082, `/api/users` to 8083, `/api/cart` to 8084 and `/api/orders` to 8085. On AWS, the load balancer does that job.

**About the photos:** `IMAGE_BASE_URL` only works when the product database is first created (a database migration applies it once). To switch an existing setup, stop `product-service`, delete `product-service/data/productdb*` and start it again with the variable. Some of the sample photos do not match their product (they came with the project).

All services keep their data in an H2 file database under their own `data/` folder. The tables are created at start-up by Flyway. Logs go to each service's `logs/` folder (see "Logging and observability").

Quick checks:

```bash
curl -s localhost:8081/actuator/health          # also 8082, 8083, 8084, 8085
curl -s 'localhost:8081/api/products?size=1'
curl -s 'localhost:8082/api/inventory?size=1'

# log in as the dev admin, then ask "who am I" with the token
TOKEN=$(curl -s -X POST localhost:8083/api/users/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"admin12345"}' | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')
curl -s -H "Authorization: Bearer $TOKEN" localhost:8083/api/users/me
```

## Try it by hand

1. **Visitor:** browse the home page, search ("iphone" shows suggestions as you type), open a category and a product. The product page shows "Log in to order".
2. **Shopper:** Register, log in, put a product in the cart (or "Buy now"), open the cart, click **Place order**. You land on **My orders** with a Confirmed order, and the product's stock has dropped.
3. **A sold-out product:** open the **iPad Air** (it has 0 stock). It shows "Out of stock" and "Add to cart" is switched off. (If a sold-out item gets into a cart anyway, for example through the API, **Place order** is refused with "Not enough stock" and the cart stays.)
4. **Admin:** log in as `admin@example.com` / `admin12345`. The **Admin** link in the header opens the admin area: Overview (numbers and service status), Inventory (add or remove stock, "Low stock only"), Orders (all orders, status filter), Users (search).
5. **A normal user** who opens `/admin` sees "This area is for administrators."

## Run the tests

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
for s in product-service inventory-service user-service cart-service order-service; do (cd $s && mvn -q test); done
(cd ui-app && npm test)
```

The Java suites take several minutes each on this Mac (they start many Spring contexts). The UI suite starts many workers; if a test times out under heavy load, run it again or run that file alone.

## Build the container images

```bash
for s in product-service inventory-service user-service cart-service order-service ui-app; do podman build -t $s:1.0 $s; done
```

## Run the whole shop in containers (like AWS)

`deploy/local/` starts a local copy of the AWS layout: two simulated VMs (a and b), each with product-service, inventory-service and ui-app, plus one user-service, one cart-service and one order-service container, behind a gateway (nginx) that applies the same path rules as the load balancer. These pieces (the Dockerfiles for user, cart and order, `up.sh`, the gateway rules) are written but **not tested yet**, because image builds fail on this Mac (see Status). Their syntax was checked (`bash -n`, and `nginx -t` on both nginx configs).

```bash
deploy/local/up.sh      # build seven images, start everything, wait until ready
deploy/local/down.sh    # stop and remove the containers and the network
```

Then open http://localhost:8000. It needs the Podman machine running with more than 6 GB of memory (seven JVMs; see `deploy/local/README.md`). In this setup the order service talks to `inventory-a` directly, so a reserve and its release reach the same stock database (details in `deploy/local/README.md`).

`deploy/aws/user-data.sh` is the start-up script for an EC2 instance. It installs Docker, clones this project from GitHub (`dailycoder09/devops-practice-01`, branch `main`), builds the three original apps and starts them. Two things must be true before you launch an instance: the repo is **public** (the script clones without a login), and you have **pushed** your commits. It does not start the user, cart or order service yet; that is added with the AWS stages.

## Login, roles and the rules

How it works: `POST /api/users/register` creates a user (role `USER`). `POST /api/users/login` returns a signed token (JWT, valid 8 hours). The UI keeps the token in `localStorage` and sends it as `Authorization: Bearer <token>`. Every service checks the token's signature with the same secret; none of them calls `user-service` for each request. There is no cookie and no server-side session, so any VM can check any token.

There are three roles in the token: `USER`, `ADMIN`, and `SERVICE` (an internal token the order service makes to call inventory; it lasts 2 minutes).

| Request | No token | `USER` | `ADMIN` | `SERVICE` |
|---|---|---|---|---|
| `GET` product or inventory data (browse) | 200 | 200 | 200 | 200 |
| `POST`, `PUT`, `PATCH`, `DELETE` on product or inventory | 401 | 403 | allowed | 403 |
| Inventory `reserve` and `release` (used by orders) | 401 | 403 | 403 | allowed |
| Cart and order calls (`/api/cart`, `/api/orders`) | 401 | allowed (own data) | allowed (own data) | 403 |
| Admin lists: `GET /api/users`, `/api/orders/all`, `/api/orders/stats` | 401 | 403 | allowed | 403 |
| `/actuator/health`, liveness, readiness | 200 | 200 | 200 | 200 |
| `/actuator/env`, `/actuator/loggers` | 401 | 403 | 200 | 403 |

Dev defaults (only in the `dev` profile, the default for local runs): admin account `admin@example.com` with password `admin12345`, and a public placeholder secret. In any other profile (for example `prod` on AWS) each service **refuses to start** unless `JWT_SECRET` is a random value of at least 32 bytes (for example `openssl rand -base64 48`), and `user-service` also needs `ADMIN_EMAIL` and `ADMIN_PASSWORD`.

## Cart and orders

- **Cart:** `PUT /api/cart/items/{sku}` (quantity 1 to 99), `DELETE /api/cart/items/{sku}`, `DELETE /api/cart`, `GET /api/cart` (with name, price and totals looked up in product-service).
- **Place an order:** `POST /api/orders` (no body). The order service reads your cart with your own token, takes the prices from product-service (never from the browser), saves the order as `PENDING`, reserves stock in inventory with an internal `SERVICE` token, marks the order `CONFIRMED` and empties the cart.
- **Stock is safe:** `reserve` is one atomic database update, so stock never goes below zero, even when two people buy the last unit at the same moment (tested: exactly one wins). Each reservation is stored with its order id, so a repeated or timed-out `reserve` or `release` cannot change stock twice.
- **When something fails:** not enough stock gives `409` and the order is saved as `REJECTED` with the reason; the stock service not answering gives `503` and `REJECTED`; in both cases every line of the order is released and your cart stays as it was. If the cart cannot be emptied after a confirmed order, you still get `201` with `cartCleared: false`; the order stays confirmed.
- **Order lists:** `GET /api/orders` is your own orders (newest first); `GET /api/orders/{id}` is yours (or any, for an admin), otherwise `404`.

## Admin area

`/admin` in the UI is only for `ADMIN` (a visitor is sent to the login page, a normal user sees "This area is for administrators."):

- **Overview:** product, stock, low-stock, order and user numbers; a **Service status** panel that calls the five services and shows answering or not, with the time it took; the low-stock top 5; the "which server answered" widgets.
- **Inventory:** the stock table with Add and Remove, the SKU history, and a "Low stock only" switch.
- **Orders:** all customers' orders with a status filter and expandable lines and reject reasons.
- **Users:** a searchable list of users (never shows passwords).

Backed by `GET /api/users`, `GET /api/orders/all`, `GET /api/orders/stats` and `GET /api/inventory/low-stock`.

## Logging and observability

Every Java service writes **structured JSON logs**, so one request can be followed across all services.

- **Where:** a JSON file `logs/<service>.json.log` in each service's folder (rolls daily and at 10 MB, kept 14 days, 500 MB at most). On stdout it is readable text in the `dev` profile and JSON in any other profile (that is what a container or CloudWatch reads). nginx (the UI and the gateway) also writes one JSON line per request.
- **One trace id across services:** every request gets a `traceId` (Micrometer Tracing). It is passed on to every service call, written on every log line, and returned to the caller in the response header `X-Trace-Id`. The UI shows it as "Reference: ..." on errors.
- **Who did it:** every line carries `userId`, `userEmail`, `userRole` and `clientIp` (`userId` is `anonymous` for a visitor; the internal token shows `order-service` with role `SERVICE`). The client IP is the first address in `X-Forwarded-For`, otherwise the address of the connection. That header can be faked, so the IP is for tracing, not for security.
- **One access line per request:** `HTTP POST /api/orders -> 201 in 128 ms` with `httpMethod`, `httpPath` (no query string), `httpStatus`, `durationMs`. Successful health checks are written at DEBUG so load balancer polling does not fill the log.
- **Audit events:** each has an `event` field, for example `user_registered`, `login_succeeded`, `login_failed` (with the reason), `product_created`, `stock_adjusted`, `stock_reserved`, `stock_reserve_refused`, `stock_released`, `cart_item_set`, `cart_cleared`, `order_checkout_started`, `order_confirmed`, `order_rejected`, `release_failed`. They say who did what.
- **Levels:** ERROR means a person must act (an unexpected failure, data at risk). WARN is a handled problem or a security signal (failed login, rejected token, refused stock, slow request, a service that timed out). INFO is start-up, access lines and audit events. DEBUG is developer detail (successful service calls, SQL in `dev`).
- **Never logged:** passwords, password hashes, tokens, the `Authorization` header, the JWT secret, request or response bodies, query strings, SQL parameter values. Values that callers send (email, SKU, paths) are cleaned so they cannot forge extra lines.
- **Privacy:** IP address and email are personal data. They are logged on purpose. `app.logging.include-email=false` removes the email from every line. Files are kept 14 days.

Follow one request (replace `<id>` with the `X-Trace-Id` of a response, for example from `curl -i`):

```bash
grep -h '"traceId":"<id>"' */logs/*.json.log | jq -r '[.timestamp,.service,.level,.userId,.userEmail,.clientIp,.message] | @tsv'
```

Change a log level while a service runs (needs an admin token; set it back with `"configuredLevel":null`):

```bash
curl -X POST localhost:8085/actuator/loggers/com.microservices.order -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}'
```

Example line (shortened):

```json
{"timestamp":"2026-11-19T09:44:05.549Z","level":"INFO","service":"order-service","message":"HTTP POST /api/orders -> 201 in 128 ms","traceId":"6afec4e5bc16721eb1a81b64b94f386f","userId":"12","userEmail":"shopper@example.com","userRole":"USER","clientIp":"198.51.100.23","event":"http_request","httpMethod":"POST","httpPath":"/api/orders","httpStatus":201,"durationMs":128}
```

## Settings you can change with environment variables

| Variable | Used by | Meaning |
|---|---|---|
| `INVENTORY_SERVICE_URL` | product-service, order-service | Where to find inventory-service (default `http://localhost:8082`) |
| `PRODUCT_SERVICE_URL` | inventory-service, cart-service, order-service | Where to find product-service (default `http://localhost:8081`) |
| `CART_SERVICE_URL` | order-service | Where to find cart-service (default `http://localhost:8084`) |
| `IMAGE_BASE_URL` | product-service | Where the product photos are served from (`/photos` locally, or for example an S3 address). Applied once, when the database is created |
| `CLOUD_METADATA_ENABLED` | product, inventory | Read EC2 instance details for the `server-info` endpoint (default on; it fails quietly off AWS) |
| `DB_FILE_PATH`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | all five services | Database location and login |
| `JWT_SECRET` | all five services | The secret that signs and checks login tokens (at least 32 bytes). Placeholder in `dev`; required elsewhere |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | user-service | The admin account created at first start. Placeholders in `dev`; required elsewhere |
| `LOG_PATH` | all five services | Folder for the JSON log file (default `logs`) |

## The plan: AWS stages

Each stage ends with a working app that can be rebuilt from scratch in one sandbox session. The shop itself (everything above) is built and runs locally; the stages put it on AWS.

| Stage | What we do | AWS services |
|---|---|---|
| 0 | Copy the apps, check they build, test and run, read what the sandbox allows | none yet (done, except reading the sandbox) |
| 1 | The baseline as Terraform: network, two servers, load balancer with path rules | VPC, EC2, ALB, Security Groups, IAM |
| 2 | One shared database instead of an H2 file on each server (all five services) | RDS (PostgreSQL), Parameter Store or Secrets Manager |
| 3 | Photos in a bucket, and a CDN in front | S3, CloudFront |
| 4 | Containers instead of building on the server at boot | ECR, ECS |
| 5 | Automatic build and test, logs and alarms | GitHub Actions, CloudWatch, SNS |
| 6 | Scale and harden | Auto Scaling, NAT or VPC endpoints, WAF, ACM, Route 53 |
| Extras | Only if the sandbox allows it | Cognito, X-Ray, API Gateway, EKS |

Stages 1 to 6 are outlines. Before each stage we check that its services are allowed in the sandbox, then plan it in detail.

What the new services add to the AWS stages:
- **Target groups and rules:** `user-tg` (8083, `/api/users*`), `cart-tg` (8084, `/api/cart*`) and `order-tg` (8085, `/api/orders*`), health check `/actuator/health`; CloudFront must pass the `Authorization` header through.
- **Database:** PostgreSQL migrations for user, cart, order (and the `stock_reservations` table of inventory). With one database for all VMs, logins, carts and orders work on every VM. Today each service has its own H2 file.
- **Secrets:** `JWT_SECRET`, `ADMIN_EMAIL`, `ADMIN_PASSWORD` in Parameter Store or Secrets Manager.
- **Logs and traces:** the services already write JSON to stdout, which CloudWatch Logs can read. Because of the trace id, X-Ray (through OpenTelemetry) can be added without code changes to the services.
- **Stuck orders:** a scheduled job (EventBridge Scheduler and Lambda) can release the stock of orders left `PENDING` (the reservations are stored with their order id).

## Rules for this project

- The old `Microservices-demo` folder is never changed.
- The owner makes all git commits and pushes. This folder is a git repo (`devops-practice-01`).
- Java 17 and Spring Boot 3.2 stay as they are. A downgrade to Java 8 (Spring Boot 2.7) was looked at and dropped on 2026-11-19.
- Not built on purpose: payments, delivery addresses, wishlist, ratings and reviews, cancelling an order, password reset. The app has no data for them.

## Known limits

- A login token cannot be cancelled before it expires (8 hours). Logging out only removes it from the browser. A role change applies at the next login.
- The shared secret lets any service make any token. A key pair (only `user-service` signs) is the later fix.
- No transaction across services: if `order-service` stops in the middle of a checkout, an order stays `PENDING` and stock may stay reserved (a clean-up job can release it by order id).
- Two checkouts of the same cart at the same instant could create two orders (the UI disables the button).
- Every service has its own H2 file, so with two VMs carts, orders, users and stock are not shared until RDS. Locally there is one of each, except in `deploy/local`, where stock differs between the two simulated VMs (see `deploy/local/README.md`).
- A request with a bad or expired token gets `401` even on a public page. The UI does not send tokens it knows are expired, and a `401` logs the person out.
- The inventory list pages (`GET`) are public on the API; the admin-only inventory page is a UI rule.
- `adjust` and `PUT` on inventory still read and then write without a lock; the database check still stops negative stock.

## Sandbox notes (not yet confirmed)

We think the AWS access is a Pluralsight AWS sandbox. From Pluralsight's help pages: only `us-east-1` and `us-west-2`, sessions of about 4 hours, everything is deleted when a session ends, and some services or actions are blocked. Because of this, the environment must be rebuildable with one command (Terraform, local state). Do not try to work around blocked actions.

## Status (checked on 2026-11-19)

Built and verified on this Mac:

- [x] Shop features: login, cart, orders with safe stock reservation, admin area, Flipkart-style storefront, logging and tracing in every service
- [x] Design polish pass (from a design review of 22 screenshots): even photo frames, aligned prices, clear "Only N left" and "Out of stock" labels instead of raw stock numbers, 44 px tap targets and 16 px inputs on phones, clearer input borders, a calmer product page, a proper 404 page, deeper header colour with one yellow accent, cleaner home tiles and hero, a card layout for the admin inventory on phones
- [x] Tests, all run again by the lead (no failures): product-service 447, inventory-service 508, user-service 256, cart-service 332, order-service 469, UI 751. Once, one UI test timed out under heavy machine load; it passed on its own (3 of 3) and in a full re-run
- [x] End-to-end against the real running services (curl): login 70 of 70, shop (cart, orders, stock, race for the last unit, refusals) 82 of 82
- [x] One real order followed across the order, cart, product and inventory services by its trace id (users, roles and IP right on every line); every line of all five JSON logs parses; zero secrets found in any log
- [x] Real Chrome (driven by a script) against the live services, re-run after the design polish: 43 of 43 checks (visitor, search suggestions, real photos, register, order, refused order, admin pages, normal user blocked, no sideways scroll at phone width)
- [x] Health check reference: [HEALTH-CHECKS.md](HEALTH-CHECKS.md)
- [x] `deploy/aws/user-data.sh` points at this project's repo (`devops-practice-01`)

Open:

- [ ] **Commit and push to GitHub (you).** The repo exists and is public, but empty. Nothing was committed by the assistant.
- [ ] **Podman image builds fail here.** Maven and npm downloads time out inside the build container: it cannot reliably reach the company proxy (the Podman VM itself can, the containers inside it only sometimes). The apps are fine; this is a network problem. Because of it, the Dockerfiles for user, cart and order, `deploy/local/up.sh` and the gateway rules are **untested** (only `bash -n` and `nginx -t` were run), and `/healthz` and the JSON access logs of nginx were not seen running in the project's own images.
- [ ] Nothing was tried in a real phone, Safari or Firefox (Chrome only), and no screen reader was used.
- [ ] AWS CLI installed (you)
- [ ] Sandbox started and allowed services written down (you)
- [ ] The AWS stages (1 to 6) have not been started.
# devops-practice-01
# devops-practice-01
