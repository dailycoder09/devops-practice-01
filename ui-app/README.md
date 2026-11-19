# ui-app

MicroShop: a React shop front end for the Spring Boot services in this repo (product 8081, inventory 8082, user 8083, cart 8084, order 8085). The layout follows the usual online-shop pattern (blue header with a wide search box, a strip of categories, product rows and a filter panel on the list page, a gallery with big buying buttons on the product page). The look, the name and the wordmark are its own.

It is also a practice tool for AWS load balancing. The admin overview shows which server answered each request and how traffic is split across the servers behind an Application Load Balancer (ALB).

The browser calls relative URLs (`/api/products/...`, `/api/inventory/...`, `/api/users/...`, `/api/cart/...` and `/api/orders/...`) on the same origin it was loaded from. There is no CORS setup and no base URL to configure.

Stack: Vite, React 18, TypeScript (strict), react-router-dom 6, plain `fetch` and plain CSS (colours and sizes are tokens at the top of `src/styles/app.css`; the icons are inline SVG). Tests use Vitest and Testing Library. The page works from a 360 px phone to a wide desktop: two product columns on phones, three on tablets (640 px and up), four on desktops (1024 px and up).

## Pages

### The shop

- **Header** (every page, sticky): the shop name, a wide search box, and the account links. A visitor sees Log in and Register. A logged-in person sees an account menu (their email, My orders, Log out) and the Cart link with the number of items. Only an administrator also sees an Admin link. The search box sends Enter or the button to `/products?search=<text>`. After a 300 ms pause in typing (two characters or more) it suggests up to six product names with a small picture (one `GET /api/products/search` call; an answer to an older request is ignored, and a failed call shows nothing). Up and Down move through the list, Enter opens the row, Escape closes it. When the box is empty and has focus it offers the last five searches, kept only in this browser (`localStorage` key `shop.recentSearches`), with a Clear link.
- **Category strip** (home, list and product pages): one link per category from `GET /api/products/categories`, with a small icon above each name. It scrolls sideways on a phone.
- **Home** (`/`): a banner with a Shop now button, a "Top deals" row (the biggest discounts among the first 40 products), one row for each of the first three categories (each with View all), and tiles for the next categories. Rows scroll sideways.
- **Products** (`/products`): a filter panel on the left (on a phone, a Filters button opens it) with Search, Category and a price range, sort tabs (Default, Price: Low to High, Price: High to Low, Newest First: only the sorts the product API has, via `sortBy` and `sortDir`), a breadcrumb, a "Showing 1 to 24 of N ..." line and the pager. The state lives in the URL as `?search=&category=&minPrice=&maxPrice=&sort=&page=` (`q` is still accepted for the search text), so refresh and deep links work. `page` is one-based, matching the "Page X of Y" label. A card shows the picture, brand, name (two lines at most), price, and, only when the MRP is above the price, the struck-through MRP and "N% off". Each page makes one batch call to the inventory service for the stock badges. If that call fails, the list still shows. The whole card is a link to the product page (the name is the real link, so it works from the keyboard).
- **Product page** (`/products/:id`): a breadcrumb, a gallery (vertical thumbnails, a large picture, Left and Right arrow keys) with the quantity box and two big buttons under it, then brand, name, SKU (click to copy), the price in large type with the MRP and what you save, a stock line (`In stock (42)`, `Only 7 left`, `Out of stock`, `Stock unavailable`), highlights, warranty and seller, description and specifications grouped in tables. It reads `GET /api/products/{id}/details`. An id that is not a positive whole number, or one the service answers 404 for, shows "This product doesn't exist" with a link back to the list. On a phone the buying buttons stay at the bottom of the screen.
- **Add to cart and Buy now** (on the product page): a logged-in person picks a quantity (1 to 99). "Add to cart" adds to the quantity already in the cart (`PUT /api/cart/items/{sku}`). "Buy now" sets the cart to exactly that quantity and opens the cart page. When the product is out of stock both buttons are off. A visitor sees one "Log in to order" button instead, and is brought back to the product after logging in.
- **Cart** (`/cart`, needs a login, shown in the header as "Cart (n)"): a card for each line with the picture, name, brand, unit price, the MRP struck through when it is known, a quantity stepper (minus, number, plus; typing a number shows Update) and Remove, and a "Price details" card (Price for n items, Discount, Total amount, and "You will save ... on this order"). The Discount line only shows when the MRP is known for every line. The pictures and MRPs come from `GET /api/products/sku/{sku}`, once per SKU; if that fails the line is shown without a picture. A line whose product no longer exists is marked "No longer available", and "Place order" stays off until it is removed. "Place order" and "Empty cart" sit in a bar that stays visible at the bottom. "Place order" sends `POST /api/orders` with no body (the server reads the cart and takes the prices itself). On success you land on the orders page with a message. If stock is short (`409`) or a service did not answer (`503`) you stay on the cart and see the reason.
- **My orders** (`/orders`, needs a login): a card for each of your orders, newest first, with its number, date, a coloured status (Confirmed green, Rejected red with the reason, Pending amber), the lines with small pictures, and the total. A visitor who opens `/cart` or `/orders` is sent to the login page and brought back after logging in.
- **Log in and Register**: a blue welcome panel and the form side by side (stacked on a phone).

