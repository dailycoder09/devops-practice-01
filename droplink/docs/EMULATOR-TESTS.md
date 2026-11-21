# Emulator tests (opt-in)

The normal `mvn test` runs the AWS code **offline** (mocks and a presigner with fake keys). Two more test classes talk
to real servers, but only when you ask for it with an environment variable. Without the variable they are skipped.

| Test class                        | Needs                                        | Switch                  |
|-----------------------------------|----------------------------------------------|-------------------------|
| `DynamoLinkRepositoryEmulatorTest` | DynamoDB Local                               | `TEST_DYNAMO_ENDPOINT`  |
| `S3FileStoreEmulatorTest`          | an S3 server with path-style (MinIO)         | `TEST_S3_ENDPOINT` (+ `TEST_S3_BUCKET`) |

They use fake credentials. Nothing here touches your real AWS account.

## 0. Maven needs two version overrides (until the pom is fixed)

Spring Boot 3.2.1 pins Apache HttpClient 5 at 5.2.x, but AWS SDK 2.59.x needs 5.4 or newer. Without the fix, every
test (and the real app in aws mode) that builds an `S3Client`, `DynamoDbClient` or `SnsClient` fails with
`NoClassDefFoundError: org/apache/hc/client5/http/ssl/TlsSocketStrategy`.
The permanent fix is two lines in `pom.xml`, in `<properties>`:

```xml
<httpclient5.version>5.5</httpclient5.version>
<httpcore5.version>5.3.4</httpcore5.version>
```

Until those are in the pom, add `-Dhttpclient5.version=5.5 -Dhttpcore5.version=5.3.4` to the `mvn` commands below.

## 1. DynamoDB Local

```bash
# a free port on this machine (here: whatever the OS gives us)
PORT=$(python3 -I -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])')

podman pull docker.io/amazon/dynamodb-local:latest
podman run -d --name droplink-test-dynamo -p 127.0.0.1:$PORT:8000 \
  docker.io/amazon/dynamodb-local:latest -jar DynamoDBLocal.jar -inMemory -sharedDb

cd droplink
TEST_DYNAMO_ENDPOINT=http://127.0.0.1:$PORT \
AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-west-2 \
JAVA_HOME=$(/usr/libexec/java_home -v 17) \
mvn -q test -Dtest=DynamoLinkRepositoryEmulatorTest \
  -Dhttpclient5.version=5.5 -Dhttpcore5.version=5.3.4      # see section 0

# clean up
podman rm -f droplink-test-dynamo
podman rmi docker.io/amazon/dynamodb-local:latest          # only if you did not have it before
```

The test creates its own table `droplink_test_<random>` and deletes it at the end (the container is in memory, so
everything is gone when you remove it anyway). It checks: the full life of a link, `tryCountDownload` refusing when
expired / at the limit / PENDING, 20 threads on a link with `maxDownloads=5` (exactly 5 successes, final count 5),
only one winner when 10 threads activate the same link, events newest first, the ttl copied to events, and `delete`
removing the link with all its events (60 events, more than two batches of 25).

## 2. MinIO (S3)

```bash
PORT=$(python3 -I -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])')

podman run -d --name droplink-test-minio -p 127.0.0.1:$PORT:9000 \
  -e MINIO_ROOT_USER=testuser -e MINIO_ROOT_PASSWORD=testpass123 \
  <minio image> server /data

cd droplink
TEST_S3_ENDPOINT=http://127.0.0.1:$PORT TEST_S3_BUCKET=droplink-test \
AWS_ACCESS_KEY_ID=testuser AWS_SECRET_ACCESS_KEY=testpass123 AWS_REGION=us-west-2 \
JAVA_HOME=$(/usr/libexec/java_home -v 17) \
mvn -q test -Dtest=S3FileStoreEmulatorTest \
  -Dhttpclient5.version=5.5 -Dhttpcore5.version=5.3.4      # see section 0

podman rm -f droplink-test-minio
```

* MinIO needs a user of 3+ characters and a password of 8+ characters, so the fake keys here are not `test/test`.
* The test creates the bucket itself when it is missing, so no `mc` container is needed.
* All keys live under a random prefix `droplink-test-xxxxxxxx/` and are deleted by the tests.
* **The image:** `docker.io/minio/minio` and `quay.io/minio/minio` could not be pulled when this was written
  (Docker Hub: "access denied", quay.io: "unauthorized"), so this test has **not been run yet** against a real
  server. Use any S3-compatible image you can pull and that supports path-style addressing and the
  `response-content-disposition` / `response-content-type` query parameters; set its root user and password to the
  values above (or change the `AWS_*` variables to match).
* It checks: upload through the presigned PUT (with `java.net.http.HttpClient`), that a PUT with a different
  Content-Type is refused (403), `head`, `readStart` (also for a missing key and an empty file), a presigned GET
  with the attachment header and with an inline + content-type override, an expired address (403), and `delete`.

## 3. What a green run looks like

`target/surefire-reports/com.droplink.store.aws.*EmulatorTest.txt` shows `Skipped: 0`. With the variable missing, the
same files show every test as skipped.
