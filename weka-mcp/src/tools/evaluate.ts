import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { WekaClient } from "../client.js";
import { run } from "./util.js";

export function register(server: McpServer, client: WekaClient): void {
  server.registerTool(
    "weka_evaluate",
    {
      title: "Evaluate model",
      description:
        "Score a model against a dataset. method = test_set (default) | cross_validation | percentage_split. " +
        "Returns accuracy/kappa, per-class metrics (nominal) or regression metrics (numeric), and confusion matrix. " +
        "Pass a square costMatrix (nominal only) for totalCost/avgCost.",
      inputSchema: {
        model: z.string(),
        dataset: z.string(),
        method: z.enum(["test_set", "cross_validation", "percentage_split"]).optional(),
        folds: z.number().int().optional().describe("cross_validation only (default 10)"),
        trainPercent: z.number().optional().describe("percentage_split only (default 66.0)"),
        seed: z.number().int().optional(),
        preserveOrder: z
          .boolean()
          .optional()
          .describe("percentage_split only: keep row order instead of shuffling (default false)"),
        costMatrix: z.array(z.array(z.number())).optional(),
      },
    },
    async (args) => run(() => client.post("/evaluate", args)),
  );

  server.registerTool(
    "weka_learning_curve",
    {
      title: "Learning curve",
      description: "CV metric vs training-set fraction for a model's configuration.",
      inputSchema: {
        model: z.string(),
        dataset: z.string(),
        fractions: z.array(z.number()).optional().describe("e.g. [0.1,0.25,0.5,1.0]; default 0.1..1.0"),
        folds: z.number().int().optional(),
        seed: z.number().int().optional(),
      },
    },
    async (args) => run(() => client.post("/diagnostics/learning-curve", args)),
  );
}
