# DropLink

Share a file with a link that expires. One Spring Boot app with a small web page.

- **Upload** a file (up to 100 MB), pick how long the link lives (1 hour, 1 day, 7 days), optionally a download limit (1 = one-time link) and a password.
- **Share** the link or its QR code. The visitor sees a **preview** (images, PDF, video, audio, text and code) and a Download button.
- **Manage** link (shown once, key after the `#`): see who downloaded (masked IP, browser) and delete the file at once.

## Run it on your computer (no AWS needed)

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn spring-boot:run     # then open http://localhost:8080
```

Files go to `./droplink-data`, links live in memory (gone when you stop the app). This is the default mode, `DROPLINK_MODE=local`.

## Run it on AWS

`DROPLINK_MODE=aws`: files in a private **S3** bucket (the browser uploads and downloads straight to S3 with short-lived signed addresses), links in **DynamoDB** (expired rows removed by TTL), an optional **SNS** mail when a file is downloaded. Step by step: [docs/AWS-SETUP.md](docs/AWS-SETUP.md).

| Setting | Meaning |
|---|---|
| `DROPLINK_MODE` | `local` (default) or `aws` |
| `DROPLINK_SECRET` | signs cookies and local addresses; **the same on every instance** (16+ characters) |
| `DROPLINK_PUBLIC_URL` | the address people use (for the link and QR code); empty = taken from the request |
| `DROPLINK_BUCKET`, `DROPLINK_TABLE`, `DROPLINK_REGION` | aws mode: bucket, table, region |
| `DROPLINK_TOPIC_ARN` | aws mode, optional: SNS topic |
| `DROPLINK_MAX_UPLOAD_MB` | default 100 |

The app keeps nothing on its own disk in aws mode, so any number of copies can run behind a load balancer (next stage).

### Two regions with failover (Terraform)

[terraform/](terraform/README.md) builds the whole thing from nothing: network, servers behind a load balancer, storage and data copy, in two regions, with Route 53 failover on your domain. Run it again in every new sandbox.

## How the safety works

- The link id (12 random characters) is the secret. It is never written to the log in full and never put in an SNS message.
- The manage key is stored only as a SHA-256 hash; the password only as a BCrypt hash. Five wrong passwords from one address lock that link for 15 minutes for that address.
- A preview is decided by the file name only. Images, PDF, video and audio are shown from a signed address with a content type the server chooses. Text, code and HTML are read by the app and shown as escaped text, never run. SVG and unknown types have no preview.
- The bucket stays private. Signed addresses live 10 min (upload), 60 s (download), 5 min (preview).
- Download is a POST so a link scanner cannot use up a one-time link.

## Tests

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test
```

108 tests run without AWS. 16 more (DynamoDB Local and an S3 emulator) are skipped unless switched on: [docs/EMULATOR-TESTS.md](docs/EMULATOR-TESTS.md). Nothing has been run against real AWS yet; the first run on AWS is the real test.

## Known limits (a proof of concept)

- Anyone can create links (no login yet). The five-tries limit is per instance.
- The 100 MB limit is checked by the app after the upload (S3 signed uploads cannot limit size).
- An unfinished upload leaves a file until the S3 lifecycle rule removes it (8 days).

## Logging

One line per event on the console (`journalctl -u droplink` on EC2), each with a request id `[rid]` that is also sent back as the `X-Request-Id` header. Logged: link created, active, unlocked, downloaded, deleted; wrong passwords (try x of 5) and lockouts; every refused request (WARN for 401, 403 and 429, INFO for the rest) with a masked IP; cleanup; SNS problems; unexpected errors with a stack trace. Never logged: full link ids (first 4 characters only), passwords, manage keys, signed addresses.
