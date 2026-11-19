# User Service

A standalone Spring Boot 3.2.1 service for user accounts and login. It lets a person register, log in and read their own profile, and it creates the signed token (JWT) that `product-service` and `inventory-service` check on every write request.

## Overview

`user-service` owns its own H2 file database (`./data/userdb`) with one table, `users`. Passwords are stored only as BCrypt hashes. A login returns a JWT that the client sends as `Authorization: Bearer <token>`. There is no session, no cookie and no refresh token: the token is valid for 8 hours and the UI simply throws it away to "log out".

```
Browser / UI ──POST /api/users/login──▶ user-service ──creates──▶ JWT (HS256, 8 hours)
     │
     └─ Authorization: Bearer <JWT> ──▶ product-service / inventory-service
                                         (each checks the JWT with the same shared secret)
```

Because every service can check the token alone, no service has to call `user-service` on each request, and a request can land on any VM.

## Features

- `POST /api/users/register`, `POST /api/users/login`, `GET /api/users/me`, and `GET /api/users` (admin only)
- Two roles: `USER` (everyone who registers) and `ADMIN` (created on start from environment variables)
- BCrypt password hashing; a password or hash is never returned or logged
- Stateless security (no session, no cookies, CSRF off) with Spring Security's OAuth2 resource server
- Same problem-JSON style for errors as the other services
- Structured JSON logs with a trace id, the caller and one access line per request (see "Logging")
- Flyway migration, Swagger UI with an Authorize button, Actuator and Prometheus metrics
- 80% line-coverage gate (JaCoCo) on the controller, service and repository packages

## API Endpoints

| Method | Endpoint | Needs | Description |
|--------|----------|-------|-------------|
| POST | `/api/users/register` | nothing | Body `{ "email", "password" }`. `201` `{ "id", "email", "role": "USER" }`. `409` if the email is taken, `400` for bad input |
| POST | `/api/users/login` | nothing | Same body. `200` `{ "token", "expiresAt", "email", "role" }`. `401` with the same message for a wrong password and an unknown email |
| GET | `/api/users/me` | valid token | `200` `{ "id", "email", "role" }`. `401` for no or a bad token |
| GET | `/api/users?page=0&size=20&q=` | `ADMIN` token | `200` Spring page of `{ "id", "email", "role", "createdAt" }`, newest first (highest id first). `q` is an optional, case-insensitive "contains" on the email (at most 100 characters). `size` 1 to 100 and `page` 0 to 100000, otherwise `400`. `401` without a token, `403` for a `USER` or `SERVICE` token. Never returns a password hash |

Rules for the input:
- `email`: valid format, at most 255 characters. It is stored in lower case and must be unique (`Ann@Example.com` and `ann@example.com` are the same account).
- `password`: 8 to 72 characters (72 is the most BCrypt can use).

### Example Requests

```bash
# Register
curl -X POST http://localhost:8083/api/users/register \
  -H "Content-Type: application/json" \
  -d '{"email":"ann@example.com","password":"correct-horse-battery"}'

# Log in (the answer contains the token)
curl -X POST http://localhost:8083/api/users/login \
  -H "Content-Type: application/json" \
  -d '{"email":"ann@example.com","password":"correct-horse-battery"}'

# Use the token
curl http://localhost:8083/api/users/me -H "Authorization: Bearer <token>"
```

## The token

| Claim | Value |
|---|---|
| `sub` | the user id, as a string |
| `email` | the user's email (lower case) |
| `role` | `USER` or `ADMIN` |
| `iat` / `exp` | issued at / expires, 8 hours apart |

Signed with HS256. The other services turn the `role` claim into an authority by adding the prefix `ROLE_` (`ADMIN` becomes `ROLE_ADMIN`).

## The secret (important)

The key that signs the tokens is a random value of **at least 32 bytes** in the environment variable `JWT_SECRET` (property `app.security.jwt.secret`). `user-service`, `product-service` and `inventory-service` must all use the same value.

| Setting | Environment variable | Dev default (only in the `dev` profile) |
|---|---|---|
| `app.security.jwt.secret` | `JWT_SECRET` | `dev-only-placeholder-secret-do-not-use-in-aws-0123456789` |
| `app.security.admin.email` | `ADMIN_EMAIL` | `admin@example.com` |
| `app.security.admin.password` | `ADMIN_PASSWORD` | `admin12345` |

