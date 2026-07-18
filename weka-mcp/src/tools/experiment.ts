import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { WekaClient } from "../client.js";
import { run } from "./util.js";

const algoSpec = z.object({
  algorithm: z.string(),
  options: z.array(z.string()).optional(),
});

export function register(server: McpServer, client: WekaClient): void {
  server.registerTool(
    "weka_experiment",
    {
      title: "Run experiment",
      description:
        "Run a k-fold CV grid over datasets x algorithms and flag each algorithm win/loss/tie against " +
        "a baseline using a corrected resampled paired t-test.",
      inputSchema: {
        datasets: z.array(z.string()).min(1),
        algorithms: z.array(algoSpec).min(1),
        metric: z.string().optional().describe("e.g. 'accuracy' (default) or 'rmse'"),
        folds: z.number().int().optional(),
        runs: z.number().int().optional(),
        seed: z.number().int().optional(),
        baselineIndex: z.number().int().optional().describe("Index into algorithms for significance"),
      },
    },
    async (args) => run(() => client.post("/experiment", args)),
  );
}
