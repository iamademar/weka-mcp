# Azure deployment (Container Apps + Bicep)

Deploys the standalone WEKA-over-MCP stack to **Azure Container Apps** as infra-as-code.

```
ACR ─▶ managed environment ─▶ Azure Files ─▶ weka-api (internal) ─▶ weka-mcp (public)
```

- **weka-api** — **internal-only** ingress (no public DNS). Reachable only by weka-mcp inside the
  environment. **Scale-to-zero** (min 0 / max 1): ~$0 idle compute; first call after idle absorbs a
  JVM cold start under weka-mcp's 210s timeout. Azure Files backs `/app/models` + `/app/data`.
- **weka-mcp** — **public** ingress. Reaches weka-api over the environment-internal FQDN.

### Two auth modes (`OPEN_DEMO`)

`deploy.sh` runs in **open demo mode by default** (`OPEN_DEMO=true`):

- **Open demo (default)** — no auth. `INTERNAL_AUTH_SHARED_SECRET` is left unset, so
  `requireInternalAuth` (weka-mcp/src/http.ts) skips the check and **anyone can call `POST /mcp`
  with no header** — this is what lets ChatGPT / Claude connect with zero setup. The shared volume
  is a **disposable public sandbox** (any visitor can upload/train/delete). Cost is bounded (below).
- **Secured** (`OPEN_DEMO=false`) — `POST /mcp` requires the `X-Internal-Auth: <secret>` header.
  Great for Claude Desktop / curl, but ChatGPT's connector UI can't send a custom header.

Either way, weka-api is **never public** — only weka-mcp is exposed.

### Cost controls (bounded public demo)

- **Scale-to-zero + hard replica cap** — both apps `minReplicas: 0`; `maxReplicas: 1` each. Idle
  cost ≈ $10/mo (storage + ACR). The `maxReplicas` cap is the real ceiling: even under a flood you
  never pay for more than one replica per app. An HTTP scale rule (`concurrentRequests: 20`) only
  spins a replica up under load, then floors back to zero.
- **Budget alert** — `modules/budget.bicep` provisions a subscription-scoped **$25/mo** cost budget
  (default) filtered to this resource group, emailing you at 80% and 100%. It *alerts*, it does not
  hard-stop spend — the `maxReplicas` cap is what actually bounds compute.
- **Small upload cap** — `MAX_UPLOAD_MB=20` on weka-api so nobody bloats the shared volume.

## Layout

```
main.bicep                 orchestrator (two-pass: deployApps false→true)
main.parameters.json       non-secret defaults
modules/
  acr.bicep                        Basic ACR (admin creds for pulls)
  environment.bicep                Log Analytics + managed environment
  storage.bicep                    storage account + Azure Files share + env storage link
  container-app-weka-api.bicep     internal-only app, Files mount, heap opt, min0/max1
  container-app-weka-mcp.bicep     public app, optional shared-secret auth, scale rule, min0/max1
  budget.bicep                     subscription-scoped monthly cost alert (email)
  managed-certificate.bicep        free Azure-managed TLS cert for a custom domain (phase B)
scripts/
  build-push.sh            az acr build both images
  deploy.sh                RG → ACR → build → full stack (+ budget); prints the mcp URL
  verify-cloud.sh          health + train→predict smoke (auth header optional)
  bind-custom-domain.sh    two-phase custom-domain bind (DNS + managed cert)
```

## Prerequisites

- `az login` (Azure CLI ≥ 2.50).
- The `containerapp` CLI extension — auto-installed on first use, or `az extension add -n containerapp`.
- No local Docker needed: images build in the cloud via `az acr build`.

## Deploy

Open demo (default — no auth, zero-setup for ChatGPT/Claude):

```bash
cd infrastructure
AZURE_RESOURCE_GROUP=weka-mcp-rg AZURE_LOCATION=eastus2 AZURE_NAME_PREFIX=wekamcp \
  ./scripts/deploy.sh
```

Secured (require the `X-Internal-Auth` header; `deploy.sh` generates + prints the secret once):

```bash
OPEN_DEMO=false AZURE_RESOURCE_GROUP=weka-mcp-rg ./scripts/deploy.sh
```

Tune the cost alert with `BUDGET_AMOUNT` (default 25; `0` disables) and `BUDGET_EMAIL` (defaults to
the signed-in user). On success `deploy.sh` prints the public weka-mcp URL and its `/mcp` endpoint.

## Verify

```bash
# open demo — no secret needed
AZURE_RESOURCE_GROUP=weka-mcp-rg NAME_PREFIX=wekamcp ./scripts/verify-cloud.sh

# secured — pass the secret so the header is sent
AZURE_RESOURCE_GROUP=weka-mcp-rg NAME_PREFIX=wekamcp \
INTERNAL_AUTH_SHARED_SECRET=<the-secret> ./scripts/verify-cloud.sh
```

