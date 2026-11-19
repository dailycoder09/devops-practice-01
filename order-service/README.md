# Order Service

A standalone Spring Boot 3.2.1 service for orders. A logged-in person places an order from their cart; the service saves the order with the prices of that moment, reserves the stock in `inventory-service`, confirms the order and empties the cart. A person can read their own orders.

## Overview

`order-service` owns its own H2 file database (`./data/orderdb`) with two tables, `orders` and `order_items`. It calls three other services:

```
Browser / UI ──POST /api/orders (Bearer <user JWT>)──▶ order-service
                                                          │
   1. GET    /api/cart            (the caller's own token) ─▶ cart-service       what is in the cart?
   2. GET    /api/products/sku/x  (public)                 ─▶ product-service    name and price now
   3. save the order as PENDING                              (own H2 database)
   4. POST   .../reserve?quantity=n&orderId=id (SERVICE token) ─▶ inventory-service   take the stock
   5. all reserved: CONFIRMED, then DELETE /api/cart (best effort) ─▶ cart-service
      stock short or a service failed: POST .../release for every line, REJECTED
```

There is no transaction across services. Every step is its own small database transaction, and the order is saved (and committed) before any stock is touched, so the stock service always has an order id to key its reservation on.

## Features

- `POST /api/orders` (no body), `GET /api/orders`, `GET /api/orders/{id}`
- The cart is read with the caller's own token, so `cart-service` decides whose cart it is. The browser never sends items, prices or a total.
- The price and name of every line come from `product-service` at checkout, never from the cart. An order keeps them, so a later price change does not change an old order.
- Stock is reserved with a short-lived internal `SERVICE` token and the order id. The calls are repeat-safe on the inventory side.
- If stock is short or a service fails, every line of the order is released and the order is `REJECTED` with a plain reason.
- Roles: `USER` and `ADMIN` can use it. A `SERVICE` token gets `403`.
- Stateless security (no session, no cookies, CSRF off) with Spring Security's OAuth2 resource server
- Same problem-JSON style for errors as the other services
- Flyway migration, Swagger UI with an Authorize button, Actuator and Prometheus metrics
- 80% line-coverage gate (JaCoCo) on the controller, service, client and repository packages

## API Endpoints

| Method | Endpoint | Needs | Description |
|--------|----------|-------|-------------|
| POST | `/api/orders` | `USER` or `ADMIN` token | Orders everything in the caller's cart. `201` with the order and `cartCleared`. See the table of outcomes below |
| GET | `/api/orders?page=0&size=20` | `USER` or `ADMIN` token | The caller's own orders (also for an admin), newest first, as a Spring `Page` (`content`, `totalElements`, `totalPages`, `number`, `size`, ...). `page` 0 to 100000, `size` 1 to 100, otherwise `400` |
| GET | `/api/orders/{id}` | `USER` or `ADMIN` token | `200` if the order is the caller's or the caller is `ADMIN`. Otherwise `404` (the same answer as for an unknown id, so existence does not leak). `400` if the id is not a number |
| GET | `/api/orders/all?page=0&size=20&status=` | `ADMIN` token only | The orders of **all** users, newest first (creation time, then id), as a Spring `Page`. `status` is optional: `PENDING`, `CONFIRMED` or `REJECTED` (an empty `status=` means no filter, anything else is `400`). `page` and `size` have the same limits as the own list. `401` without a token, `403` for `USER` and `SERVICE` |
| GET | `/api/orders/stats` | `ADMIN` token only | `{ "totalOrders", "pending", "confirmed", "rejected", "revenue" }`: the counts per status over all users, and `revenue` = the sum of the totals of the `CONFIRMED` orders (two decimals). One database query, so the numbers belong together |

`/api/orders/all` and `/api/orders/stats` are literal paths: Spring MVC picks them before the template `/api/orders/{id}`, and the security rule for them (`ADMIN`) comes before the generic `/api/orders/**` rule, so a `USER` token gets `403` there and never a `400` ("all" is not a number). `GET /api/orders` is still only the caller's own orders, also for an admin.

