import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { WekaClient } from "../client.js";
import { run } from "./util.js";

const filterSpec = z.object({
  filter: z.string().describe("Fully-qualified weka.filters.* classname"),
  options: z.array(z.string()).optional().describe("Weka CLI-style options"),
});

export function register(server: McpServer, client: WekaClient): void {
  server.registerTool(
    "weka_transform",
    {
      title: "Transform dataset",
      description: "Apply a chain of filters to a dataset and persist the result as a new dataset.",
      inputSchema: {
        dataset: z.string(),
        filters: z.array(filterSpec).min(1),
        outputName: z.string(),
        format: z.enum(["arff", "csv"]).optional(),
      },
    },
    async (args) => run(() => client.post("/transform", args)),
  );

  server.registerTool(
    "weka_transform_preview",
    {
      title: "Preview transform",
      description: "Run a filter chain in memory and return a sample of transformed rows; writes nothing.",
      inputSchema: {
        dataset: z.string(),
        filters: z.array(filterSpec).min(1),
        head: z.number().int().min(1).max(200).optional(),
        seed: z.number().int().optional(),
      },
    },
    async ({ dataset, filters, head, seed }) =>
      run(() => client.post("/transform/preview", { dataset, filters }, { head, seed })),
  );
}