### The admin area (role `ADMIN` only)

The pages under `/admin` need the `ADMIN` role. A visitor is sent to the login page and brought back afterwards. A logged-in user without the role sees "This area is for administrators." The old `/inventory` address redirects to `/admin/inventory`. The menu on the left (tabs on a phone) has:

- **Overview** (`/admin`): one panel per service with its status, the server nameplate (hostname), IPv4 addresses, EC2 instance and zone, request path, uptime and heap. Press Refresh to ask again. When the load balancer sends the request to the other VM, the hostname changes and a hint says so. Below the panels, the traffic distribution widget sends N requests (1 to 100, default 20, at most 5 in flight) to each service and shows how many each server answered. A totals strip shows product, category and inventory record counts.
- **Inventory** (`/admin/inventory`): paged stock table. Each row has an amount field with Add and Remove buttons that call `PATCH /api/inventory/sku/{sku}/adjust?delta=n`. The row updates in place, and a rejected change (for example, going below zero) shows the reason on that row. A SKU opens a side panel with the product, its stock record and its last five stock changes, plus an "Open product page" link when the product exists. The three sections load separately, so one service being down never blanks the panel.
- **Orders** and **Users**: placeholders ("This page is built next.").

The admin routes live in `src/admin/` (`routes.tsx` lists them, each page is its own file).

## Logging in the browser

`src/lib/logger.ts` writes to the browser console, each line starting with the level and a short time (`[WARN 13:45:12.120] Request failed`). The level is `info` unless `localStorage.shop.logLevel` is `debug` (route changes are then written too). Failed API calls are logged with method, path (no query string), status and trace id: `warn` for 4xx, `error` for 5xx and for network failures. Logging in, logging out and a session that ended are logged at `info` (no email, no token). Tokens, passwords and request bodies are never logged. When a service answers with an `X-Trace-Id` header the error shown to the person ends with "Reference: <trace id>", so they can quote it.

### Product images

The image URLs in product-service are dummy placeholders (`https://placehold.co/...`). Replace them with real ones through `PUT /api/products/{id}/images`. The UI only renders `http` and `https` URLs, and shows a fallback tile with the product's initials when an image is missing or can't load (for example when the browser is offline or blocks the image host). The fallback is drawn locally and makes no request.

## Run it locally

You need Node 22 and npm 10.

```sh
npm ci
npm run dev
```

`npm run dev` starts Vite on http://localhost:5173. It proxies `/api/products` to `http://localhost:8081`, `/api/inventory` to `http://localhost:8082`, `/api/users` to `http://localhost:8083`, `/api/cart` to `http://localhost:8084` and `/api/orders` to `http://localhost:8085`, so the services must be running locally. Without them the pages open, but show "unreachable" and error messages.

Locally there is one server per service, so the traffic distribution widget correctly shows 100 % on one host. It only splits when two VMs sit behind the ALB.

```sh
npm test         # run the tests once
npm run test:watch
npm run build    # type-check, then write the static site to dist/
npm run preview  # serve dist/ locally (no API proxy to the services)
```

## Docker

```sh
docker build -t ui-app .
docker run -p 8080:8080 ui-app
```

The image builds the app with Node, then serves `dist/` from `nginxinc/nginx-unprivileged` (non-root, port 8080). `/healthz` returns `ok` for health checks. Unknown paths fall back to `index.html`, so deep links such as `/products` work.

### Open it through the ALB, not the container alone

nginx here serves static files only. It has no `/api` proxy. If you open http://localhost:8080 directly, the pages load, but every API call returns the app's own `index.html` and the UI reports "The server answered with something other than JSON".

Open the app through the ALB, which routes the API paths to the services, or use the Vite dev server (`npm run dev`), which proxies them.

## ALB listener rules

All three targets sit behind one listener, so the browser sees a single origin.

| Priority | Condition (path)  | Forward to                       | Port |
| -------- | ----------------- | -------------------------------- | ---- |
| 10       | `/api/products*`  | product-service target group     | 8081 |
| 20       | `/api/inventory*` | inventory-service target group   | 8082 |
| default  | everything else   | UI target group (this container) | 8080 |

UI target group health check: `/healthz`.

For the traffic split to show up, register both VMs in each service's target group. The two `server-info` endpoints (`/api/products/server-info` and `/api/inventory/server-info`) report which instance answered.

## Product photos

Product photos live in `public/photos/products/<category>/<type>/1.jpg` to `4.jpg` (640 px, from Wikimedia Commons; `public/photos/ATTRIBUTION.csv` lists the author and licence of each). Vite copies them into the build and the nginx container serves them at `/photos/...` with a 7-day cache, so no S3 bucket is needed. product-service decides which products use them: set `IMAGE_BASE_URL=/photos` when starting it (see `deploy/aws/user-data.sh`). Without that variable products keep their placeholder images. To add or change a photo, replace the file here and rebuild the image.
