# Inventory Service

A standalone Spring Boot 3.2.1 service for tracking stock levels against `product-service`'s catalog, built as the second of two independently deployable Spring Boot applications in this repo - a deliberate exercise in inter-service communication and failure handling once the single-service `product-service` foundation was solid.

## Overview

The Inventory Service provides RESTful APIs for stock management: creating, reading, updating, adjusting, and deleting per-SKU stock records, plus an append-only audit trail of every change. It owns its own H2 file database (separate from product-service's) and validates every new inventory record against product-service's catalog over HTTP before accepting it. It ships with a seeded dataset that matches product-service's catalog exactly - 5,010 inventory records (one per SKU) and 5,010 matching `CREATED` audit log entries - so the two services' data is consistent from the first run.

## Features

### Core Functionality
- Create/read/update/delete inventory records
- `PATCH .../adjust` endpoint to increment or decrement stock without a full read-modify-write round trip
- Repeat-safe `reserve` and `release` endpoints for order-service (role `SERVICE`), see "Reserve and release" below
- Immutable audit log of every create/adjust/update/delete/reserve/release, queryable per SKU, most recent first
- Pagination support on both the inventory list and the audit history
- SKU validation against product-service on write (see "Calling product-service" below)

### Technical Excellence
- Clean layered architecture (Controller -> Service -> Repository -> Database)
- Comprehensive input validation (Bean Validation JSR-303)
- Global exception handling with RFC 7807 Problem Details
- MapStruct for compile-time safe DTO mapping
- Database migrations with Flyway
- OpenAPI/Swagger documentation
- Actuator endpoints for monitoring + Prometheus metrics
- Docker containerization
- 80% line-coverage gate (JaCoCo) on the controller/service/repository/client packages

## Architecture

### Layered Architecture
```
┌─────────────────────────────────────┐
│         Controller Layer            │  REST endpoints, validation
├─────────────────────────────────────┤
│          Service Layer              │  Business logic, audit trail
├─────────────────────────────────────┤
│        Repository Layer             │  Data access
├─────────────────────────────────────┤
│         Database (H2, file-based)   │  Data persistence
└─────────────────────────────────────┘
                 │
                 │ RestClient (2s connect / 3s read timeout)
                 ▼
         product-service :8081
     (GET /api/products/sku/{sku})
```

### Calling product-service

`POST /api/inventory` validates the SKU exists in product-service's catalog before accepting a new stock record, via `ProductClient` (a Spring 6.1 `RestClient`-based client, see `client/ProductClientImpl.java`):

| product-service response | inventory-service behavior |
|---|---|
| 200 with a product body | SKU is valid - proceed with create |
| 404 | `422 SkuNotFoundInCatalogException` |
| Any other error, timeout, or connection failure | `503 ProductServiceUnavailableException` |

This client **throws** on failure (rather than degrading gracefully) because it's a write-path validation: if we can't confirm the SKU is real, we must reject the write rather than silently accept bad data. This is the deliberate opposite of product-service's own `InventoryClient` (added alongside this service), which calls back into inventory-service to enrich a single product read with live stock and **swallows** the same failures into a `null` `stockQuantity` - a read-path enrichment must never break the primary read. Both behaviors are intentional, not an oversight; see the design spec's "Note on the asymmetry".

### Design Patterns Used
- **Repository Pattern**: Data access abstraction
- **DTO Pattern**: Request/Response separation from entities
- **Builder Pattern**: Clean object construction (Lombok)
- **Mapper Pattern**: Entity-DTO mapping (MapStruct)
- **Audit Log Pattern**: Append-only history table written in the same transaction as the change it describes

## Technology Stack

| Category | Technology | Version |
|----------|-----------|---------|
| Framework | Spring Boot | 3.2.1 |
| Language | Java | 17 |
| Build Tool | Maven | 3.9+ |
| Database | H2 (file-based) | Runtime-managed |
| Migration | Flyway | Latest |
| HTTP Client | Spring 6.1 `RestClient` | Bundled with Spring Boot 3.2.1 |
| Mapping | MapStruct | 1.5.5 |
| Documentation | Swagger/OpenAPI | 2.2.0 |
| Metrics | Prometheus (via Micrometer) | Latest |
| Testing | JUnit 5, Mockito, MockMvc, MockRestServiceServer | Latest |
| Code Coverage | JaCoCo | 0.8.11 |

## Prerequisites

- JDK 17 (see [Building without changing your default Java](#building-without-changing-your-default-java) if your machine defaults to a different version)
- Maven 3.9+
- Docker (optional, only needed to build/run the container image)
- `product-service` is **not** required to be running for inventory-service to start, build, or pass its own test suite - it's only needed at call time for `POST /api/inventory` (see above), and for product-service's own read-path enrichment to return live stock.

No external database is required - H2 runs as an embedded file-based database and the schema/data are created automatically by Flyway on startup.

## Getting Started

### Building without changing your default Java

If `java -version` / `mvn -v` on your machine resolves to something other than 17, use the bundled script instead of installing a new default JDK:

```bash
./build.sh          # mvn clean verify  - compiles, runs all tests + the JaCoCo gate
./build.sh package  # mvn clean package -DskipTests - fast jar build (what the Dockerfile does)
./build.sh run      # mvn spring-boot:run - starts the app on :8082
```

It points `JAVA_HOME` at a JDK 17 install for just that one process, so your system default is never touched. If `mvn`/`java` already resolve to 17 on your machine, the plain commands below work the same way.

### Build the Application

```bash
# Build with tests
mvn clean package

# Build without tests (faster)
mvn clean package -DskipTests
```

### Run the Application

```bash
# Using Maven
mvn spring-boot:run

# Using the packaged jar
java -jar target/inventory-service-1.0.0.jar

# With a specific profile
java -jar target/inventory-service-1.0.0.jar --spring.profiles.active=prod

# Pointing at a non-default product-service location
PRODUCT_SERVICE_URL=http://product-service.example.com:8081 java -jar target/inventory-service-1.0.0.jar
```

On first run, Flyway creates the `inventory_items` and `inventory_audit_log` tables and loads the seed data into a local H2 file at `./data/inventorydb` (configurable via the `DB_FILE_PATH` env var).

### Verify the Application

The application starts on port `8082`:

```bash
# Health check
curl http://localhost:8082/actuator/health

# Get inventory records (paginated)
curl http://localhost:8082/api/inventory

# Look up stock for a specific SKU
curl http://localhost:8082/api/inventory/sku/APPLE-IP15P-128
```

## Security (JWT login)

Reading is open to everyone. Changing data needs a login with the `ADMIN` role, except `reserve` and `release`, which need the internal `SERVICE` role. Login tokens are created by `user-service` (`POST /api/users/login`); the `SERVICE` token is made by `order-service` (sub `order-service`, role `SERVICE`, valid 2 minutes). This service only checks tokens.

| Request | Needs |
|---------|-------|
| `GET`, `HEAD`, `OPTIONS` on any path (also `/actuator/health`, Swagger, `/api-docs`) | nothing |
| `POST /api/inventory/sku/{sku}/reserve` and `POST /api/inventory/sku/{sku}/release` | a valid token with role `SERVICE` (`USER` and `ADMIN` get `403`) |
| every other `POST`, `PUT`, `PATCH`, `DELETE` | a valid token with role `ADMIN` (`SERVICE` gets `403`) |
| `/actuator/env` and `/actuator/loggers` (any method) | a valid token with role `ADMIN` (`SERVICE` gets `403`) |

- No token, a bad token or an expired token gives `401`. A valid token without the needed role gives `403`. Both answers are `application/problem+json`.
- Send the token as `Authorization: Bearer <token>`. In Swagger UI, click **Authorize** and paste the token.
- The token is signed with HS256 and one shared secret (env var `JWT_SECRET`, at least 32 bytes, the same in all services). The `role` claim becomes the role (`ADMIN` becomes `ROLE_ADMIN`).
- With the default `dev` profile a labelled placeholder secret is used, so a local run needs no set-up. With any other profile (for example `--spring.profiles.active=prod`) the service **refuses to start** unless `JWT_SECRET` is set to a real value. Never commit a real secret.
- The calls between product-service and inventory-service are plain `GET` reads, so they need no token.

```bash
# Example: adjust stock (needs an ADMIN token in $TOKEN)
curl -X PATCH "http://localhost:8082/api/inventory/sku/APPLE-IP15P-128/adjust?delta=5" -H "Authorization: Bearer $TOKEN" ...
```

## Docker

### Build the Image

```bash
docker build -t inventory-service:local .
```

### Run the Container

```bash
docker run -p 8082:8082 -v "$(pwd)/data:/data" \
  -e PRODUCT_SERVICE_URL=http://host.docker.internal:8081 \
  inventory-service:local
```

A `docker-compose.yml` wiring both services together is planned for a later step and isn't in this repo yet.

## API Documentation

### Swagger UI
```
http://localhost:8082/swagger-ui.html
```

### OpenAPI Specification
```
http://localhost:8082/api-docs
```

### API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/inventory` | Create a stock record (422 if SKU unknown in catalog, 409 if already tracked, 503 if product-service unreachable) |
| GET | `/api/inventory` | Get all inventory records (paginated) |
| GET | `/api/inventory/{id}` | Get inventory record by ID |
| GET | `/api/inventory/sku/{skuCode}` | Get inventory record by SKU (this is what product-service calls for stock enrichment) |
| GET | `/api/inventory/low-stock?page=0&size=20` | Records at or below their reorder threshold (out of stock included), lowest quantity first, then SKU, as a Spring `Page`. Public like the other reads. `page` 0 to 100000 and `size` 1 to 100, otherwise `400` |
| PUT | `/api/inventory/{id}` | Replace quantity/reorder threshold |
| PATCH | `/api/inventory/sku/{skuCode}/adjust?delta=-5` | Increment/decrement stock (400 if result would go negative) |
| POST | `/api/inventory/sku/{skuCode}/reserve?quantity=3&orderId=17` | Take stock for an order line (role `SERVICE`; 409 if not enough stock, 404 unknown SKU, 400 bad parameters) |
| POST | `/api/inventory/sku/{skuCode}/release?orderId=17` | Put the reserved stock of an order line back (role `SERVICE`; safe to repeat) |
| DELETE | `/api/inventory/{id}` | Delete an inventory record |
| GET | `/api/inventory/sku/{skuCode}/audit` | Paginated audit history for a SKU, most recent first |
| GET | `/api/inventory/server-info` | Which server answered this request (see "Server info endpoint" below) |

### Reserve and release (for order-service)

order-service calls these two endpoints, one call per order line, with its short-lived `SERVICE` token. Browsers cannot call them.

- `reserve` (`quantity` 1 to 999, `orderId` a positive number, otherwise `400`) runs one conditional database statement: `UPDATE inventory_items SET quantity_on_hand = quantity_on_hand - :n WHERE sku_code = :sku AND quantity_on_hand >= :n`. If no row changed, the answer is `409` ("Not enough stock for SKU ...: wanted n, available m"). So two callers can never take the last unit and stock never goes below zero, with no lock in the application. On success the line is stored in `stock_reservations` (status `RESERVED`) and an audit entry `RESERVED` is written, all in one transaction. The answer is `200` with the usual inventory JSON (the new quantity).
- Repeating the same call (same order, SKU and quantity) answers `200` again and changes nothing. The same order and SKU after a release, or with another quantity, answers `409`. An unknown SKU answers `404`.
- `release` adds the reserved quantity back, marks the line `RELEASED` and writes an audit entry `RELEASED`. When nothing is reserved for that order and SKU (never reserved, or already released) it changes nothing and still answers `200`. This makes it safe to call for every line after a failure, even for a line whose reserve timed out and never arrived.
- The table `stock_reservations` (migration `V3__create_stock_reservations.sql`) has a unique pair `(order_id, sku_code)`, so a line can be reserved once and released once. Because every reservation is stored with its order id, reservations that were never released (for example when order-service stopped half way) can be found and released by order id later.
- Known limits: `adjust` and `PUT` are older code that reads the quantity and then writes it. If an admin adjusts the same SKU at the exact moment of a reserve, the admin write can overwrite the reserve's change. `reserve` and `release` do not have this problem with each other. Also, if a `release` arrives while the matching `reserve` is still running, the release finds nothing and the reserve then completes; the contract does not store "release first" markers.

### Example Requests

> The `POST`, `PUT`, `PATCH` and `DELETE` examples below need an `ADMIN` token. Add `-H "Authorization: Bearer $TOKEN"` to them (see Security above).

#### Create Inventory Record
```bash
curl -X POST http://localhost:8082/api/inventory \
  -H "Content-Type: application/json" \
  -d '{
    "skuCode": "APPLE-IP15P-128",
    "quantityOnHand": 42,
    "reorderThreshold": 15
  }'
```

#### Adjust Stock
```bash
# Decrement by 5 (e.g. an order shipped)
curl -X PATCH "http://localhost:8082/api/inventory/sku/APPLE-IP15P-128/adjust?delta=-5"

# Increment by 100 (e.g. a restock)
curl -X PATCH "http://localhost:8082/api/inventory/sku/APPLE-IP15P-128/adjust?delta=100"
```

#### View Audit History
```bash
curl "http://localhost:8082/api/inventory/sku/APPLE-IP15P-128/audit?page=0&size=10"
```

### Server info endpoint

`GET /api/inventory/server-info` reports which server instance answered the request, so a dashboard can show (per service) the answering host, IP, and EC2 instance. It lives under `/api/inventory/` so the load balancer's `/api/inventory*` rule routes it here. The response is never cached (`Cache-Control: no-store`), since behind a load balancer each call can land on a different instance.

```bash
curl http://localhost:8082/api/inventory/server-info
```

The JSON has these sections:

| Section | Contents |
|---|---|
| `application` | name, version, active profiles, port |
| `host` | hostname, IPv4 addresses (per network interface), container flag (running in Docker) |
| `request` | `Host` header, serving address/port, client address, `X-Forwarded-For` |
| `runtime` | Java/OS details, CPU count, heap usage, pid, start time, uptime |
| `cloud` | EC2 instance details (instance id/type, AZ, region, private/public IP, AMI); **only present on EC2** |

EC2 details come from the instance metadata service (IMDSv2) with a 500 ms timeout, so off-AWS the lookup fails fast and the `cloud` section is simply omitted. The result is cached for 5 minutes. Only a fixed list of harmless fields is read - never IAM credentials.

| Config property | Env var | Default |
|---|---|---|
| `server-info.cloud-metadata.enabled` | `CLOUD_METADATA_ENABLED` | `true` (set `false` to skip the lookup entirely) |
| `server-info.cloud-metadata.url` | `CLOUD_METADATA_URL` | `http://169.254.169.254` |
| `server-info.cloud-metadata.timeout-ms` | - | `500` |

When the service runs in a container on EC2, the instance's metadata hop limit must be set to 2 (`aws ec2 modify-instance-metadata-options --instance-id <id> --http-put-response-hop-limit 2`); with the default of 1, IMDSv2 token responses don't reach the container and `cloud` will be missing.

## Seed Data

Flyway applies two migrations on startup:
- `V1__create_inventory_table.sql` - schema for `inventory_items` and `inventory_audit_log`
- `V2__seed_inventory.sql` - one `inventory_items` row plus one matching `inventory_audit_log` `CREATED` row for **every** SKU in product-service's catalog (10 handwritten V1 SKUs + 5,000 bulk-generated V2 SKUs)

The SKU codes are extracted verbatim from product-service's own migration files when the seed migration is generated, so the two services' catalogs can never drift apart - no product exists without a matching inventory record. Quantities are deterministically randomized (fixed RNG seed) so every fresh environment gets identical data: mostly 1-500, with roughly 5% forced to `0` to simulate realistic out-of-stock SKUs. `reorder_threshold` defaults to 10 for every seeded row.

Total: 5,010 inventory records + 5,010 audit log entries.

If an inventory record is later deleted via `DELETE /api/inventory/{id}` while the product still exists in product-service's catalog, that SKU becomes "legitimately missing" from inventory-service - product-service's read-path enrichment degrades gracefully in that case (`stockQuantity: null`) rather than failing.

## Monitoring & Observability

### Actuator Endpoints
```
http://localhost:8082/actuator/health
http://localhost:8082/actuator/info
http://localhost:8082/actuator/metrics
http://localhost:8082/actuator/prometheus
```

### Logging

Every line this service writes is structured and can be followed across services. The details of the design are in the "Logging and observability" section of the main `README.md`; this is what matters for `inventory-service`.

- **Where**: one JSON object per line (proper escaping, a stack trace in one `stack_trace` field) in the rolling file `logs/inventory-service.json.log` (daily and every 10 MB, 14 days, 500 MB at most; folder set by `LOG_PATH`). Outside the `dev` profile the same JSON goes to stdout too (containers and CloudWatch read stdout). In `dev` the console is readable text: `HH:mm:ss.SSS LEVEL [traceId] [user=.. ip=..] logger - message`.
- **Fields**: `timestamp` (UTC), `level`, `service`, `profile`, `logger`, `thread`, `message`, `stack_trace`, plus `traceId`, `spanId`, `userId`, `userEmail`, `userRole`, `clientIp` (from the request) and the fields of an event (`event`, `httpStatus`, ...). A caller that is not logged in is `userId=anonymous`; a call from another service shows its name, for example `userId=order-service`, `userRole=SERVICE`.
- **Trace id**: Micrometer Tracing (Brave bridge, nothing is exported) gives every request a `traceId`. It is returned in the response header `X-Trace-Id`, an incoming `traceparent` header is continued, and it is sent on the call to product-service (the SKU check when a stock record is created). One request can be followed through all services with `jq 'select(.traceId=="<id>")' logs/*.json.log`.
- **One access line per request**: `HTTP GET /api/... -> 200 in 12 ms` with `event=http_request`, `httpMethod`, `httpPath` (no query string), `httpStatus`, `durationMs`. Level: 5xx ERROR, 401 and 403 WARN, a request slower than `app.logging.slow-request-ms` (default 2000) WARN with `slow=true`, health checks (`/actuator/health**`, `/healthz`) DEBUG, everything else INFO. `401` and `403` answers also write `event=auth_rejected` (`reason` is `missing_token` or `invalid_token`) or `event=access_denied`; the token is never logged.
- **Audit events** (logger `audit`, always at least INFO): `stock_adjusted` (`sku`, `delta`, `before`, `after`), `inventory_created`, `inventory_updated`, `inventory_deleted` (`sku` and the quantities), `stock_reserved` (`orderId`, `sku`, `quantity`, `after`), `stock_reserve_refused` (WARN; `orderId`, `sku`, `wanted`, `available`), `stock_released` (`orderId`, `sku`, `quantity`, `after`) and `stock_release_noop` (`orderId`, `sku`). A repeated reserve or release that changes nothing writes no second `stock_reserved` or `stock_released`.
- **Calls to other services** are logged as `event=downstream_call` (`downstreamService`, `httpMethod`, `httpPath`, `httpStatus`, `durationMs`): DEBUG for 2xx, WARN for 4xx, 5xx, I/O errors and time-outs (then with the exception class in `exception`).
- **Never logged**: passwords, tokens, the `Authorization` header, the JWT secret, request or response bodies, query strings, SQL parameter values. Values that come from callers (SKU, path, email) have control characters replaced by `_` and are cut at 200 characters, so one request can never make a second log line.
- **Privacy**: the client IP and the user's email are personal data. They are logged on purpose, so a request can be traced to a person. Set `app.logging.include-email=false` (environment variable `APP_LOGGING_INCLUDE_EMAIL=false`) to leave the email out. Log files are kept 14 days.
- **Levels**: change them while the service runs with `POST /actuator/loggers/<logger>` (role `ADMIN`). SQL statements are only logged in the `dev` profile, and never with parameter values.
- **Start-up**: one `event=service_started` line (version, profiles, port, `jwtSecretConfigured=true`, database, URLs of the services it calls) - never the secret.

## Testing

```bash
# Run all tests
mvn test

# Run with coverage (also enforced as a build gate by `mvn verify`)
mvn clean test jacoco:report
```

View the coverage report at `target/site/jacoco/index.html`.

The suite is pure JUnit 5 + Mockito + MockMvc + an embedded H2 database (`@DataJpaTest`) + Spring's `MockRestServiceServer` for the HTTP client - no Docker, Testcontainers, or a live product-service required to run it. The JaCoCo 80% line-coverage gate applies to the `controller`, `service`, `repository`, and `client` packages (the client wrapper contains real error-mapping logic worth covering); generated/boilerplate code (`dto`, `exception`, `config`, `mapper`, `model`) is excluded by design, matching product-service's existing exclusion rationale.

Reserve and release have their own tests: `StockReservationApiTest` (every rule and answer, against a real in-memory database), `ServiceRoleRulesTest` (the four token kinds on both endpoints, `SERVICE` refused on every other write), `StockReservationsMigrationTest` (migration V3 on top of V2) and `StockReservationConcurrencyTest` (many callers over real HTTP racing for the last units: exactly the available number win, stock is never negative, and the numbers add up).

Audit coverage specifically: the service-layer tests assert, via Mockito `ArgumentCaptor`, that each of create/adjust/update/delete produces exactly one correctly-shaped audit entry (right `changeType`, right previous/new quantities). The repository test confirms the 5,010 seeded `inventory_items` rows and 5,010 seeded `inventory_audit_log` rows line up with the distinct SKU count computed live from product-service's own migration files, so the test stays correct if that catalog ever changes.

## Configuration Profiles

### Development Profile (`dev`, default)
- Verbose logging, SQL logging enabled

### Production Profile (`prod`)
- Optimized logging
- DB connection details overridable via `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` env vars

Activate a profile:
```bash
java -jar app.jar --spring.profiles.active=prod
```

The H2 web console is disabled in all profiles (`spring.h2.console.enabled: false`) - it's a known RCE vector (CVE-2021-42392) when left reachable.

### Pointing at a different product-service

| Config property | Env var | Default |
|---|---|---|
| `inventory.product-service.url` | `PRODUCT_SERVICE_URL` | `http://localhost:8081` |

## Security

- Non-root Docker user
- Input validation on all endpoints
- SQL injection prevention (prepared statements / JPA)
- Error message sanitization
- H2 console disabled in every profile
- Outbound HTTP calls to product-service are time-bounded (2s connect / 3s read) so a slow/unreachable dependency can't hang a request indefinitely

## Code Quality

- **Lombok**: Reduces boilerplate
- **MapStruct**: Type-safe mapping
- **JaCoCo**: 80% coverage gate on controller/service/repository/client, enforced in `mvn verify`

## License

This project is licensed under the MIT License.

---

**Version**: 1.0.0
**Last Updated**: 2026-10-04
