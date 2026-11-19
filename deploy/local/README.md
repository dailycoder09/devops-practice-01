# Run the whole shop locally in containers (Podman or Docker)

One command builds the seven images (`product-service`, `inventory-service`, `user-service`, `cart-service`, `order-service`, `ui-app` and the gateway) and starts a local copy of the AWS layout:

```
                                      http://localhost:8000
                                                  |
                                           [ gateway ]            nginx applying the same path rules as the ALB
   /api/products*  /api/inventory*  /api/users*  /api/cart*  /api/orders*  everything else
         |               |               |            |            |             |
  product-a/b      inventory-a/b       user         cart        order         ui-a/b      a and b = two simulated VMs
  (hostname vm-a / vm-b)             (one only)  (one only)    (one only)

  The services call each other through the gateway:
     cart  ->  product-service            order  ->  cart, product-service and inventory-service
```

Each simulated VM runs `product-service`, `inventory-service` and `ui-app` with its own H2 database file, exactly like your two EC2 instances. The services call each other through the gateway, the way they would through the ALB DNS name.

**There is a single `user` container locally** (hostname `vm-a`), not one per VM. Each container has its own H2 file, so two user containers would not know each other's accounts and a login would fail at random. This means the local setup shows login and the token checks, but not "login works on whichever VM answers". That check is for AWS, once the VMs share a database (RDS).

**The same is true for `cart` and `order`**: one container each (hostname `vm-a`), because a second cart container would hold other items and a second order container would know other orders. `cart` asks `product-service` for names and prices. `order` reads the cart with the caller's own token, asks `product-service` for the prices, and reserves stock in `inventory-service`; all three addresses (`PRODUCT_SERVICE_URL`, `INVENTORY_SERVICE_URL`, `CART_SERVICE_URL`) point at the gateway, `http://gateway:8080`.

All services run with the default `dev` profile, so they share the public dev placeholder secret and the dev admin account is `admin@example.com` / `admin12345`. These are for local use only and the services refuse them outside `dev`.

## Before the first run (Podman)

Seven JVMs need memory (product x2, inventory x2, user, cart, order). The default Podman machine (2 GB) is too small:

```bash
podman machine init --cpus 4 --memory 6144     # first time only
podman machine start
```

If a machine already exists, resize it while stopped:

```bash
podman machine stop
podman machine set --cpus 4 --memory 6144
podman machine start
```

Check it works with `podman --version` and `podman info`.

**Seven JVMs may need more than 6144 MB.** Each one is limited to 320 MB of heap (`-Xmx320m`) and needs more memory than the heap (class data, threads), and the two `ui-app` containers and the gateway need a little too. The earlier five-JVM setup used 6144 MB; for seven JVMs that may not be enough. This could not be tested here (image builds fail on this Mac). If containers are killed or restart ("Killed" in `podman logs`), give the machine more, for example `podman machine set --cpus 4 --memory 8192` while it is stopped.

## Run

```bash
deploy/local/up.sh        # build images, start everything, wait until the apps are ready
deploy/local/down.sh      # stop and remove the containers and the network
```

Then open **http://localhost:8000**. Use `GATEWAY_PORT=9000 deploy/local/up.sh` if 8000 is taken. To use Docker instead: `CONTAINER_CLI=docker deploy/local/up.sh` (written for it, but only Podman is the intended runtime here).

The first build downloads Maven and npm dependencies and takes a few minutes. Later builds reuse the cache.

## What to try

| Try | Expected |
|---|---|
| Dashboard, press Refresh several times | The server name alternates between `vm-a` and `vm-b` |
| "Send 20 requests" | Roughly a 10 / 10 split per service |
| `podman stop product-b`, then refresh | Requests keep working through `vm-a`; the gateway skips the stopped VM |
| `podman start product-b` then `podman restart gateway` | Traffic returns to both VMs |
| Add stock on the Inventory page, then refresh a few times | The quantity can differ between refreshes: each VM has its own H2 file |
| Register on the Register page, then log in | Works every time, even though other requests alternate between VMs: there is one `user` container |
| Log in as `admin@example.com` / `admin12345` and add stock | Works only for the admin; a normal account does not see "Add stock" |
| Log in, open a product, add it to the cart, open the cart | The cart is the same on every refresh: there is one `cart` container |
| Place the order, then open My orders | The order is `CONFIRMED` and the cart is empty (see the stock note below) |
| `podman logs -f user` | The log of the user service (`cart` and `order` work the same way) |
| `podman logs -f product-a` | The service log of one VM |

## How it differs from the real ALB

- nginx retries the other backend when one refuses a connection, so a stopped VM is usually invisible to the user. The ALB marks a target unhealthy after its health checks fail (and returns errors in the meantime).
- nginx looks up the backend names once at start. That's why `up.sh` starts the gateway last and why it needs a restart after you recreate containers.
- The ALB uses health check paths (`/actuator/health`, `/healthz`); here a refused connection stands in for them.
- Stock is not shared between the two inventory containers. To keep a `reserve` and its `release` on the same database, `order` talks to `inventory-a` directly (`INVENTORY_SERVICE_URL=http://inventory-a:8082`), not through the gateway. The cost: orders only ever change the stock of `inventory-a`, so a stock check through the gateway shows different numbers on each refresh (half of the requests reach `inventory-b`, which orders never change). This is the same "each VM has its own H2 file" limit as above and goes away once the VMs share a database (RDS). It cannot be tested here.
- Hostnames are set to `vm-a` / `vm-b` for readability. On EC2 you'll see the real host name and, when the metadata hop limit is 2, the instance ID.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `cannot reach its engine` | `podman machine start` |
| Containers killed or restarting, "Killed" in `podman logs` | Not enough memory: raise it with `podman machine set --memory 8192` (the machine must be stopped first, see above) |
| `port is already allocated` | Another process uses 8000: set `GATEWAY_PORT` |
| Build prompts to pick a registry | Shouldn't happen: the Dockerfiles use full `docker.io/...` image names |
| `host not found in upstream` in `podman logs gateway` | A backend container is missing: run `down.sh` then `up.sh` |
| A service did not become ready | `podman logs product-a` (or the one named in the message; the user, cart and order services are `podman logs user`, `podman logs cart` and `podman logs order`) |
