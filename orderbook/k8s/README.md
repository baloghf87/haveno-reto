# Deploying the Haveno Orderbook API to k3s

k3s runs **containerd**, not Docker, so a locally built Docker image isn't visible to k3s
until you import it into containerd (or push it to a registry). These steps cover the
import path (no registry needed).

Prerequisites on your build machine: JDK 21, Docker, and `kubectl` pointed at your k3s
cluster. Run everything from the repo root.

## 1. Build the app distribution

The Dockerfile packages a pre-built distribution, so build it first:

```bash
./gradlew :orderbook:installDist
```

This produces `orderbook/build/app/{bin,lib}`, which the Dockerfile copies in.

## 2. Build the image

```bash
docker build -t haveno-orderbook:latest orderbook
```

## 3. Import the image into k3s (containerd)

```bash
docker save haveno-orderbook:latest -o /tmp/haveno-orderbook.tar
sudo k3s ctr images import /tmp/haveno-orderbook.tar
sudo k3s ctr images ls | grep haveno-orderbook   # verify: docker.io/library/haveno-orderbook:latest
```

The manifests set `imagePullPolicy: IfNotPresent`, so k3s uses the imported image and
never tries to pull from a registry. (Multi-node cluster? Import on **every** node, or
use a registry instead — see the bottom.)

## 4. Configure the Monero node (required for mainnet)

Edit `orderbook/k8s/configmap.yaml` and set `HAVENO_XMR_NODE` to a reachable **mainnet**
monerod RPC. Either:
- point it at an existing node you run (e.g. `http://<host>:18081`), or
- a public remote node, or
- run the monerod sidecar (uncomment the `monerod` container + `monero-data` PVC in
  `deployment.yaml`, and keep `HAVENO_XMR_NODE: "http://127.0.0.1:18081"`).

## 5. Deploy

```bash
kubectl apply -f orderbook/k8s/configmap.yaml
kubectl apply -f orderbook/k8s/deployment.yaml
kubectl apply -f orderbook/k8s/service.yaml
kubectl rollout status deploy/haveno-orderbook
```

## 6. Wait for bootstrap, then query

Mainnet startup builds a Tor circuit and syncs the P2P data store — this takes **several
minutes**. Watch the logs:

```bash
kubectl logs -f deploy/haveno-orderbook
# look for: "Orderbook REST API listening ...", then Tor start, then onDataReceived
```

Then reach the API (ClusterIP service, port 80 → container 8080):

```bash
kubectl port-forward svc/haveno-orderbook 8080:80
# in another shell:
curl -s localhost:8080/api/v1/health | jq      # status UP, bootstrapped true
curl -s localhost:8080/api/v1/markets | jq
curl -s localhost:8080/api/v1/orderbook/USD | jq
```

Readiness/liveness probes hit `/api/v1/health`; the pod won't report Ready until the HTTP
server is up (it returns 200 while `bootstrapped:false` during Tor bring-up, so the pod
becomes Ready before P2P data has fully synced — check `bootstrapped` in the body for true
readiness).

## Networking notes (important)

- **Tor egress:** mainnet reaches seed nodes over Tor, which needs ordinary outbound TCP.
  A home k3s node normally has this. If you run a restrictive `NetworkPolicy`, allow the
  pod egress.
- **Exposure:** the API is read-only and unauthenticated. For access beyond the cluster,
  expose it via a Traefik `Ingress` (k3s ships Traefik) or a `NodePort`, and terminate TLS
  + add auth at that layer. Example NodePort: change the Service `type` to `NodePort`.
- **Storage:** the `/data` PVC persists the P2P store and Tor identity across restarts, so
  re-bootstraps are fast. Ensure your cluster has a default StorageClass
  (`kubectl get sc`); k3s ships `local-path` by default.

## Updating the image

After code changes, repeat steps 1–3 with a new tag (e.g. `:v2`), update the `image:` in
`deployment.yaml`, and `kubectl apply` + `kubectl rollout restart deploy/haveno-orderbook`.
Using a fresh tag (not reusing `:latest`) avoids stale-image confusion with `IfNotPresent`.

## Alternative: use a registry instead of importing

If you prefer a registry (e.g. a local registry or GHCR):

```bash
docker tag haveno-orderbook:latest <registry>/haveno-orderbook:latest
docker push <registry>/haveno-orderbook:latest
# set image: <registry>/haveno-orderbook:latest in deployment.yaml
# (add imagePullSecrets if the registry is private)
```
