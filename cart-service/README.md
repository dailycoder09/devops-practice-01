# Cart Service

A standalone Spring Boot 3.2.1 service for the shopping cart. Every logged-in person has one cart with products and quantities. `order-service` reads this cart when the person places an order, and clears it afterwards.

## Overview

`cart-service` owns its own H2 file database (`./data/cartdb`) with one table, `cart_items`. The cart stores only the SKU and the quantity. Name and price are looked up in `product-service` every time the cart is shown, so a price change shows up at once and a browser can never send its own price.

```
Browser / UI ──Authorization: Bearer <JWT>──▶ cart-service ──GET /api/products/sku/{sku}──▶ product-service
                                                   │
order-service ──GET / DELETE /api/cart (caller's own token)──┘
```

The cart belongs to the user id in the token (`sub`). The user id is never taken from the URL or the body, so nobody can read or change another person's cart.

## Features

- `GET /api/cart`, `PUT /api/cart/items/{sku}`, `DELETE /api/cart/items/{sku}`, `DELETE /api/cart`
- Quantity 1 to 99. Text, decimals, missing values and numbers that do not fit are refused with `400`
- Items are shown in the order they were added; changing a quantity keeps the place
- An item whose product no longer exists is shown with `available: false` (the rest of the cart still works)
- Short time-outs towards `product-service` (connect 2 s, read 5 s); a slow or missing product service gives `503` with a plain sentence, never a `500`, and never a half-saved change
- Stateless security (no session, no cookies, CSRF off) with Spring Security's OAuth2 resource server
- Same problem-JSON style for errors as the other services
- Structured JSON logs with a trace id (it also travels to `product-service`), the caller and one access line per request (see "Logging")
- Flyway migration, Swagger UI with an Authorize button, Actuator and Prometheus metrics
- 80% line-coverage gate (JaCoCo) on the controller, service, client and repository packages

## API Endpoints

All of them need a token with the role `USER` or `ADMIN`.

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/cart` | `200` with the cart (below). `503` if product-service did not answer |
| PUT | `/api/cart/items/{sku}` | Body `{ "quantity": 1..99 }` sets the quantity (adds the item or replaces the quantity, it does **not** add to the old one). `200` with the same body as `GET /api/cart`. `400` for a bad quantity, `422` `Unknown product: SKU` if product-service does not know the SKU, `503` if it did not answer (nothing is saved then) |
| DELETE | `/api/cart/items/{sku}` | `204`, also when the item is not in the cart. Needs no product-service |
| DELETE | `/api/cart` | `204`, also when the cart is already empty |

The cart JSON:

```json
{
  "items": [
    { "sku": "APPLE-IP15P-128", "quantity": 2, "available": true,
      "name": "iPhone 15 Pro", "unitPrice": 999.99, "lineTotal": 1999.98 },
    { "sku": "OLD-THING-1", "quantity": 1, "available": false,
      "name": null, "unitPrice": null, "lineTotal": null }
  ],
  "itemCount": 3,
  "total": 1999.98
}
```

- `itemCount` is the sum of all quantities. `total` is the sum of the `lineTotal` of the available items. Money has two decimals.
- `name`, `unitPrice` and `lineTotal` are always in the JSON; they are `null` for an unavailable item.

### Example Requests

```bash
# Get a token from user-service first (port 8083), then:
TOKEN=<token>

curl -X PUT http://localhost:8084/api/cart/items/APPLE-IP15P-128 \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"quantity":2}'

