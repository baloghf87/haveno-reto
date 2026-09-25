# syntax=docker/dockerfile:1
#
# Runtime image for the Haveno orderbook observer node.
#
# The Haveno application is built on the host (it needs jitpack.io + the Monero binary
# download, which are awkward inside an isolated build container), then packaged here:
#
#   ./gradlew :orderbook:installDist       # produces orderbook/build/app/{bin,lib}
#   docker build -t haveno-orderbook:latest orderbook
#
# The image bundles Tor via Haveno's networking layer, so for XMR_MAINNET the pod only
# needs outbound network egress (no Tor sidecar). It still needs a reachable monerod
# (external node or sidecar) for full reserve-spent offer validation.

FROM eclipse-temurin:21-jre

# Non-root runtime user
RUN groupadd -r haveno && useradd -r -g haveno -m -d /home/haveno haveno \
    && mkdir -p /data && chown -R haveno:haveno /data

WORKDIR /app

# The pre-built, self-contained distribution (bin/haveno-orderbook + lib/*.jar)
COPY build/app/ /app/
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh /app/bin/haveno-orderbook

# REST API defaults (override via env in k8s)
ENV HAVENO_REST_HOST=0.0.0.0 \
    HAVENO_REST_PORT=8080 \
    HAVENO_BASE_CURRENCY_NETWORK=XMR_MAINNET \
    HAVENO_APP_DATA_DIR=/data \
    JAVA_OPTS="-XX:MaxRAM=2g"

EXPOSE 8080
VOLUME ["/data"]
USER haveno

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