`dev` is the default profile, so a local run needs no set-up. The placeholders are public (they are in git), so the **startup check** refuses to start the application when:

- the profile is not `dev` and `JWT_SECRET` is empty or still the placeholder,
- the profile is not `dev` and `ADMIN_EMAIL` or `ADMIN_PASSWORD` is not set,
- (any profile) `JWT_SECRET` is shorter than 32 bytes.

The message says which variable to set and never prints the secret. Create a secret with `openssl rand -base64 48`.

Never commit a real secret. The `env` actuator endpoint shows property values as `******`, even to an admin.

## Access rules

| URL | Who |
|---|---|
| `POST /api/users/register`, `POST /api/users/login` | everyone (an `Authorization` header is ignored on these two calls) |
| `/api/users/me` | any valid token |
| `GET /api/users` (the list of accounts) | `ADMIN` only (`403` for a `USER` or `SERVICE` token, `401` without a token) |
| `/actuator/health/**` (including liveness and readiness) | everyone (the load balancer needs this) |
| `/actuator/env/**`, `/actuator/loggers/**` | `ADMIN` only (`403` for a `USER` token, `401` without a token) |
| Swagger (`/swagger-ui.html`, `/swagger-ui/**`, `/api-docs/**`) | everyone |
| other `/actuator/**` endpoints | everyone (as in the other services) |
| everything else | any valid token |

`401` and `403` answers are problem JSON (`application/problem+json`): `{"type":"about:blank","title":"Unauthorized","status":401,"detail":"..."}`.

## The admin account

On every start, if no user has the email `ADMIN_EMAIL`, an `ADMIN` account is created with the password `ADMIN_PASSWORD`. If the account already exists it is left alone, so starting twice creates only one admin. Use it to log in and get a token that product-service and inventory-service accept for writes.

## Logging

Every line is structured, traceable and says who made the request.

**Where it goes.** A JSON file `logs/user-service.json.log` (folder from `LOG_PATH`, default `logs`; a new file every day and every 10 MB, kept 14 days, 500 MB at most) in every profile, and stdout: JSON outside `dev` (containers and CloudWatch read stdout), readable text in `dev`. One line per event, real JSON (quotes and line breaks are escaped, a stack trace is one field).

**Fields.** `timestamp` (UTC), `level`, `service`, `profile`, `logger`, `thread`, `message`, `stack_trace`, plus:

| Field | Meaning |
|---|---|
| `traceId`, `spanId` | one id for the whole request, across services (also returned to the caller in the `X-Trace-Id` response header) |
| `userId`, `userEmail`, `userRole` | the caller, from the token (`anonymous` without a login; a service token shows its own name, for example `order-service` / `SERVICE`) |
| `clientIp` | first valid address in `X-Forwarded-For`, otherwise the socket address. The header can be faked, so this is for finding things out, never for security |
| `event` and its own fields | `http_request`, `service_started`, `auth_rejected`, `access_denied` and the audit events below |

**Access line.** One per request: `HTTP GET /api/users/me -> 200 in 12 ms` with `httpMethod`, `httpPath` (never the query string), `httpStatus`, `durationMs`. Level: `ERROR` for 500 and above, `WARN` for 401, 403 and slow requests (`slow=true`, more than `app.logging.slow-request-ms`, default 2000), `DEBUG` for health checks and metric scrapes that succeed, `INFO` for the rest.

**Audit events** (logger `audit`): `user_registered` (`newUserId`, `email`), `login_succeeded` (`userId`, `email`), `login_failed` (`WARN`; `email`, `reason` = `unknown_email` or `bad_password`). The log tells the two failures apart; the answer to the caller is exactly the same.

**Levels.** `ERROR` needs a human, `WARN` is a handled problem or a security signal, `INFO` is lifecycle, access and audit, `DEBUG` is developer detail (SQL statements in `dev`). Change a level at runtime as `ADMIN`: `POST /actuator/loggers/com.microservices` with `{"configuredLevel":"DEBUG"}`.

**Never logged:** passwords, password hashes, tokens, the `Authorization` header, the JWT secret, request or response bodies, query strings. Values from callers (email, paths) are cleaned (control characters become `_`, 200 characters at most), so nobody can fake a second log line.

**Privacy.** An IP address and an email are personal data. They are logged because the project asked for it. Set `app.logging.include-email=false` (env `APP_LOGGING_INCLUDE_EMAIL=false`) to keep emails out of the logs (`userEmail` and the `email` field of audit events). Files are kept 14 days.

