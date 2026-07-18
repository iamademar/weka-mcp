import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { WekaClient } from "../client.js";
import { run } from "./util.js";

export function register(server: McpServer, client: WekaClient): void {
  server.registerTool(
    "weka_list_models",
    {
      title: "List models",
      description: "List persisted models with size and kind (classifier / clusterer).",
      inputSchema: {},
    },
    async () => run(() => client.get("/models")),
  );

  server.registerTool(
    "weka_get_model",
    {
      title: "Model metadata",
      description: "Get a model's algorithm and textual summary.",
      inputSchema: { name: z.string() },
    },
    async ({ name }) => run(() => client.get(`/models/${encodeURIComponent(name)}`)),
  );

  server.registerTool(
    "weka_delete_model",
    {
      title: "Delete model",
      description: "Remove a persisted model.",
      inputSchema: { name: z.string() },
    },
    async ({ name }) => run(() => client.del(`/models/${encodeURIComponent(name)}`)),
  );

  server.registerTool(
    "weka_model_tree",
    {
      title: "Model tree (DOT)",
      description: "Graphviz DOT for tree-based classifiers (J48, RandomTree, ...). 400 NOT_DRAWABLE otherwise.",
      inputSchema: { name: z.string() },
    },
    async ({ name }) => run(() => client.get(`/models/${encodeURIComponent(name)}/tree`)),
  );

  server.registerTool(
    "weka_model_graph",
    {
      title: "Model graph (DOT)",
      description: "Graphviz DOT for Bayes-net classifiers.",
      inputSchema: { name: z.string() },
    },
    async ({ name }) => run(() => client.get(`/models/${encodeURIComponent(name)}/graph`)),
  );

  server.registerTool(
    "weka_download_model",
    {
      title: "Download model",
      description: "Save a model's serialized bytes to a local file path; returns the byte count.",
      inputSchema: {
        name: z.string(),
        outPath: z.string().describe("Absolute local path to write the .model file to"),
      },
    },
    async ({ name, outPath }) =>
      run(async () => {
        const bytes = await client.downloadModel(name, outPath);
        return { name, outPath, bytesWritten: bytes };
      }),
  );

  server.registerTool(
    "weka_import_model",
    {
      title: "Import model",
      description: "Import an externally-trained .model file, supplying a dataset name for the header.",
      inputSchema: {
        name: z.string().describe("Name to store the imported model under"),
        filePath: z.string().describe("Absolute local path to the .model file"),
        dataset: z.string().describe("Existing dataset whose header matches the model"),
      },
    },
    async ({ name, filePath, dataset }) => run(() => client.importModel(name, filePath, dataset)),
  );
}
