import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { WekaClient } from "../client.js";
import { run } from "./util.js";

export function register(server: McpServer, client: WekaClient): void {
  server.registerTool(
    "weka_associate",
    {
      title: "Mine association rules",
      description:
        "Mine rules with weka.associations.* (Apriori, FPGrowth). Needs all-nominal data — " +
        "numeric attributes return REQUIRES_NOMINAL (discretize first via weka_transform).",
      inputSchema: {
        dataset: z.string(),
        algorithm: z.string().describe("Fully-qualified weka.associations.* classname"),
        options: z.array(z.string()).optional(),
      },
    },
    async (args) => run(() => client.post("/associate", args)),
  );
}
