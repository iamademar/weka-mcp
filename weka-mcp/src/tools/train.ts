import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { WekaClient } from "../client.js";
import { run } from "./util.js";

const filterSpec = z.object({
  filter: z.string(),
  options: z.array(z.string()).optional(),
});

export function register(server: McpServer, client: WekaClient): void {
  server.registerTool(
    "weka_train",
    {
      title: "Train classifier",
      description:
        "Train and persist a classifier. Optionally embed a leakage-safe filter chain (FilteredClassifier).",
      inputSchema: {
        dataset: z.string(),
        algorithm: z.string().describe("Fully-qualified weka.classifiers.* classname"),
        modelName: z.string().describe("Name to persist to (no extension/path separators)"),
        options: z.array(z.string()).optional().describe("Weka CLI options, e.g. ['-C','0.25','-M','2']"),
        classIndex: z.number().int().optional().describe("Zero-based; -1 (default) = last attribute"),
        filters: z.array(filterSpec).optional().describe("Filters embedded via FilteredClassifier"),
      },
    },
    async (args) => run(() => client.post("/train", args)),
  );

  server.registerTool(
    "weka_train_search",
    {
      title: "Train with hyperparameter search",
      description: "Train via CVParameterSelection; returns the tuned model and bestOptions.",
      inputSchema: {
        dataset: z.string(),
        algorithm: z.string(),
        modelName: z.string(),
        cvParameters: z
          .array(z.string())
          .describe("CVParameterSelection param specs, e.g. ['K 1 10 10'] to tune IBk's -K"),
        options: z.array(z.string()).optional(),
        classIndex: z.number().int().optional(),
      },
    },
    async (args) => run(() => client.post("/train/search", args)),
  );

  server.registerTool(
    "weka_train_update",
    {
      title: "Incrementally update model",
      description: "Feed new instances to a stored UpdateableClassifier and re-save it.",
      inputSchema: {
        model: z.string(),
        instances: z
          .array(z.record(z.string(), z.unknown()))
          .min(1)
          .describe("Each object maps attribute name -> value"),
      },
    },
    async (args) => run(() => client.post("/train/update", args)),
  );
}