### The order JSON

```json
{
  "id": 12,
  "userId": 7,
  "userEmail": "ann@example.com",
  "status": "CONFIRMED",
  "total": 261.50,
  "createdAt": "2026-11-19T10:15:30",
  "items": [
    { "sku": "APPLE-IP15P-128", "name": "iPhone 15 Pro", "unitPrice": 100.00, "quantity": 2, "lineTotal": 200.00 }
  ],
  "cartCleared": true
}
```

- `userId` (a number, the `sub` of the token at checkout) and `userEmail` (the `email` claim at checkout) say who ordered. They are in every answer that has an order. `userEmail` is left out when the token had no email, like every other null property.
- `status` is `PENDING`, `CONFIRMED` or `REJECTED`. `rejectReason` (a sentence) is there only for a `REJECTED` order.
- `cartCleared` is only in the answer of `POST /api/orders`. Properties that are null are left out.
- Money has two decimals.

### What `POST /api/orders` answers

| Situation | Answer | Order saved? | Stock | Cart |
|---|---|---|---|---|
| Everything works | `201` order `CONFIRMED`, `cartCleared` | yes, `CONFIRMED` | reserved | emptied (`cartCleared: true`) |
| Everything reserved, but emptying the cart failed | `201` order `CONFIRMED`, `cartCleared: false` | yes, `CONFIRMED` (a failed clean-up never changes it) | reserved | unchanged |
| Cart is empty | `400` "Your cart is empty." | no | untouched | - |
| A product is gone from `product-service` | `422` "Product SKU is no longer available." (with the real SKU) | no | untouched | unchanged |
| `cart-service` or `product-service` does not answer, or answers nonsense | `503` ("The cart service did not answer. Please try again." / "The product service did not answer. Please try again.") | no | untouched | unchanged |
| Stock refused for a line (`409` or `404` from inventory) | `409` problem JSON with `detail` (for example "Not enough stock for APPLE-IP15P-128: wanted 3, available 1") and `orderId` | yes, `REJECTED` with the reason | every line released | unchanged |
| Inventory unreachable, too slow or failing (`5xx`, `401`, `403`, `400`, ...) | `503` problem JSON "The stock service did not answer. Please try again." and `orderId` | yes, `REJECTED` with that reason | every line released (including the one that failed) | unchanged |
| A release itself fails | logged and ignored, the answer above stays | `REJECTED` | that line may stay reserved (see known limits) | unchanged |
| An unexpected error after the order was saved | `503` "Something went wrong while placing your order. Please try again." and `orderId` | yes, `REJECTED` | every line released | unchanged |

Why every line is released, even the ones never reserved: a reserve call that timed out may still have been applied by `inventory-service`. A release does nothing when there is no reservation for that order id and SKU, so it is always safe.

If the same SKU shows up twice in the answer of the cart (it should not), the quantities are added, because inventory keeps one reservation per order id and SKU.

### Example Requests

```bash
# Log in with user-service and keep the token
TOKEN=$(curl -s -X POST http://localhost:8083/api/users/login -H "Content-Type: application/json" \
  -d '{"email":"ann@example.com","password":"correct-horse-battery"}' | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')

# Place an order from the cart
curl -X POST http://localhost:8085/api/orders -H "Authorization: Bearer $TOKEN"

# My orders, and one order
curl "http://localhost:8085/api/orders?page=0&size=20" -H "Authorization: Bearer $TOKEN"
curl http://localhost:8085/api/orders/12 -H "Authorization: Bearer $TOKEN"
```

## The SERVICE token

`inventory-service` only lets `reserve` and `release` through for a token with the role `SERVICE`. `order-service` makes this token itself:

| Claim | Value |
|---|---|
| `sub` | `order-service` |
| `role` | `SERVICE` |
| `iat` / `exp` | issued at / expires, **2 minutes** apart |

