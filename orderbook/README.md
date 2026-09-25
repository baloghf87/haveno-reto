# Haveno Orderbook & Liquidity API

A wallet-less, fund-less **observer node** that joins the Haveno P2P network, receives the
fully-replicated offer book and trade-statistics store, and exposes a **neutral,
network-wide orderbook and liquidity view** over a read-only REST/JSON API.

It is built on the same lightweight P2P bootstrap as the seed and statistics nodes
(`ExecutableForAppWithP2p` + `ModuleForAppWithP2p`), so it never starts a Monero wallet,
holds no funds, and never registers to trade — it only *observes*.

> **This document is written to be handed to other agents/developers as the integration
> contract.** The machine-readable spec is served live at `GET /api/v1/openapi.yaml`
> (source: [`src/main/resources/openapi.yaml`](src/main/resources/openapi.yaml)).

---

## Quick start

```bash
# 1. Build the whole app + this module (needs jitpack.io + Maven Central reachable)
./gradlew :orderbook:installDist

# 2a. Run directly against a local dev network (see "Local soak" below)
HAVENO_REST_PORT=8080 ./haveno-orderbook \
  --baseCurrencyNetwork=XMR_LOCAL --useLocalhostForP2P=true \
  --useNativeXmrWallet=false --appDataDir=/tmp/ob-data --xmrNode=http://localhost:28081

# 2b. Or build & run the container (mainnet)
docker build -t haveno-orderbook:latest orderbook
docker run -p 8080:8080 -e HAVENO_XMR_NODE=http://<monerod>:18081 haveno-orderbook:latest

# 3. Query
curl -s localhost:8080/api/v1/health | jq
curl -s localhost:8080/api/v1/markets | jq
curl -s 'localhost:8080/api/v1/orderbook/EUR?depth=10' | jq
```

---

## API reference

- **Base URL:** `http://<host>:8080/api/v1`
- **Method:** all endpoints are `GET`
- **Auth:** none (read-only public data). Terminate TLS + apply auth/rate-limiting at an
  ingress/reverse proxy if exposing publicly.
- **Content type:** `application/json; charset=utf-8` (add `?pretty=true` for indented JSON).
- **CORS:** `Access-Control-Allow-Origin: *`.
- **Errors:** non-2xx responses are `{"error": "...", "status": <code>}`.

### Conventions (read this first)

| Concept | Meaning |
|---|---|
| Market | Always `XMR/<currency>` — there are no cross-currency pairs. Address a market by its **counter-currency code** (`EUR`, `USD`, `BTC`, …). |
| Amounts | `amountXmr`, `*LiquidityXmr`, `cumulativeXmr` are in **whole XMR**. |
| Prices | Counter currency **per 1 XMR** (fiat/traditional markets). Crypto markets follow the offer's monetary convention. |
| Direction | Maker's perspective on XMR: **`BUY` = bid** (maker buys XMR), **`SELL` = ask** (maker sells XMR). |
| Timestamps | Unix epoch **milliseconds**, UTC. |
| Unpriced offers | Market-based (margin) offers only get a numeric price when a recent reference price exists; otherwise `price` is `null` and they are counted in `unpricedOfferCount`. |
| Freshness | Offers expire after ~11 min unless refreshed, so the book is **currently-live offers**, not history. |
| Trade history | Completed trades carry **no direction**; publication is delayed up to 24h for privacy, so 24h volume lags real time. |

### Endpoints

| Endpoint | Description |
|---|---|
| `GET /health` | Node + API health; use for k8s liveness/readiness. |
| `GET /markets` | All active markets with liquidity + price summaries. |
| `GET /ticker` | Alias of `/markets`. |
| `GET /orderbook/{market}?depth={n}` | Aggregated bid/ask levels + cumulative depth. |
| `GET /offers?market={code}&direction={BUY\|SELL}` | Raw per-offer detail. |
| `GET /prices` | External reference (index) prices by currency code. |
| `GET /trades/{market}?limit={n}&since={epochMs}` | Completed-trade history, newest first. |
| `GET /openapi.yaml` | Machine-readable OpenAPI 3 spec. |

### Example responses

`GET /api/v1/health`
```json
{
  "status": "UP",
  "bootstrapped": true,
  "network": "XMR_MAINNET",
  "numConnections": 11,
  "numOffers": 342,
  "numMarkets": 27,
  "numTradeStatistics": 84213,
  "priceFeedAvailable": true,
  "uptimeSeconds": 5403,
  "version": "1.0.0",
  "timestamp": 1758790000000
}
```

