# Haveno Orderbook & Liquidity API

A wallet-less, fund-less **observer node** for the Haveno network that ingests the
fully-replicated P2P offer book and completed-trade store and exposes a **neutral,
network-wide orderbook and liquidity view** over a read-only **REST/JSON API**.

It reuses the same lightweight P2P-only bootstrap as the seed and statistics nodes
(`ExecutableForAppWithP2p` + `ModuleForAppWithP2p`), so it **never starts a Monero wallet,
holds no funds, and never registers to trade** — it only observes and serves data.

> This document is the single reference for the feature: what it is, how it works, how to
> build/run/deploy it, how it was verified, and its known limits. The machine-readable API
> contract is served live at `GET /api/v1/openapi.yaml`
> (source: [`src/main/resources/openapi.yaml`](src/main/resources/openapi.yaml)); a
> feasibility study and mainnet runbook are in
> [`docs/orderbook-api-feasibility.md`](../docs/orderbook-api-feasibility.md); k3s/k8s
> deployment details are in [`k8s/README.md`](k8s/README.md).

---

## Why this works (design in one paragraph)

Haveno is a Bisq-style flood-fill P2P network: every fully-bootstrapped node receives a
**complete replica** of all currently-live offers (`OfferPayload`, ~11-minute TTL, gossiped
via `P2PDataStorage`) and of the append-only completed-trade store (`TradeStatistics3`). So a
single passive node already holds the entire network orderbook in memory — no crawling or
cross-node aggregation is needed. This module adds a **neutral aggregation layer** over that
in-memory data plus a **REST transport**. Unlike the daemon's existing gRPC `GetOffers`
(which filters to offers *takeable by the local node* and hides your own), this reads
`OfferBookService.getOffers()` **unfiltered** for a true market-wide view.

---

## Architecture

| Component | File |
|---|---|
| Entry point (wallet-less P2P bootstrap) | `src/main/java/haveno/orderbook/OrderbookMain.java` |
| Node wiring (pins services, starts REST) | `src/main/java/haveno/orderbook/OrderbookNode.java` |
| Neutral aggregation (orderbook/markets/trades/prices) | `src/main/java/haveno/orderbook/OrderbookAggregator.java` |
| REST server (JDK `HttpServer` + Gson) | `src/main/java/haveno/orderbook/RestApiServer.java` |
| Env-driven config | `src/main/java/haveno/orderbook/OrderbookConfig.java` |
| JSON DTOs (API contract) | `src/main/java/haveno/orderbook/model/Dto.java` |
| OpenAPI 3 spec (served at `/api/v1/openapi.yaml`) | `src/main/resources/openapi.yaml` |
| Unit tests | `src/test/java/haveno/orderbook/OrderbookAggregatorTest.java` |

Data sources (all existing `core`/`p2p` services, read directly — **core is unmodified**):
`OfferBookService` (live offers), `PriceFeedService` (external index prices),
`TradeStatisticsManager` (completed trades), `P2PService` (connection/bootstrap state). The
depth logic mirrors the pattern in `core/.../api/CorePriceService.getMarketDepth()`.

The REST layer uses the JDK's bundled `com.sun.net.httpserver.HttpServer` and the
already-present Gson, so it adds **no new third-party dependency** (Gradle
dependency-verification metadata is unchanged).

---

## Data exposed

Every market is `XMR/<currency>` (no cross-currency pairs). The API surfaces:

- **Live orderbook** per market: aggregated bid/ask price levels with size and cumulative
  depth, plus best bid/ask/spread/mid.
- **Per-market summaries**: liquidity per side (XMR), unique makers, offer counts, index
  price, last trade price, 24h volume/count.
- **Raw offers**: per-offer detail — direction, price (fixed or resolved from a market
  margin), amount/min-amount, payment method, maker/taker/penalty fees, buyer/seller
  security deposits, trade limits/period, F2F country/city, private-offer flag, maker onion
  address (redacted by default), date.
- **Reference (index) prices** from the price feed (used to resolve margin-priced offers).
- **Completed-trade history** per market (price, amount, volume, payment method, date).

---

## REST API reference

- **Base URL:** `http://<host>:8080/api/v1` · all endpoints are `GET` · no auth (read-only).
- **Content type:** `application/json; charset=utf-8` (`?pretty=true` for indented JSON).
- **CORS:** `Access-Control-Allow-Origin: *` · **Errors:** `{"error": "...", "status": <code>}`.

### Conventions (read first)