It is HS256, signed with the same shared secret as every other token, and it is made fresh for every call to inventory. It is never returned by any endpoint, and `user-service` never issues it. It is valid for every service's decoder, but it is refused (`403`) wherever `USER`, `ADMIN` or an admin is required: that includes every endpoint of this service.

**Known weakness:** with one shared secret, any service (and anyone who learns the secret) can make any token, including an `ADMIN` one. The later fix is a key pair, where only `user-service` can sign user tokens. Until then, protect `JWT_SECRET` like a password.

## The secret (important)

The key that checks (and signs) tokens is a random value of **at least 32 bytes** in the environment variable `JWT_SECRET` (property `app.security.jwt.secret`). `user-service`, `product-service`, `inventory-service`, `cart-service` and `order-service` must all use the same value.

`dev` is the default profile, so a local run needs no set-up: it uses the public placeholder `dev-only-placeholder-secret-do-not-use-in-aws-0123456789`. The placeholder is in git, so the **startup check** refuses to start the application when:

- the profile is not `dev` and `JWT_SECRET` is empty or still the placeholder,
- (any profile) `JWT_SECRET` is shorter than 32 bytes.

The message says which variable to set and never prints the secret. Create a secret with `openssl rand -base64 48`. The `env` actuator endpoint shows property values as `******`, even to an admin. Tokens and the secret are never logged.

## Configuration

| Setting | Environment variable | Default |
|---|---|---|
| `order.product-service.url` | `PRODUCT_SERVICE_URL` | `http://localhost:8081` |
| `order.inventory-service.url` | `INVENTORY_SERVICE_URL` | `http://localhost:8082` |
| `order.cart-service.url` | `CART_SERVICE_URL` | `http://localhost:8084` |
| `order.clients.connect-timeout-ms` / `read-timeout-ms` | - | `2000` / `5000` |
| `app.security.jwt.secret` | `JWT_SECRET` | the placeholder, in `dev` only |
| database file | `DB_FILE_PATH` | `./data/orderdb` |
| `app.logging.include-email` | - | `true` (`false` keeps the email out of every log line) |
| `app.logging.slow-request-ms` | - | `2000` (a slower request is a WARN with `slow=true`) |
| `management.tracing.sampling.probability` | - | `1.0` |
| log folder | `LOG_PATH` | `logs` (the file is `logs/order-service.json.log`) |

On AWS the three service URLs point at the load balancer, like the other services do.

## Access rules

| URL | Who |
|---|---|
| `/api/orders/all`, `/api/orders/stats` (any method) | `ADMIN` token only (`401` without a token or with a bad one, `403` for `USER`, `SERVICE` or any other role) |
| `/api/orders`, `/api/orders/**` | `USER` or `ADMIN` token (`401` without a token or with a bad one, `403` for a `SERVICE` token or any other role) |
| a valid `USER` or `ADMIN` token whose `sub` is not a number | `401` (never `500`) |
| `/actuator/health/**` (including liveness and readiness) | everyone (the load balancer needs this) |
| `/actuator/env/**`, `/actuator/loggers/**` | `ADMIN` only (`403` for `USER` and `SERVICE`, `401` without a token) |
| Swagger (`/swagger-ui.html`, `/swagger-ui/**`, `/api-docs/**`) | everyone |
| other `/actuator/**` endpoints | everyone (as in the other services) |
| everything else | any valid token |

`401` and `403` answers are problem JSON (`application/problem+json`): `{"type":"about:blank","title":"Unauthorized","status":401,"detail":"..."}`. Other errors have the same shape (`title`, `status`, `detail`), and the `409` and `503` answers of a rejected order also carry `orderId`.

## Technology Stack