Runs health → upload iris → train J48 → predict, and asserts the response contains **`Iris-setosa`**.
The first call wakes weka-api from scale-to-zero (~10–30s JVM cold start), so allow a beat.

## Connect a client

The MCP endpoint is `https://<mcp-fqdn>/mcp` (JSON-RPC MCP over Streamable HTTP). In **open demo
mode** no header is needed:

- **ChatGPT** (Plus/Pro/Business → Settings → Connectors / Developer Mode → add custom MCP connector):
  paste the `/mcp` URL. No auth field required.
- **claude.ai** (Settings → Connectors → add custom connector): paste the `/mcp` URL.
- **Claude Desktop** (stdio client → bridge to the remote endpoint):
  ```json
  {
    "mcpServers": {
      "weka": { "command": "npx", "args": ["mcp-remote", "https://<mcp-fqdn>/mcp"] }
    }
  }
  ```
  In **secured** mode add `"--header", "X-Internal-Auth:<secret>"` to the `args`.
- **curl** (smoke test):
  ```bash
  curl -s https://<mcp-fqdn>/mcp \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
  # secured mode: add  -H "X-Internal-Auth: <secret>"
  ```

## Custom domain (optional)

By default weka-mcp is reachable only at its Azure FQDN (`<prefix>-weka-mcp.<region>.azurecontainerapps.io`).
To serve it at your own hostname (e.g. `weka-mcp.example.com`) with a **free, auto-renewed
Azure-managed TLS cert**, the deploy is **two-phase** — a managed cert can only be issued after the
domain's DNS validation records resolve, and the `SniEnabled` binding needs that cert to exist:

```bash
cd infrastructure
MCP_CUSTOM_DOMAIN=weka-mcp.example.com AZURE_RESOURCE_GROUP=weka-mcp-rg \
AZURE_NAME_PREFIX=wekamcp AZURE_LOCATION=eastus2 \
  ./scripts/bind-custom-domain.sh
```

`bind-custom-domain.sh` runs **phase A** (binds the hostname cert-less so Azure accepts the domain
and prints the two DNS records to create), waits for the `asuid.<sub>` TXT to resolve, then runs
**phase B** (issues the managed cert and binds it `SniEnabled`). Create the printed records at your
DNS provider when prompted:

| Type  | Name           | Value                                     |
|-------|----------------|-------------------------------------------|
| TXT   | `asuid.<sub>`  | the app's `customDomainVerificationId`    |
| CNAME | `<sub>`        | the app's default `*.azurecontainerapps.io` FQDN |

To run the phases yourself (e.g. DNS lives elsewhere and you'll add records manually), set
`PHASE=a` then `PHASE=b`, or drive it straight from `main.bicep` with the two params:
`mcpCustomDomainHostname=<host>` + `mcpCustomDomainIssueCert=false` (phase A) then `=true` (phase B).
These params default empty/false, so a normal `deploy.sh` is unaffected — but once you set them,
**pass them on every subsequent deploy** so a full-template apply doesn't drop the binding. Apex
(root) domains need an A record to the environment's static IP instead of a CNAME; set
`domainControlValidation: 'HTTP'` in `managed-certificate.bicep` for that case.

## Design notes

- **`WEKA_API_URL` in the cloud is `https://<api-internal-fqdn>` with no `:7070`** — internal ingress
  terminates TLS on 443 and maps to weka-api's targetPort 7070. (Locally it's `http://weka-api:7070`.)
  The Bicep builds this from the api module's `ingress.fqdn` output; don't hardcode it.
- **Two-pass deploy**: the app resources reference image tags that must already exist, so ACR is
  provisioned first, images pushed, then the apps (`deployApps` flag flips false→true).
- **CPU/memory**: weka-api defaults to 2 vCPU / 4Gi (WEKA is heap-hungry); bump `apiMemory`/`apiCpu`
  in `main.parameters.json` for larger datasets (valid Container Apps ratios only, e.g. 4.0 → 8Gi).
- **Timeouts**: `WEKA_API_TIMEOUT_MS=210000` deliberately sits below the ~240s Container Apps Envoy
  ceiling. Training that needs longer than ~240s can't complete over ingress — that would need an
  async/job pattern (out of scope).
- **Scale**: weka-api is pinned to max 1 replica (filesystem-based model persistence; single writer).
  weka-mcp is stateless and scales 0→3.

## Tear down

```bash
az group delete --name weka-mcp-rg --yes --no-wait
```