| Concept | Meaning |
|---|---|
| Market | Always `XMR/<currency>`; address it by the **counter-currency code** (`EUR`, `USD`, `BTC`, `BCH`, …). |
| Amounts | `amountXmr`, `*LiquidityXmr`, `cumulativeXmr` are in **whole XMR**. |
| Prices | Counter currency **per 1 XMR** (fiat/traditional); crypto markets follow the offer's monetary convention. |
| Direction | Maker's perspective on XMR: **`BUY` = bid**, **`SELL` = ask**. |
| Timestamps | Unix epoch **milliseconds**, UTC. |
| Unpriced offers | Margin offers with no recent reference price have `price: null` and are counted in `unpricedOfferCount`, not placed on a level. |
| Freshness | Offers expire after ~11 min unless refreshed → the book is **currently-live offers**. |
| Trade history | Completed trades carry **no direction**; publication is delayed up to 24h for privacy, so 24h volume lags. |

### Endpoints

| Endpoint | Description |
|---|---|
| `GET /health` | Node + API health (k8s liveness/readiness). |
| `GET /markets` | All active markets with liquidity + price summaries, incl. per-side VWAP (`bidVwap`/`askVwap`) and 24h `open24h`/`high24h`/`low24h` from the (deduplicated) trade statistics. |
| `GET /ticker` | Alias of `/markets`. |
| `GET /orderbook/{market}?depth={n}` | Aggregated bid/ask levels + cumulative depth. |
| `GET /offers?market={code}&direction={BUY\|SELL}` | Raw per-offer detail. |
| `GET /prices` | External reference (index) prices by currency code. |
| `GET /trades/{market}?limit={n}&since={epochMs}&until={epochMs}` | Completed-trade history in `[since, until)`, newest first. |
| `GET /openapi.yaml` | Machine-readable OpenAPI 3 spec. |

### Example

`GET /api/v1/orderbook/BCH`
```json
{
  "market": "XMR/BCH",
  "counterCurrency": "BCH",
  "currencyType": "crypto",
  "indexPrice": 1.7258283749,
  "bids": [{"price": 0.0048, "amountXmr": 0.8, "offerCount": 1, "cumulativeXmr": 0.8}],
  "asks": [{"price": 0.005,  "amountXmr": 1.0, "offerCount": 1, "cumulativeXmr": 1.0}],
  "unpricedOfferCount": 0,
  "timestamp": 1790320638038
}
```

### Notes for agent/automation consumers

- Fetch `GET /api/v1/openapi.yaml` at runtime to generate a client or ground a tool schema.
- Enumerate `/markets` first, then drill into `/orderbook/{code}` and `/trades/{code}`; never
  assume a fixed market list — it's whatever is currently live.
- Handle nullable price fields (`bestBid`, `bestAsk`, `spread`, `indexPrice`,
  `lastTradePrice`, offer `price`/`volume`).
- Poll every 10–30s (data changes on the ~11-min TTL cadence); there is no streaming endpoint.

---

## Configuration (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `HAVENO_REST_HOST` | `0.0.0.0` | REST bind interface. |
| `HAVENO_REST_PORT` | `8080` | REST port. |
| `HAVENO_BASE_CURRENCY_NETWORK` | `XMR_MAINNET` | `XMR_MAINNET` / `XMR_STAGENET` / `XMR_LOCAL`. |
| `HAVENO_XMR_NODE` | — | monerod URI for reserve-spent offer validation. |
| `HAVENO_APP_DATA_DIR` | `/data` | P2P/Tor data directory (persist in k8s). |
| `HAVENO_EXPOSE_MAKER_ADDRESS` | `false` | Include maker onion address in `/offers`. |
| `HAVENO_MAX_TRADES` | `5000` | Cap on `/trades` rows. |
| `HAVENO_HTTP_THREADS` | `8` | REST server worker threads. |
| `HAVENO_USE_LOCALHOST_FOR_P2P` | — | `true` for local dev networks. |

Extra Haveno CLI flags can be appended after the mapped ones (the container passes them
verbatim), e.g. `--seedNodes=...`, `--useTorForXmr=off`, `--torrcFile=...`.

---

## Build & run

```bash
# Build the app distribution (needs jitpack.io + Maven Central reachable)
./gradlew :orderbook:installDist          # -> orderbook/build/app/{bin,lib}, and ./haveno-orderbook

# Run directly against mainnet (bundled Tor; needs outbound network + a monerod)
./haveno-orderbook --baseCurrencyNetwork=XMR_MAINNET --useNativeXmrWallet=false \
  --appDataDir=/some/data --xmrNode=http://<monerod>:18081
# then: curl localhost:8080/api/v1/health

# Unit tests
./gradlew :orderbook:test
```

### Docker

The Dockerfile packages the pre-built distribution onto a JRE base (build the dist first):

```bash
./gradlew :orderbook:installDist
docker build -t haveno-orderbook:latest orderbook
docker run -p 8080:8080 -e HAVENO_XMR_NODE=http://<monerod>:18081 haveno-orderbook:latest
```

### Kubernetes / k3s

