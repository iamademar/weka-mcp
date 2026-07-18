import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { WekaClient } from "../client.js";
import { run } from "./util.js";

export function register(server: McpServer, client: WekaClient): void {
  server.registerTool(
    "weka_predict",
    {
      title: "Predict instances",
      description: "Score inline instances with a trained model; returns class + distribution.",
      inputSchema: {
        model: z.string(),
        instances: z
          .array(z.record(z.string(), z.unknown()))
          .min(1)
          .describe("Each object maps attribute name -> value; missing keys = missing values"),
      },
    },
    async (args) => run(() => client.post("/predict", args)),
  );

  server.registerTool(
    "weka_predict_dataset",
    {
      title: "Batch predict dataset",
      description: "Score every row of an uploaded dataset with a trained model.",
      inputSchema: {
        model: z.string(),
        dataset: z.string(),
        includeDistribution: z.boolean().optional(),
      },
    },
    async (args) => run(() => client.post("/predict/dataset", args)),
  );
}
