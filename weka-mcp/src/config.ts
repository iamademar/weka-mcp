/** Runtime configuration, read from the environment. */
export interface Config {
  /** Base URL of the Weka REST API, e.g. http://localhost:7070. */
  wekaApiUrl: string;
  /** Port for the Phase 2 HTTP transport. */
  httpPort: number;
  /**
   * Shared secret FastAPI attaches to every call as `X-Internal-Auth` (master
   * §10.6 "authenticate requests from FastAPI where practical"). Defense in
   * depth alongside Container Apps internal-only ingress (the primary
   * control) — undefined in local/compose dev, where this isn't set and the
   * check is skipped (see http.ts's requireInternalAuth).
   */
  internalAuthSharedSecret: string | undefined;
}

export function loadConfig(): Config {
  const wekaApiUrl = (process.env.WEKA_API_URL ?? "http://localhost:7070").replace(/\/+$/, "");
  const httpPort = Number(process.env.MCP_HTTP_PORT ?? 3000);
  const internalAuthSharedSecret = process.env.INTERNAL_AUTH_SHARED_SECRET || undefined;
  return { wekaApiUrl, httpPort, internalAuthSharedSecret };
}
