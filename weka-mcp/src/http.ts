#!/usr/bin/env node
import express from "express";
import type { NextFunction, Request, Response } from "express";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { WekaClient } from "./client.js";
import { loadConfig } from "./config.js";
import { rateLimit } from "./rateLimit.js";
import { createServer } from "./server.js";

/**
 * PHASE 2 — HTTP transport for remote/shared deployment.
 *
 * This reuses the exact same `createServer()` tool registrations as the stdio
 * entrypoint, so deploying on a server exposes the identical tool surface to
 * MCP-over-HTTP clients.
 *
 * Deployment access restrictions (master §10.6): the primary control is
 * Container Apps internal-only ingress (infrastructure/modules/
 * container-app-weka-mcp.bicep) — this app has no public DNS entry at all.
 * On top of that, as defense in depth: `/mcp` requires a shared secret
 * FastAPI attaches as `X-Internal-Auth` (undefined/skipped when
 * INTERNAL_AUTH_SHARED_SECRET isn't set, i.e. local/compose dev), and a
 * lightweight per-IP rate limit backstops a compromised or bugged caller.
 * CORS is intentionally not configured — the only expected callers are
 * server-side (glamli-api/glamli-worker), never a browser.
 *
 * Roadmap toward an OpenAPI app: the same per-tool zod schemas in src/tools/*
 * can be projected to an OpenAPI document (the schemas already describe every
 * operation's inputs), serving ordinary HTTP clients alongside MCP clients.
 *
 * Run with: node dist/http.js   (after `npm run build`)
 */
async function main(): Promise<void> {
  const config = loadConfig();
  const client = new WekaClient(config.wekaApiUrl);

  const app = express();
  app.use(express.json());

  function requireInternalAuth(req: Request, res: Response, next: NextFunction): void {
    if (!config.internalAuthSharedSecret) {
      // Not configured (local/compose dev) — nothing to check against.
      next();
      return;
    }
    if (req.header("X-Internal-Auth") !== config.internalAuthSharedSecret) {
      res.status(401).json({ error: "unauthorized" });
      return;
    }
    next();
  }

  const mcpRateLimit = rateLimit({ windowMs: 60_000, max: 300 });

  // Stateless: a fresh transport + server per request keeps the example simple.
  //
  // `sessionIdGenerator: undefined` is what actually makes the transport
  // stateless — it must NOT issue a session id. With a generator set, the SDK
  // runs in stateful mode (it expects every follow-up request to carry the
  // issued `mcp-session-id`), but because we discard the transport after each
  // request that session never exists again, so initialize succeeds and the
  // very next call is rejected with "Server not initialized" (400). Per-request
  // transports require stateless mode. `enableJsonResponse` returns a plain
  // JSON-RPC body instead of an SSE stream, which is all a request/response
  // HTTP client (FastAPI) needs.
  app.post("/mcp", requireInternalAuth, mcpRateLimit, async (req, res) => {
    const server = createServer(client);
    const transport = new StreamableHTTPServerTransport({
      sessionIdGenerator: undefined,
      enableJsonResponse: true,
    });
    res.on("close", () => {
      transport.close();
      server.close();
    });
    await server.connect(transport);
    await transport.handleRequest(req, res, req.body);
  });

  // Deliberately not behind requireInternalAuth — Container Apps' own
  // liveness/readiness probes hit this route directly and don't carry the
  // shared secret; it returns no sensitive data.
  app.get("/healthz", (_req, res) => res.json({ ok: true, wekaApiUrl: config.wekaApiUrl }));

  app.listen(config.httpPort, () => {
    console.error(`weka-mcp HTTP transport on :${config.httpPort}/mcp -> ${config.wekaApiUrl}`);
  });
}

main().catch((err) => {
  console.error("weka-mcp (http) failed to start:", err);
  process.exit(1);
});
