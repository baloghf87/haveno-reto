#!/usr/bin/env bash
#
# Translates a handful of environment variables into Haveno CLI flags, then execs the
# orderbook node. Any additional arguments passed to the container are appended verbatim,
# so advanced flags (e.g. --seedNodes, --useTorForXmr, --xmrNodeUsername) still work.
#
set -euo pipefail

ARGS=()
ARGS+=("--baseCurrencyNetwork=${HAVENO_BASE_CURRENCY_NETWORK:-XMR_MAINNET}")
ARGS+=("--appDataDir=${HAVENO_APP_DATA_DIR:-/data}")

# Observer node: never start a native wallet.
ARGS+=("--useNativeXmrWallet=false")

if [[ -n "${HAVENO_XMR_NODE:-}" ]]; then
  ARGS+=("--xmrNode=${HAVENO_XMR_NODE}")
fi
if [[ -n "${HAVENO_XMR_NODE_USERNAME:-}" ]]; then
  ARGS+=("--xmrNodeUsername=${HAVENO_XMR_NODE_USERNAME}")
fi
if [[ -n "${HAVENO_XMR_NODE_PASSWORD:-}" ]]; then
  ARGS+=("--xmrNodePassword=${HAVENO_XMR_NODE_PASSWORD}")
fi
if [[ -n "${HAVENO_USE_LOCALHOST_FOR_P2P:-}" ]]; then
  ARGS+=("--useLocalhostForP2P=${HAVENO_USE_LOCALHOST_FOR_P2P}")
fi

# JAVA_OPTS is honored by the generated start script via JAVA_OPTS env var.
exec /app/bin/haveno-orderbook "${ARGS[@]}" "$@"