Manifests are in [`k8s/`](k8s/) (`configmap.yaml`, `deployment.yaml` + PVC, `service.yaml`),
with liveness/readiness probes on `/api/v1/health` and a `/data` PVC for the P2P store + Tor
identity. Full build-and-deploy instructions — including importing the image into k3s's
containerd (`docker save` + `k3s ctr images import`) — are in [`k8s/README.md`](k8s/README.md).

---

## Mainnet & Tor requirements

Haveno mainnet reaches its seed nodes **only over Tor** (`.onion` addresses). Tor is embedded
in the app (netlayer) — **no Tor sidecar is needed** — but the pod/host must have ordinary
**outbound TCP egress** so Tor can build circuits. A normal home k3s node has this. A
reachable mainnet `monerod` (external or the optional sidecar in `deployment.yaml`) is used
for reserve-spent offer validation. Mainnet bootstrap (Tor + P2P sync) takes several minutes;
`/health` returns 200 while `bootstrapped:false` during bring-up — check the `bootstrapped`
field for true data readiness.

---

## Verification

Verified end-to-end on a self-contained local network (2× monerod in testnet/fixed-difficulty,
seed node, registered arbitrator, funded maker daemon):

- `:orderbook:test` — **7/7 unit tests pass** (sorting, cumulative depth, per-price-level
  aggregation, unpriced-offer handling, depth limits, market summaries).
- Observer starts **unattended**, bootstraps P2P, serves all endpoints (`/health` → `UP`).
- Two **real arbitrator-signed offers** (SELL + BUY of XMR/BCH) placed via the maker daemon
  propagated P2P → observer → API correctly: `/orderbook/BCH` returned
  `bids:[0.0048 × 0.8 XMR]`, `asks:[0.005 × 1.0 XMR]`; `/markets` returned
  `bestBid 0.0048, bestAsk 0.005, spread 0.0002, mid 0.0049` with correct per-side liquidity.
  XMR amounts are reported accurately (the module reads atomic units directly from the P2P
  payload).
- **Docker image** builds; the container starts unattended (`--network host`), bootstraps in
  ~12s, and serves the same live book.
- A soak (`scripts/soak-test.sh`) confirmed continuous healthy serving (clean polls, zero
  failures) for the duration it ran.

> The full multi-hour mainnet soak is intended to run in a persistent environment (e.g. your
> k8s cluster); the soak script works against any deployment.

---

## Mainnet verification (2026-09-25)

- Built with the self-contained `orderbook/Dockerfile.full` (multi-stage: JDK 21 builder → JRE runtime,
  build context = repo root): `docker build -f orderbook/Dockerfile.full -t haveno-orderbook .`
- Ran against **XMR_MAINNET (RetoSwap)** with an external shared Tor (`--torControlHost=... --torControlPort=9051
  --torControlPassword=...`) and a clearnet monerod (`HAVENO_XMR_NODE`, `--useTorForXmr=off`). Bootstrap in a
  few minutes: 20 markets, ~480 offers, ~58k trade statistics.
- Crypto-market prices are counter per XMR (XMR/BTC ≈ 0.0067, XMR/BCH ≈ 1.65) — consistent with the index price.
- **Fixed: every trade appeared twice** in `/trades` and in the 24h volume/count — both traders publish a
  `TradeStatistics3` for the same trade (different payload hashes). Entries are now deduplicated on
  (currency, date, price, amount, payment method).
- P2P books can be crossed (e.g. XMR/EUR best bid above best ask) because prices depend on the payment method.

## Known issues / notes

- **`haveno-cli` amount scaling (fixed):** during verification the CLI's `createoffer
  --amount` scaled by 1e8 (satoshi) while the daemon expects XMR atomic units (1e12), making
  CLI amounts 10,000× too small; this was fixed separately on `master`
  (`fix(cli): scale offer amounts to XMR atomic units`). This module was never affected — it
  reads offer amounts directly from the P2P payload.
- **Privacy:** the maker onion address is public on the wire but redacted in the API by
  default (`HAVENO_EXPOSE_MAKER_ADDRESS=false`).
- **Scale:** `/trades` is capped by `HAVENO_MAX_TRADES`; add pagination if you need larger
  history exports.

---

## Files

```
orderbook/
  build.gradle wiring          # in root build.gradle + settings.gradle (module ':orderbook')
  Dockerfile, docker-entrypoint.sh, .dockerignore
  k8s/{configmap,deployment,service}.yaml, k8s/README.md
  scripts/soak-test.sh
  src/main/java/haveno/orderbook/{OrderbookMain,OrderbookNode,OrderbookAggregator,RestApiServer,OrderbookConfig}.java
  src/main/java/haveno/orderbook/model/Dto.java
  src/main/resources/{openapi.yaml,logback.xml}
  src/test/java/haveno/orderbook/OrderbookAggregatorTest.java
docs/orderbook-api-feasibility.md   # feasibility study + local & mainnet soak runbooks
```

Licensed under AGPLv3, like the rest of Haveno.
