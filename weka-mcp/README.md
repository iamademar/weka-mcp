# Weka MCP server

A [Model Context Protocol](https://modelcontextprotocol.io) server in TypeScript that wraps the
[Weka REST API](../README.md) so AI agents (Claude Desktop, Claude Code, or any MCP client) can
drive Weka — upload data, train/evaluate classifiers, cluster, mine association rules, run the
experimenter, and pull diagnostics — through typed tools instead of hand-written `curl`.

It is a thin, typed client over the existing HTTP API; it makes **no** changes to the Java backend.

- **46 tools** covering every endpoint group (see [Tools](#tools)).
- **Phase 1 (this):** local **stdio** server you run against a local Weka API.
- **Phase 2 (scaffolded):** an HTTP transport (`src/http.ts`) for deploying on a server so many
  clients can connect, plus a path toward an OpenAPI surface — see [Deployment roadmap](#deployment-roadmap).

---

## Prerequisites

- **Node.js ≥ 20** (uses global `fetch`/`FormData`; Node 22 recommended).
- A **running Weka REST API**. From the repo root:
  ```bash
  docker compose up --build -d
  curl http://localhost:7070/health   # {"status":"ok","wekaVersion":"3.9.6"}
  ```

## Install & build

```bash
cd weka-mcp
npm install
npm run build      # compiles src/ -> dist/
```

Configuration is a single env var (default shown):

```bash
WEKA_API_URL=http://localhost:7070
```

---

## Testing

### Smoke test (no Weka API needed)

Asserts the server builds and registers all expected tools, each with a description.

```bash
npm run smoke
# -> smoke OK: 46 tools registered, all described
```

### Live end-to-end test (needs the Weka API running)

Drives `health → upload iris → train J48 → predict → 10-fold CV evaluate` **through the MCP tools**
(in-memory MCP client). Skips gracefully if the API is unreachable.

```bash
docker compose up -d        # from repo root, if not already running
cd weka-mcp && npm run test:live
# -> test:live OK — health -> upload -> train -> predict -> evaluate through MCP tools
```

---

## Manual testing

### Option A — MCP Inspector (fastest visual check)

The Inspector is a browser UI that lists the tools and lets you call them with a form.

```bash
cd weka-mcp
npm run build
WEKA_API_URL=http://localhost:7070 npx @modelcontextprotocol/inspector node dist/index.js
```

It prints a local URL. Open it, and you'll see all 46 tools. Try, in order:

1. `weka_health` (no args) → confirms the backend.
2. `weka_upload_dataset` → `filePath`: an absolute path to `weka-api/src/test/resources/iris.arff`,
   `name`: `iris`.
3. `weka_train` → `dataset`: `iris`, `algorithm`: `weka.classifiers.trees.J48`, `modelName`: `iris-j48`.
4. `weka_predict` → `model`: `iris-j48`, `instances`:
   `[{"sepallength":5.1,"sepalwidth":3.5,"petallength":1.4,"petalwidth":0.2}]`.
5. `weka_evaluate` → `model`: `iris-j48`, `dataset`: `iris`, `method`: `cross_validation`, `folds`: `10`.

### Option B — Claude Code (real client)

Register the server (use an **absolute** path to `dist/index.js`):

```bash
# from anywhere, once the Weka API is running and `npm run build` has been done
claude mcp add weka \
  --env WEKA_API_URL=http://localhost:7070 \
  -- node "/ABSOLUTE/PATH/TO/weka-over-mcp/weka-mcp/dist/index.js"

claude mcp list           # should show "weka" connected
```

Then in a Claude Code session, ask in natural language — Claude picks the tools:

- "Using the weka tools, list my datasets."
- "Upload `weka-api/src/test/resources/iris.arff` as `iris`, train a J48, and evaluate it with 10-fold cross-validation."
- "Train SimpleKMeans with 3 clusters on iris and show the classes-to-clusters breakdown."

Remove it with `claude mcp remove weka`.

### Option C — Claude Desktop

Add to `claude_desktop_config.json`
(macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "weka": {
      "command": "node",
      "args": ["/ABSOLUTE/PATH/TO/weka-over-mcp/weka-mcp/dist/index.js"],
      "env": { "WEKA_API_URL": "http://localhost:7070" }
    }
  }
}
```

Restart Claude Desktop; the Weka tools appear in the tools menu.

---

## Tools

| Group | Tools |
|---|---|
| Health / discovery | `weka_health`, `weka_list_algorithms`, `weka_list_clusterers`, `weka_list_associators`, `weka_list_as_evaluators`, `weka_list_as_searches`, `weka_list_filters`, `weka_filter_metadata` |
| Datasets | `weka_list_datasets`, `weka_get_dataset`, `weka_upload_dataset`, `weka_delete_dataset` |
| EDA | `weka_attribute_stats`, `weka_dataset_summary`, `weka_histogram`, `weka_scatter`, `weka_scatter_matrix` |
| Transform | `weka_transform`, `weka_transform_preview` |
| Train / predict / evaluate | `weka_train`, `weka_train_search`, `weka_train_update`, `weka_predict`, `weka_predict_dataset`, `weka_evaluate`, `weka_learning_curve` |
| Models | `weka_list_models`, `weka_get_model`, `weka_delete_model`, `weka_model_tree`, `weka_model_graph`, `weka_download_model`, `weka_import_model` |
| Cluster / select / associate / experiment | `weka_cluster_train`, `weka_cluster_assign`, `weka_cluster_evaluate`, `weka_attribute_selection`, `weka_associate`, `weka_experiment` |
| Diagnostics | `weka_diag_errors`, `weka_diag_threshold_curve`, `weka_diag_margin_curve`, `weka_diag_cost_curve`, `weka_diag_calibration`, `weka_diag_residuals`, `weka_diag_pr_curve` |

Errors from the Weka API are returned to the agent as an error result carrying the API's own code
and message (e.g. `Weka API error 422 REQUIRES_NOMINAL: attribute 'sepallength' is numeric ...`).

---

## How it's wired

```
src/
  index.ts     stdio entrypoint -> StdioServerTransport
  http.ts      Phase 2: StreamableHTTPServerTransport on Express /mcp
  server.ts    createServer(client): registers every tool (single source of truth)
  client.ts    WekaClient: typed fetch wrapper (JSON + multipart upload/import + download)
  config.ts    reads WEKA_API_URL
  tools/*.ts    one module per endpoint group; each exports register(server, client)
```

Both transports import the same `createServer()`, so the stdio server and the deployed HTTP server
expose the identical tool surface.

---

## Deployment roadmap

The goal is eventually to let **everyone** access this on a server, as both an AI tool surface and a
plain HTTP/OpenAPI app.

1. **HTTP MCP transport (scaffolded).** `src/http.ts` serves MCP over Streamable HTTP at `POST /mcp`
   using the same tools. Build and run:
   ```bash
   npm run build && node dist/http.js   # listens on MCP_HTTP_PORT (default 3000)
   ```
   Before exposing publicly, add authentication, CORS, and rate limiting (the bundled SDK ships
   helpers for these).
2. **OpenAPI app.** Each tool's zod input schema already fully describes an operation. These can be
   projected to an OpenAPI document and served as ordinary REST endpoints alongside MCP, giving
   browsers/apps/`curl` access without the MCP protocol. (Not implemented yet.)

> Note: this MCP server inherits the backend's posture — the Weka API is single-user, no-auth, local
> dev. Add auth at both layers before any shared/remote deployment.