curl http://localhost:8084/api/cart -H "Authorization: Bearer $TOKEN"
curl -X DELETE http://localhost:8084/api/cart/items/APPLE-IP15P-128 -H "Authorization: Bearer $TOKEN"
curl -X DELETE http://localhost:8084/api/cart -H "Authorization: Bearer $TOKEN"
```

## Access rules

| URL | Who |
|---|---|
| `/api/cart/**` | a valid token with the role `USER` or `ADMIN` |
| `/actuator/health/**` (including liveness and readiness) | everyone (the load balancer needs this) |
| `/actuator/env/**`, `/actuator/loggers/**` | `ADMIN` only (`403` for a `USER` token, `401` without a token) |
| Swagger (`/swagger-ui.html`, `/swagger-ui/**`, `/api-docs/**`) | everyone |
| other `/actuator/**` endpoints | everyone (as in the other services) |
| everything else | any valid token |

- No token, a bad token, an expired token or a token signed with another secret: `401`.
- A valid token with another role gets `403`. This includes the internal `SERVICE` token of `order-service`: it is a valid token, but it may not use the cart.
- A token whose `sub` is not a whole number (text, empty, missing, too big) gives `401`. This is checked before the body is read and before anything is read or written.

`401` and `403` answers are problem JSON (`application/problem+json`): `{"type":"about:blank","title":"Unauthorized","status":401,"detail":"..."}`. Other errors use the same shape (`title`, `status`, `detail`).

## The secret (important)

The key that checks the tokens is a random value of **at least 32 bytes** in the environment variable `JWT_SECRET` (property `app.security.jwt.secret`). All services must use the same value.

| Setting | Environment variable | Dev default (only in the `dev` profile) |
|---|---|---|
| `app.security.jwt.secret` | `JWT_SECRET` | `dev-only-placeholder-secret-do-not-use-in-aws-0123456789` |

`dev` is the default profile, so a local run needs no set-up. The placeholder is public (it is in git), so the **startup check** (`JwtStartupCheck`) refuses to start the application when:

- the profile is not `dev` and `JWT_SECRET` is empty or still the placeholder,
- (any profile) `JWT_SECRET` is shorter than 32 bytes.

The message says which variable to set and never prints the secret. Create a secret with `openssl rand -base64 48`. The `env` actuator endpoint shows property values as `******`, even to an admin.

## Settings

| Environment variable | Default | Meaning |
|---|---|---|
| `PRODUCT_SERVICE_URL` | `http://localhost:8081` | where product-service is (a gateway or load balancer URL also works) |
| `JWT_SECRET` | dev placeholder in `dev` only | see above |
| `DB_FILE_PATH` | `./data/cartdb` | the H2 file (without extension) |

The time-outs towards product-service are in `application.yml` (`cart.product-service.connect-timeout-ms: 2000`, `read-timeout-ms: 5000`).

## Logging

Every line is structured, traceable and says who made the request.

**Where it goes.** A JSON file `logs/cart-service.json.log` (folder from `LOG_PATH`, default `logs`; a new file every day and every 10 MB, kept 14 days, 500 MB at most) in every profile, and stdout: JSON outside `dev` (containers and CloudWatch read stdout), readable text in `dev`. One line per event, real JSON (quotes and line breaks are escaped, a stack trace is one field).

**Fields.** `timestamp` (UTC), `level`, `service`, `profile`, `logger`, `thread`, `message`, `stack_trace`, plus:

| Field | Meaning |
|---|---|
| `traceId`, `spanId` | one id for the whole request, across services (also returned to the caller in the `X-Trace-Id` response header). A trace that arrives in the `traceparent` header (for example from `order-service`) is continued, and every call to `product-service` carries it on |
| `userId`, `userEmail`, `userRole` | the caller, from the token (`anonymous` without a login; a service token shows its own name, for example `order-service` / `SERVICE`) |
| `clientIp` | first valid address in `X-Forwarded-For`, otherwise the socket address. The header can be faked, so this is for finding things out, never for security |
| `event` and its own fields | `http_request`, `downstream_call`, `service_started`, `auth_rejected`, `access_denied` and the audit events below |

**Access line.** One per request: `HTTP GET /api/cart -> 200 in 12 ms` with `httpMethod`, `httpPath` (never the query string), `httpStatus`, `durationMs`. Level: `ERROR` for 500 and above, `WARN` for 401, 403 and slow requests (`slow=true`, more than `app.logging.slow-request-ms`, default 2000), `DEBUG` for health checks and metric scrapes that succeed, `INFO` for the rest.

**Calls to product-service** (`event=downstream_call`): `downstreamService`, `httpMethod`, `httpPath` (no query), `httpStatus`, `durationMs`. `DEBUG` for a good answer, `WARN` for a 4xx or 5xx answer, `WARN` with the exception name for a refused connection or a time-out. Never headers, bodies or queries.

**Audit events** (logger `audit`): `cart_item_set` (`sku`, `quantity`), `cart_item_removed` (`sku`), `cart_cleared`. The user is in the line (`userId`, `userEmail`, `userRole`), the same as in every other line of the request.

**Levels.** `ERROR` needs a human, `WARN` is a handled problem or a security signal, `INFO` is lifecycle, access and audit, `DEBUG` is developer detail (successful calls to product-service, SQL statements in `dev`). Change a level at runtime as `ADMIN`: `POST /actuator/loggers/com.microservices` with `{"configuredLevel":"DEBUG"}`.

**Never logged:** tokens, the `Authorization` header, the JWT secret, request or response bodies, query strings. Values from callers (SKU, paths) are cleaned (control characters become `_`, 200 characters at most), so nobody can fake a second log line.

**Privacy.** An IP address and an email are personal data. They are logged because the project asked for it. Set `app.logging.include-email=false` (env `APP_LOGGING_INCLUDE_EMAIL=false`) to keep emails out of the logs (`userEmail`). Files are kept 14 days.

Follow one request: `jq 'select(.traceId=="<id>")' logs/cart-service.json.log`.

## Technology Stack

| Category | Technology | Version |
|----------|-----------|---------|
| Framework | Spring Boot | 3.2.1 |
| Language | Java | 17 |
| Security | Spring Security 6.2 (OAuth2 resource server, Nimbus JOSE) | managed by Boot |
| HTTP client | Spring `RestClient` | managed by Boot |
| Database | H2 (file-based) | Runtime-managed |
| Migration | Flyway | managed by Boot |
| Documentation | Swagger/OpenAPI (springdoc) | 2.2.0 |
| Testing | JUnit 5, Mockito, MockMvc, a fake product-service on the JDK `HttpServer` | managed by Boot |
| Code Coverage | JaCoCo | 0.8.11 |

## Getting Started

JDK 17 and Maven 3.9+ are needed. If your machine defaults to another Java, use the script:

```bash
./build.sh          # mvn clean verify  - compiles, runs all tests + the JaCoCo gate
./build.sh package  # mvn clean package -DskipTests - fast jar build (what the Dockerfile does)
./build.sh run      # mvn spring-boot:run - starts the app on :8084
```

Or with Maven directly:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -DskipTests package   # target/cart-service-1.0.0.jar
java -jar target/cart-service-1.0.0.jar                              # port 8084, dev profile
```

Run it outside `dev`:

```bash
JWT_SECRET="$(openssl rand -base64 48)" PRODUCT_SERVICE_URL=http://localhost:8081 \
  java -jar target/cart-service-1.0.0.jar --spring.profiles.active=prod
```

Useful URLs: `http://localhost:8084/actuator/health` and `http://localhost:8084/swagger-ui.html` (click Authorize and paste the token).

## Docker

```bash
docker build -t cart-service:local .
docker run -p 8084:8084 -v "$(pwd)/data:/data" -e PRODUCT_SERVICE_URL=http://host.docker.internal:8081 cart-service:local
```

The `Dockerfile` has the same shape as the other services (port 8084, health check on `/actuator/health`, proxy handling). In `deploy/local`, `up.sh` runs it as one container named `cart` (see `deploy/local/README.md`).

## Testing

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test
```

The suite uses an in-memory H2 database (Flyway runs the real migration), the real security filter chain and a fake product-service (a small HTTP server on a random port; it can answer, fail, delay and record what it was asked). It checks, among other things:

- the rules per role and per missing, wrong, expired, tampered or unsigned token; `SERVICE` gets `403`, a non-numeric `sub` gets `401`
- add, replace, remove and clear; the order of items; separate carts per user; a parallel add of the same item
- quantity edges (0, 100, negative, text, decimals, missing, huge numbers, bad JSON)
- unknown SKU (`422`), a product that disappeared (`available: false`), prices that change, totals and `itemCount`
- product-service failing, unreachable, slow or answering nonsense (`503`, nothing saved), and that the user's token is never sent to it
- the startup check (the whole application is started with different profiles), health being public, and `env` / `loggers` needing `ADMIN`
- the logging: the access line (user, client IP, trace id, levels, `401` / `403` / `500`), the MDC being empty after every request, JSON on stdout and in the file (the real configuration in its own JVM), nothing secret in the logs, log injection, `app.logging.include-email=false`, the audit events, and that the trace id reaches the fake product-service and is the one in the cart's own log lines

## Configuration Profiles

### Development Profile (`dev`, default)
- Verbose logging (readable text on the console, JSON in the file), SQL statements logged (never with their values)
- Placeholder secret (local only)

### Production Profile (`prod`)
- JSON on stdout, less from Spring
- `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` overridable
- `JWT_SECRET` is required

The H2 web console is disabled in all profiles (known RCE vector, CVE-2021-42392).

## Known limits

- The cart is not shared between two VMs: each service has its own H2 file, so behind a load balancer with two copies a person could see different carts (fixed later by a shared database). `deploy/local` therefore runs one `cart` container.
- Showing a cart asks product-service once per item, one after the other. That is fine for a small shop, and a batch lookup would be the next step.
- A token cannot be cancelled before it expires (8 hours, as in the other services).
- The shared secret means any service that knows it could create any token (see the README of the project).

## License

This project is licensed under the MIT License.

---

**Version**: 1.0.0
**Last Updated**: 2026-11-19