`GET /api/v1/orderbook/EUR?depth=2`
```json
{
  "market": "XMR/EUR",
  "counterCurrency": "EUR",
  "currencyType": "fiat",
  "indexPrice": 151.0,
  "bids": [
    {"price": 150.2, "amountXmr": 2.5, "offerCount": 1, "cumulativeXmr": 2.5},
    {"price": 149.0, "amountXmr": 1.0, "offerCount": 2, "cumulativeXmr": 3.5}
  ],
  "asks": [
    {"price": 151.8, "amountXmr": 3.0, "offerCount": 1, "cumulativeXmr": 3.0},
    {"price": 152.5, "amountXmr": 0.5, "offerCount": 1, "cumulativeXmr": 3.5}
  ],
  "unpricedOfferCount": 0,
  "timestamp": 1758790000000
}
```

`GET /api/v1/markets` → array of `MarketSummary` (best bid/ask, spread, liquidity per side,
unique makers, index price, last trade price, 24h volume/count). See the OpenAPI schema for
every field.

### Notes for agent integration

- **Discover the shape at runtime:** fetch `GET /api/v1/openapi.yaml` and generate a client
  or ground a tool schema from it.
- **Enumerate markets first** via `/markets`, then drill into `/orderbook/{code}` and
  `/trades/{code}`. Never assume a fixed market list — it is whatever is currently live.
- **Handle nullable price fields.** `bestBid`, `bestAsk`, `spread`, `indexPrice`,
  `lastTradePrice`, and offer `price`/`volume` can be `null`.
- **Poll politely.** Data changes on the ~11-minute offer TTL cadence; polling every
  10–30s is plenty. There is no push/streaming endpoint (yet).
- **Privacy:** maker onion addresses are redacted unless `HAVENO_EXPOSE_MAKER_ADDRESS=true`.

---

## Configuration (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `HAVENO_REST_HOST` | `0.0.0.0` | REST bind interface. |
| `HAVENO_REST_PORT` | `8080` | REST port. |
| `HAVENO_BASE_CURRENCY_NETWORK` | `XMR_MAINNET` | `XMR_MAINNET` / `XMR_STAGENET` / `XMR_LOCAL`. |
| `HAVENO_XMR_NODE` | — | monerod URI for reserve-spent validation. |
| `HAVENO_APP_DATA_DIR` | `/data` | P2P/Tor data directory (persist in k8s). |
| `HAVENO_EXPOSE_MAKER_ADDRESS` | `false` | Include maker onion address in `/offers`. |
| `HAVENO_MAX_TRADES` | `5000` | Cap on `/trades` rows. |
| `HAVENO_HTTP_THREADS` | `8` | REST server worker threads. |
| `HAVENO_USE_LOCALHOST_FOR_P2P` | — | `true` for local dev networks. |

Advanced Haveno flags can be appended after the mapped ones (the container passes extra
args verbatim), e.g. `--seedNodes=...`, `--useTorForXmr=off`.

---

## Deployment (Kubernetes)

Manifests are in [`k8s/`](k8s/): `configmap.yaml`, `deployment.yaml` (+ PVC), `service.yaml`.

- **Mainnet needs Tor egress.** Tor is embedded in the app (no sidecar), but the pod must
  be allowed outbound network access to reach Tor relays; otherwise P2P bootstrap fails.
- **monerod:** point `HAVENO_XMR_NODE` at an external mainnet node or run the commented-out
  sidecar in `deployment.yaml`.
- **Storage:** mount a PVC at `/data` so the P2P store and Tor identity survive restarts.
- **Exposure:** the API is unauthenticated; front it with an ingress that adds TLS + auth.

See [`docs/orderbook-api-feasibility.md`](../docs/orderbook-api-feasibility.md) for the
feasibility study and the full mainnet soak-test runbook.

---

## Verification

- **Unit tests** (`./gradlew :orderbook:test`) cover the aggregation logic deterministically.
- **Local soak** (no Tor, no funds, fully offline): [`scripts/soak-test.sh`](scripts/soak-test.sh)
  drives the API and asserts it stays healthy and serving. Bring-up steps for the local
  Haveno network are in the feasibility doc's runbook.