| Category | Technology | Version |
|----------|-----------|---------|
| Framework | Spring Boot | 3.2.1 |
| Language | Java | 17 |
| Security | Spring Security 6.2 (OAuth2 resource server, Nimbus JOSE) | managed by Boot |
| HTTP client | Spring `RestClient` with time-outs | managed by Boot |
| Database | H2 (file-based) | Runtime-managed |
| Migration | Flyway | managed by Boot |
| Logging | Logback with `logstash-logback-encoder` (JSON lines) | 7.4 |
| Tracing | Micrometer Tracing, Brave bridge (ids only, nothing is exported) | managed by Boot |
| Documentation | Swagger/OpenAPI (springdoc) | 2.2.0 |
| Testing | JUnit 5, Mockito, MockMvc, fake HTTP servers (JDK `HttpServer`) | managed by Boot |
| Code Coverage | JaCoCo | 0.8.11 |

## Getting Started

JDK 17 and Maven 3.9+ are needed. If your machine defaults to another Java, use the script:

```bash
./build.sh          # mvn clean verify  - compiles, runs all tests + the JaCoCo gate
./build.sh package  # mvn clean package -DskipTests - fast jar build (what the Dockerfile does)
./build.sh run      # mvn spring-boot:run - starts the app on :8085
```

Or with Maven directly:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -DskipTests package   # target/order-service-1.0.0.jar
java -jar target/order-service-1.0.0.jar                              # port 8085, dev profile
```

A real checkout needs `cart-service` (8084), `product-service` (8081) and `inventory-service` (8082) to be running, and the same `JWT_SECRET` in all of them.

Run it outside `dev`:

```bash
JWT_SECRET="$(openssl rand -base64 48)" java -jar target/order-service-1.0.0.jar --spring.profiles.active=prod
```

Useful URLs: `http://localhost:8085/actuator/health` and `http://localhost:8085/swagger-ui.html` (click Authorize and paste a token from `user-service`).

## Docker

```bash
docker build -t order-service:local .
docker run -p 8085:8085 -v "$(pwd)/data:/data" \
  -e PRODUCT_SERVICE_URL=http://host.docker.internal:8081 \
  -e INVENTORY_SERVICE_URL=http://host.docker.internal:8082 \
  -e CART_SERVICE_URL=http://host.docker.internal:8084 \
  order-service:local
```

The `Dockerfile` has the same shape as the other services (port 8085, health check on `/actuator/health`, proxy handling, non-root user).

## Logging and observability

**Where the logs go.** Outside the `dev` profile every log event is one JSON object on one line on stdout (containers and CloudWatch read stdout). In **every** profile the same lines also go to a rolling file `logs/order-service.json.log` (a new file every day or at 10 MB, 14 days kept, 500 MB at most; the folder comes from `LOG_PATH`). In `dev` the console is readable text: `14:03:22.117 INFO  [4bf92f3577b34da6a3ce929d0e0e4736] [user=7 ip=203.0.113.7] c.m.o.l.RequestLoggingFilter - HTTP GET /api/orders -> 200 in 12 ms`. Both outputs are async (a queue of 1024 events that never discards one).

**Fields of every line:** `timestamp` (ISO-8601, UTC), `level`, `service`, `profile`, `logger` (short), `thread`, `message`, `stack_trace` (a stack trace is one field, never many lines), and from the request: `traceId`, `spanId`, `userId`, `userEmail`, `userRole`, `clientIp`. Every structured argument of a log call is a top-level field too (`event`, `httpStatus`, `orderId`, ...). A message with quotes or line breaks is escaped, so every line is valid JSON.

```json
{"timestamp":"2026-11-19T08:33:22.117Z","level":"INFO","service":"order-service","profile":"prod","logger":"c.m.o.l.RequestLoggingFilter","thread":"http-nio-8085-exec-3","message":"HTTP POST /api/orders -> 201 in 183 ms","traceId":"4bf92f3577b34da6a3ce929d0e0e4736","spanId":"00f067aa0ba902b7","clientIp":"203.0.113.7","userId":"7","userEmail":"ann@example.com","userRole":"USER","event":"http_request","httpMethod":"POST","httpPath":"/api/orders","httpStatus":201,"durationMs":183}
```