Follow one request: `jq 'select(.traceId=="<id>")' logs/user-service.json.log`.

## Technology Stack

| Category | Technology | Version |
|----------|-----------|---------|
| Framework | Spring Boot | 3.2.1 |
| Language | Java | 17 |
| Security | Spring Security 6.2 (OAuth2 resource server, Nimbus JOSE) | managed by Boot |
| Password hashing | BCrypt (`BCryptPasswordEncoder`) | managed by Boot |
| Database | H2 (file-based) | Runtime-managed |
| Migration | Flyway | managed by Boot |
| Mapping | MapStruct | 1.5.5 |
| Documentation | Swagger/OpenAPI (springdoc) | 2.2.0 |
| Testing | JUnit 5, Mockito, MockMvc | managed by Boot |
| Code Coverage | JaCoCo | 0.8.11 |

## Getting Started

JDK 17 and Maven 3.9+ are needed. If your machine defaults to another Java, use the script:

```bash
./build.sh          # mvn clean verify  - compiles, runs all tests + the JaCoCo gate
./build.sh package  # mvn clean package -DskipTests - fast jar build (what the Dockerfile does)
./build.sh run      # mvn spring-boot:run - starts the app on :8083
```

Or with Maven directly:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -DskipTests package   # target/user-service-1.0.0.jar
java -jar target/user-service-1.0.0.jar                              # port 8083, dev profile
```

Run it outside `dev`:

```bash
JWT_SECRET="$(openssl rand -base64 48)" ADMIN_EMAIL=boss@example.com ADMIN_PASSWORD='a-strong-password' \
  java -jar target/user-service-1.0.0.jar --spring.profiles.active=prod
```

Useful URLs: `http://localhost:8083/actuator/health` and `http://localhost:8083/swagger-ui.html` (click Authorize and paste the token).

## Docker

```bash
docker build -t user-service:local .
docker run -p 8083:8083 -v "$(pwd)/data:/data" user-service:local
```

The `Dockerfile` has the same shape as the other services (port 8083, health check on `/actuator/health`, proxy handling). In `deploy/local`, `up.sh` runs it as one container named `user` (see `deploy/local/README.md`).

## Testing

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test
```

The suite uses an in-memory H2 database (Flyway runs the real migration) and the real security filter chain. It checks, among other things: register (created, duplicate, bad email, short password, lower-cased email), login (token claims, 8-hour expiry, identical `401` bodies), `/me` (valid, missing, expired, wrong-secret, tampered and unsigned tokens), BCrypt storage, the admin seeded once, the startup check (the whole application is started with different profiles), health being public, and `env` / `loggers` needing `ADMIN`. The list of accounts is tested for every role, the parameter limits, the order, the page shape and that no hash is returned. The logging is tested too: the access line (user, client IP, trace id, levels, `401` / `403` / `500`), the MDC being empty after every request, JSON on stdout and in the file (the real configuration in its own JVM), nothing secret in the logs, log injection, `app.logging.include-email=false` and the audit events.

## Configuration Profiles

### Development Profile (`dev`, default)
- Verbose logging (readable text on the console, JSON in the file), SQL statements logged (never with their values)
- Placeholder secret and admin account (local only)

### Production Profile (`prod`)
- JSON on stdout, less from Spring
- `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` overridable
- `JWT_SECRET`, `ADMIN_EMAIL` and `ADMIN_PASSWORD` are required

The H2 web console is disabled in all profiles (known RCE vector, CVE-2021-42392).

## Security notes

- Passwords are hashed with BCrypt and never returned or logged. SQL parameter logging (which would show hashes) is not turned on, and neither the access line nor any other line holds a token or a query string.
- Wrong password and unknown email give the same `401` body, and an unknown email still costs one BCrypt check, so the answer time does not give it away.
- Validation errors never repeat the password (Spring's own message does, so it is not used).
- A token cannot be cancelled before it expires (8 hours). That is a known trade-off of this design: there is no refresh token and no block list.
- The `409` for a duplicate email tells a caller that an email already has an account. That is how the API is specified, and there is no rate limiting in this first build.
- Non-root Docker user.

## License

This project is licensed under the MIT License.

---

**Version**: 1.0.0
**Last Updated**: 2026-11-19
