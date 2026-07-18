import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { WekaClient } from "../client.js";
import { run } from "./util.js";

export function register(server: McpServer, client: WekaClient): void {
  server.registerTool(
    "weka_health",
    {
      title: "Weka API health",
      description: "Liveness probe; returns status and the Weka version the backend is running.",
      inputSchema: {},
    },
    async () => run(() => client.get("/health")),
  );
}