**Follow one request.** The answer of every request has the header `X-Trace-Id`. Micrometer Tracing sends a W3C `traceparent` header on every call to `cart-service`, `product-service` and `inventory-service` (the `RestClient`s are built from the Spring `RestClient.Builder`, which is instrumented), so the other services log the same `traceId`:

```bash
jq -c 'select(.traceId=="4bf92f3577b34da6a3ce929d0e0e4736") | {timestamp,service,event,message,userId}' logs/*.json.log
```

**One access line per request** (method, path **without** query string, status, duration), written after the whole security chain, so it also covers `401`, `403` and requests that throw, and it carries the user. Level: `500` and above ERROR, `401` and `403` WARN, slower than `app.logging.slow-request-ms` WARN with `slow=true`, health checks (`/actuator/health**`, `/healthz`) and metric scrapes DEBUG, everything else INFO. The client address is the first usable address of `X-Forwarded-For` (at most 45 characters, only hex digits, `:` and `.`), otherwise the socket address; the header can be faked, so the address is for observability only. Callers without a valid token are `userId=anonymous`; the internal token shows as `userId=order-service`, `userRole=SERVICE`.

**Events** (`event` field). The business events go through the logger `audit`:

| `event` | Level | Fields |
|---|---|---|
| `order_checkout_started` | INFO | `itemCount` (number of lines, after equal SKUs are merged) |
| `order_confirmed` | INFO | `orderId`, `total`, `itemCount`, `cartCleared` |
| `order_rejected` | WARN | `orderId`, `reason` (cleaned, 200 characters at most) |
| `release_failed` | ERROR | `orderId`, `sku` (one per line whose release failed; the order stays `REJECTED`) |
| `cart_clear_failed` | WARN | `orderId` (the order stays `CONFIRMED`) |
| `service_token_issued` | DEBUG | `lifetimeSeconds` (never the token; shown only when the `audit` logger is at DEBUG) |

Other events: `http_request` (access line), `downstream_call` (every call to another service: `downstreamService`, `httpMethod`, `httpPath`, `httpStatus` or `exception`, `durationMs`; DEBUG for a success, WARN for 4xx, 5xx, time-out or I/O error), `auth_rejected` (WARN, `reason` = `missing_token` or `invalid_token`, never the token), `access_denied` (WARN, method and path) and `service_started` (one INFO line at start: version, profiles, port, `jwtSecretConfigured`, database, the URLs of the other services without credentials; never a secret).

**Levels.** ERROR needs a person (an unexpected failure, a release that failed). WARN is a handled problem or a security signal (a refused order, a rejected token, a slow request, a failed call to another service). INFO is lifecycle, access and audit. DEBUG is developer detail (successful downstream calls, SQL in `dev`). Defaults: root INFO, `com.microservices` INFO (DEBUG in `dev`). Each failure is logged once; only an unexpected error (5xx) has a stack trace.

**Change a level while it runs** (needs an `ADMIN` token):

```bash
curl -X POST http://localhost:8085/actuator/loggers/audit -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" -d '{"configuredLevel":"DEBUG"}'
```

