# Feasibility Study: Network-Wide Orderbook & Liquidity API for Haveno

## Summary

**Goal.** Extract liquidity information from the Haveno network — derive an orderbook for
every market plus supporting liquidity/volume data — and expose it over a REST/JSON HTTP
API.

**Verdict: feasible, and implemented in this repository** as the `orderbook` module.
Haveno is a Bisq-style flood-fill P2P network in which every bootstrapped node receives a
**complete replica** of all currently-live offers and of the append-only completed-trade
history. A single passive, wallet-less node therefore holds the full network orderbook in
memory; the only new work is a *neutral* (unfiltered) aggregation layer and a REST
transport.

The service is a wallet-less observer (`haveno.orderbook.OrderbookMain`) built on the same
lightweight P2P bootstrap as the seed/statistics nodes, with an embedded REST API. It adds
**no new third-party dependencies** (JDK `HttpServer` + the already-present Gson).

---

## What data is available

Every market is `XMR/<currency>` (no cross-currency pairs). From the replicated P2P state a
node can expose:

**Live orderbook** — from `OfferBookService.getOffers()` (the full valid offer set), read
*unfiltered* (unlike `CoreOffersService`, which filters to offers takeable by the local
node and hides own offers). Per offer: direction, fixed price or market-margin %, resolved
price, amount/min-amount (range), payment method, maker/taker/penalty fees, buyer/seller
security deposits, trade limits/period, F2F country/city + bank data, private-offer flag,
maker onion address, date.

**Per-market aggregates** — best bid/ask, spread, mid, cumulative depth curves, total
liquidity per side (XMR), unique makers, offer counts, liquidity by payment method, index
price + % on margin pricing.

**Historical volume** — from `TradeStatistics3` via `TradeStatisticsManager`
(whole-network, append-only): last price, 24h volume/high/low, trade counts. Caveat:
completed trades have no direction and publication is delayed up to 24h for privacy.

**Reference prices** — `PriceFeedService` external index prices, required to resolve
market-based (margin) offers into executable prices.

Not available: `isPrivateOffer` offers (shared out-of-band) and purely local `OpenOffer`
state. Offers self-expire after ~11 minutes unless refreshed, so the book is "currently
live," not historical.

---

## Architecture

| Component | File |
|---|---|
| Entry point (wallet-less P2P bootstrap) | `orderbook/.../OrderbookMain.java` |
| Node wiring (pins services, starts REST) | `orderbook/.../OrderbookNode.java` |
| Neutral aggregation (orderbook/markets/trades/prices) | `orderbook/.../OrderbookAggregator.java` |
| REST server (JDK HttpServer + Gson) | `orderbook/.../RestApiServer.java` |
| DTOs (JSON contract) | `orderbook/.../model/Dto.java` |
| Env-driven config | `orderbook/.../OrderbookConfig.java` |
| OpenAPI 3 spec (served at `/api/v1/openapi.yaml`) | `orderbook/src/main/resources/openapi.yaml` |

Reuses `CorePriceService.getMarketDepth()` patterns and the existing services
(`OfferBookService`, `PriceFeedService`, `TradeStatisticsManager`, `P2PService`). Core is
**not modified** — the aggregation lives in the module and reads public core services.

The REST API, endpoints, conventions, and configuration are documented for consumers in
[`orderbook/README.md`](../orderbook/README.md); the machine-readable contract is
`orderbook/src/main/resources/openapi.yaml`.

---

## Deployment

Docker image and k8s manifests: `orderbook/Dockerfile`, `orderbook/k8s/`. The app is built
on the host (`./gradlew :orderbook:installDist`) and packaged into a slim JRE image.

**Mainnet requires Tor egress.** Haveno reaches mainnet seed nodes only over Tor (`.onion`
addresses). Tor is embedded in the app (no sidecar), but the pod must have outbound network
access to reach Tor relays. A reachable mainnet `monerod` (external or sidecar) is needed
for reserve-spent offer validation. Persist `/data` (P2P store + Tor identity) on a PVC.

---

## Verification

### 1. Unit tests (deterministic, offline)

```bash
./gradlew :orderbook:test
```

`OrderbookAggregatorTest` feeds synthetic offers and asserts sorting, cumulative depth,
per-price-level aggregation, unpriced-offer handling, depth limits, and market summaries
(spread/liquidity/makers).

