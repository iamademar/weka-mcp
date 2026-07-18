import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { WekaClient } from "../client.js";
import { run } from "./util.js";

export function register(server: McpServer, client: WekaClient): void {
  server.registerTool(
    "weka_cluster_train",
    {
      title: "Train clusterer",
      description: "Build and persist a clusterer. A nominal class is removed before clustering by default.",
      inputSchema: {
        dataset: z.string(),
        algorithm: z.string().describe("Fully-qualified weka.clusterers.* classname"),
        modelName: z.string(),
        options: z.array(z.string()).optional().describe("e.g. ['-N','3'] for SimpleKMeans"),
      },
    },
    async (args) => run(() => client.post("/cluster/train", args)),
  );

  server.registerTool(
    "weka_cluster_assign",
    {
      title: "Assign clusters",
      description: "Assign cluster indices to inline instances or a named dataset.",
      inputSchema: {
        model: z.string(),
        dataset: z.string().optional(),
        instances: z.array(z.record(z.string(), z.unknown())).optional(),
      },
    },
    async (args) => run(() => client.post("/cluster/assign", args)),
  );

  server.registerTool(
    "weka_cluster_evaluate",
    {
      title: "Evaluate clusterer",
      description: "Cluster evaluation; classes-to-clusters when the dataset has a nominal class.",
      inputSchema: { model: z.string(), dataset: z.string() },
    },
    async (args) => run(() => client.post("/cluster/evaluate", args)),
  );
}