**Never logged:** passwords, password hashes, tokens (the caller's and the internal `SERVICE` token), the `Authorization` header, the JWT secret, request or response bodies, query strings, SQL parameter values. A value that comes from a caller (email, SKU, path, a reason sent by another service) is cleaned before it is logged: every control character, including CR and LF, becomes `_`, and at most 200 characters are kept, so a value can never start a second log line.

**Privacy.** The client IP and the email address are personal data. They are logged because the owner asked for it. Set `app.logging.include-email=false` to keep the email out of every line (the order itself still stores it). Log files are kept for 14 days.

**Logging never breaks a request.** Every filter, the downstream interceptor and the audit calls catch their own failures: a good response stays a good response and a checkout keeps its outcome.

## Testing

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test
```

The suite uses an in-memory H2 database (Flyway runs the real migration), the real security filter chain, and **fake HTTP servers** (the JDK `HttpServer`, on random ports) for cart, product and inventory. The fake inventory keeps stock and stored reservations like the real one, can answer late, cut the connection or be stopped, and accepts only a correctly signed `SERVICE` token. The tests check, among other things:

- the happy path: the price snapshot (the product price, not the cart's), the stock calls with the `SERVICE` token and the order id, the order saved as `PENDING` before any stock call, `CONFIRMED`, `201`, the cart cleared with the caller's own token
- an empty cart (`400`), a second checkout right after a successful one (`400`), a product removed (`422`)
- stock refused for the second of several lines, inventory down, slow, cutting the connection or answering `5xx` or `401`: `409` / `503`, `REJECTED`, a release for every line, stock back to what it was, cart unchanged
- a release that fails (ignored, the other lines are still released) and a cart clean-up that fails (`201`, `CONFIRMED`, `cartCleared: false`)
- the `SERVICE` token (claims, 2 minutes, valid signature) and that a `SERVICE` token, a token with a non-numeric `sub` and every kind of bad token never reach the checkout (`403` / `401`, never `500`)
- ownership: another person's order is `404`, an admin can read any order, the list is the caller's own and newest first, paging limits
- the startup check (the whole application is started with different profiles), health being public, and `env` / `loggers` needing `ADMIN`
- the admin endpoints (`/api/orders/all` and `/api/orders/stats`): no token, `USER`, `SERVICE` and `ADMIN`, the status filter, the limits, the order, the page shape, the numbers, and that they do not clash with `/api/orders/{id}`; and the new `userId` / `userEmail` fields
- logging: the access line (fields, user, client IP, trace id, `401` / `403`, levels, slow requests), MDC clean after every request and with a single reused worker thread, the real `logback-spring.xml` for each profile (valid JSON for quotes, line breaks and huge values, one-field stack traces, async queues, rolling rules), the audit events with their fields, log injection, that no token, secret or password hash reaches a log (also in the real JSON file), and that the trace id reaches cart, product and inventory (with the `SERVICE` token and the caller's token unchanged)

## Configuration Profiles

### Development Profile (`dev`, default)
- Readable text console, DEBUG for our own code, the SQL text (never its parameter values)
- Placeholder secret (local only)

### Production Profile (`prod`)
- JSON on stdout and in the file, calm levels
- `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` overridable
- `JWT_SECRET` is required

The H2 web console is disabled in all profiles (known RCE vector, CVE-2021-42392).

## Known limits

- **No transaction across services.** If `order-service` stops after saving the order and before it finishes, the order stays `PENDING` and stock may stay reserved. The same is true for a release that fails. Because every reservation is stored with its order id in `inventory-service`, a clean-up job can release them by order id later (a scheduled Lambda on AWS).
- **Two checkouts of the same cart at the same instant** could create two orders (the UI disables the button while the request runs). The stock itself is never oversold, because the stock service reserves atomically.
- **Each service has its own H2 file**, so with two VMs behind a load balancer, orders (and carts) are not shared until the services move to RDS.
- **No cancel, return or refund.** Stock is not put back after a confirmed order. An admin can list every order and read the statistics (`/api/orders/all`, `/api/orders/stats`), but cannot change one.
- **The shared secret weakness** described above.
- **A slow stock service makes a slow checkout.** Every call has its own time-out (2 s to connect, 5 s to read), and after a failure every line is released with its own call, so the worst case is about (1 + number of lines) x 5 seconds. A stopped service answers at once.
- The list of orders is read newest first by creation time and id, and the page size is limited to 100.

## Security notes

- The person is always the one in the token. The request never carries a user id, a price or a total.
- Another person's order answers `404`, exactly like an order that does not exist.
- Tokens, the secret, query strings and response bodies of other services are never written to the log (see "Logging and observability").
- Non-root Docker user.

## License

This project is licensed under the MIT License.

---

**Version**: 1.0.0
**Last Updated**: 2026-11-19
