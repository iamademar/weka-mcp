# WEKA-MCP

What is this? It's basically the [WEKA](https://ml.cms.waikato.ac.nz/) the machine-learning
library that allows you to upload data, train/evaluate classifiers, cluster, mine association rules, 
run diagnostics but served by your favor LLM through **46 typed [Model Context Protocol](https://modelcontextprotocol.io) tools**.

<img width="636" height="432" alt="weka-mcp" src="https://github.com/user-attachments/assets/da3e8bcf-d508-416b-a883-cc1f3083429e" />

Here’s the high-level architecture:

```
  MCP client ──▶ weka-mcp ──▶ weka-api ──▶ WEKA library
               (:3000 /mcp)  (:7070 REST)   (real ML)
                  or stdio
```

- **weka-api** — Java 17 / Javalin REST service wrapping WEKA `3.9.6`. Does the real ML. Persists
  models to `/app/models` and datasets to `/app/data`. Listens on **7070**.
- **weka-mcp** — TypeScript/Node MCP server. A thin, typed forwarder — **no ML logic**; it translates
  each MCP tool call into a weka-api REST call. Supports **HTTP** (`POST /mcp`) and **stdio** transports.
  Finds weka-api via `WEKA_API_URL`.

---

## Run it locally

Prerequisites: **Docker** + **Docker Compose v2**. (For stdio dev outside Docker: **Node ≥ 20**.)

```bash
bin/setup     # build + start; auto-selects free host ports, then waits for health
```

`bin/setup` is the one-command install: it probes for free host ports (starting at **3001** for
weka-mcp and **7070** for weka-api), records them in a generated `.env`, runs `docker compose up
--build -d`, and waits until weka-mcp reports healthy. It prints the endpoints when done. The plain
`docker compose up --build -d` still works if you'd rather manage ports yourself (defaults / `.env`).

weka-api starts first; weka-mcp waits until weka-api reports healthy (`depends_on` +
[healthcheck](compose.yaml)). Both services publish a host port locally; the internal mcp→api wiring
stays on the service name (`weka-api:7070`), mirroring the cloud shape where weka-api is internal-only.

Check it's up (`bin/setup` prints the exact ports it chose):

```bash
curl -s localhost:3001/healthz
# {"ok":true,"wekaApiUrl":"http://weka-api:7070"}
```

Tear down:

```bash
bin/remove                # down -v (wipes model/data volumes), removes images, frees the ports
# or, manually:
docker compose down       # stop containers, keep model/data volumes
docker compose down -v    # also wipe the weka-models + weka-data volumes
```

---

## Connecting an MCP client

### HTTP transport (the deployed shape)

Point any MCP-over-HTTP client at `http://localhost:3001/mcp`. Locally the auth check is skipped. The
cloud deployment can run **open** (no header — so ChatGPT / claude.ai / Claude Desktop connect with
zero setup) or **secured** (requires an `X-Internal-Auth: <secret>` header); see
[`infrastructure/README.md`](infrastructure/README.md) → *Connect a client*.

### stdio transport (Claude Desktop / Claude Code)

Desktop clients speak MCP over **stdio**. Build once, then run `dist/index.js` with `WEKA_API_URL`
pointed at a running weka-api:

```bash
cd weka-mcp && npm ci && npm run build
```

Claude Code:

```bash
claude mcp add weka \
  --env WEKA_API_URL=http://localhost:7070 \
  -- node "/ABSOLUTE/PATH/TO/weka-mcp-core/weka-mcp/dist/index.js"
```

Claude Desktop (`claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "weka": {
      "command": "node",
      "args": ["/ABSOLUTE/PATH/TO/weka-mcp-core/weka-mcp/dist/index.js"],
      "env": { "WEKA_API_URL": "http://localhost:7070" }
    }
  }
}
```

> stdio needs weka-api reachable at `WEKA_API_URL`. The compose stack does **not** publish weka-api to
> the host; for stdio dev either run weka-api directly (`cd weka-api && mvn -q package -DskipTests &&
> PORT=7070 java -jar target/weka-api-*-shaded.jar`) or temporarily add `ports: ["7070:7070"]` to the
> weka-api service in `compose.yaml`.

---

## Environment variables

**weka-api** (read in `weka-api/src/main/java/com/wekaapi/config/Config.java`):

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `7070` | HTTP port Javalin binds |
| `MODELS_DIR` | `/app/models` | serialized `.model` files |
| `DATA_DIR` | `/app/data` | uploaded ARFF/CSV datasets |
| `MAX_UPLOAD_MB` | `100` | upload size cap (MB) |
| `LOG_LEVEL` | `INFO` | Logback root level |
| `JAVA_TOOL_OPTIONS` | *(unset)* | JVM flags; compose sets `-XX:MaxRAMPercentage=75.0` under the container cap |

**weka-mcp** (read in `weka-mcp/src/config.ts` and `client.ts`):

| Variable | Default | Purpose |
|---|---|---|
| `WEKA_API_URL` | `http://localhost:7070` | base URL of weka-api (compose: `http://weka-api:7070`) |
| `MCP_HTTP_PORT` | `3000` | port the HTTP transport listens on (`POST /mcp`) |
| `WEKA_API_TIMEOUT_MS` | `210000` | per-request timeout to weka-api (kept < ~240s Envoy ceiling) |
| `INTERNAL_AUTH_SHARED_SECRET` | *(unset)* | if set, `POST /mcp` requires header `X-Internal-Auth: <secret>`; unset ⇒ check skipped |

`MCP_HOST_PORT` (default `3001`) is a compose-only convenience that sets the **host** port published for
weka-mcp; the container still listens on `MCP_HTTP_PORT` (3000). See [`.env.example`](.env.example).

---

## The tool surface (46 tools, all `weka_`-prefixed)

| Group | Tools |
|---|---|
| Health / discovery | `weka_health`, `weka_list_algorithms`, `weka_list_clusterers`, `weka_list_associators`, `weka_list_as_evaluators`, `weka_list_as_searches`, `weka_list_filters`, `weka_filter_metadata` |
| Datasets | `weka_list_datasets`, `weka_get_dataset`, `weka_upload_dataset`, `weka_delete_dataset` |
| EDA | `weka_attribute_stats`, `weka_dataset_summary`, `weka_histogram`, `weka_scatter`, `weka_scatter_matrix` |
| Transform | `weka_transform`, `weka_transform_preview`, `weka_attribute_selection` |
| Train / predict / evaluate | `weka_train`, `weka_train_search`, `weka_train_update`, `weka_predict`, `weka_predict_dataset`, `weka_evaluate`, `weka_experiment`, `weka_learning_curve` |
| Models | `weka_list_models`, `weka_get_model`, `weka_delete_model`, `weka_model_tree`, `weka_model_graph`, `weka_download_model`, `weka_import_model` |
| Clustering | `weka_cluster_train`, `weka_cluster_assign`, `weka_cluster_evaluate` |
| Association | `weka_associate` |
| Diagnostics | `weka_diag_errors`, `weka_diag_threshold_curve`, `weka_diag_margin_curve`, `weka_diag_cost_curve`, `weka_diag_calibration`, `weka_diag_residuals`, `weka_diag_pr_curve` |

Run `cd weka-mcp && npm run smoke` to assert all 46 register with descriptions (no API needed). The
per-service READMEs — [`weka-mcp/README.md`](weka-mcp/README.md) and [`WEKA-ORIGINAL-README.md`](WEKA-ORIGINAL-README.md)
(the REST API contract) — go deeper on individual tools/endpoints.

---

## Deploy to Azure

Container Apps + Bicep, as infra-as-code, lives in [`infrastructure/`](infrastructure/): weka-api gets
**internal-only** ingress (never public); weka-mcp gets **public** ingress — either **open** for a
zero-setup public demo or **secured** behind the `X-Internal-Auth` header. Cost is bounded by
scale-to-zero + a hard `maxReplicas` cap + a monthly budget alert, and an Azure Files share backs the
models/data volumes. See [`infrastructure/README.md`](infrastructure/README.md) for deploy steps and
how to connect ChatGPT / Claude.
