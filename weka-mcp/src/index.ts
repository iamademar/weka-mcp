#!/usr/bin/env node
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { WekaClient } from "./client.js";
import { loadConfig } from "./config.js";
import { createServer } from "./server.js";

/**
 * Stdio entrypoint. The MCP client (Claude Desktop / Claude Code) spawns this
 * as a child process and speaks JSON-RPC over stdout. All logging must go to
 * stderr to avoid corrupting the protocol channel.
 */
async function main(): Promise<void> {
  const config = loadConfig();
  const client = new WekaClient(config.wekaApiUrl);
  const server = createServer(client);

  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error(`weka-mcp connected (stdio) -> Weka API at ${config.wekaApiUrl}`);
}

main().catch((err) => {
  console.error("weka-mcp failed to start:", err);
  process.exit(1);
});