### 2. Self-contained local soak (no Tor, no funds, fully offline)

Uses the repo's `XMR_LOCAL` harness — a local Monero test chain and localhost P2P, so it
exercises the real ingestion → aggregation → REST path with real arbitrator-signed offers
and no external network.

Bring-up (each in its own shell; see `Makefile` and `docs/developer-guide.md` for details):

```bash
make                       # build; downloads .localnet/monerod + monero-wallet-rpc
make monerod1-local        # local Monero node 1 (fixed-difficulty regtest-style)
make monerod2-local        # local Monero node 2
make funding-wallet-local  # funding wallet; mine/generate test XMR to it
make seednode-local        # localhost seed node (port 2002)
make arbitrator-daemon-local   # register the arbitrator (offers are arbitrator-signed)
make user1-daemon-local        # funded maker daemon (gRPC api on :9999)
make user2-daemon-local        # second maker

# Post a few offers across markets via the daemon API (e.g. with haveno-ts or the CLI),
# so the book is non-empty, then start the observer + REST API:
HAVENO_REST_PORT=8080 HAVENO_USE_LOCALHOST_FOR_P2P=true \
  ./haveno-orderbook --baseCurrencyNetwork=XMR_LOCAL --useLocalhostForP2P=true \
  --useNativeXmrWallet=false --appDataDir=/tmp/ob-data --xmrNode=http://localhost:28081

# Soak the API for a few hours, asserting it stays healthy and serving:
REQUIRE_OFFERS=1 orderbook/scripts/soak-test.sh http://localhost:8080 10800 30
```

Acceptance: `soak-test.sh` prints `PASS` — continuous successful polls for the full
duration, `status=UP`, `bootstrapped=true`, and offers observed after the grace period.
Cross-check `/orderbook/{market}` against the posted offers.

### 3. Mainnet soak runbook (run where Tor egress is available, e.g. your k8s)

> Haveno mainnet is only reachable over Tor. This step must run in an environment that
> permits Tor egress (a normal k8s cluster does; a TLS-intercepting egress proxy does not).

```bash
# Build and push the image to your registry
./gradlew :orderbook:installDist
docker build -t <registry>/haveno-orderbook:latest orderbook
docker push <registry>/haveno-orderbook:latest

# Deploy (edit image + HAVENO_XMR_NODE in k8s/ first)
kubectl apply -f orderbook/k8s/configmap.yaml
kubectl apply -f orderbook/k8s/deployment.yaml
kubectl apply -f orderbook/k8s/service.yaml

# Wait for bootstrap (Tor + P2P data sync can take several minutes), then soak:
kubectl port-forward svc/haveno-orderbook 8080:80 &
REQUIRE_OFFERS=1 GRACE_SECONDS=1200 orderbook/scripts/soak-test.sh http://localhost:8080 14400 30
```

Acceptance criteria:
- `/health` → `status=UP`, `bootstrapped=true`, stable for the full run (no restarts).
- `/markets` returns multiple `XMR/*` markets with plausible spreads (sanity-check a
  couple against the Haveno desktop GUI market view).
- `/orderbook/{market}` shows bid/ask levels with monotonic cumulative depth.
- `/trades/{market}` returns historical trades; `/prices` returns index prices.
- `soak-test.sh` prints `PASS`.

---

## Risks & notes

- **Tor dependency** is the key operational constraint for mainnet (see Deployment).
- **Offer TTL (~11 min):** the book is live offers, not history; document liveness
  semantics to consumers.
- **Margin offers** need a healthy price feed (stale >30 min ⇒ price treated as unavailable
  and the offer is counted, not listed on a level).
- **monerod dependency** for reserve-spent validation (no wallet/funds required).
- **Privacy:** maker onion address is public on the wire but redacted in the API by default
  (`HAVENO_EXPOSE_MAKER_ADDRESS=false`); trade-stats volume lags real time (≤24h).
- **Scale:** `/trades` is capped (`HAVENO_MAX_TRADES`); add pagination if larger history
  exports are needed.
- **No new dependencies:** JDK `HttpServer` + existing Gson, so Gradle dependency
  verification metadata is unchanged.
