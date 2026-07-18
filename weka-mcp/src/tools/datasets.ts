import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { WekaClient } from "../client.js";
import { run } from "./util.js";

export function register(server: McpServer, client: WekaClient): void {
  server.registerTool(
    "weka_list_datasets",
    {
      title: "List datasets",
      description: "List uploaded datasets with format and size.",
      inputSchema: {},
    },
    async () => run(() => client.get("/datasets")),
  );

  server.registerTool(
    "weka_get_dataset",
    {
      title: "Dataset metadata",
      description: "Get a dataset's attributes, types, instance count, and class attribute.",
      inputSchema: { name: z.string() },
    },
    async ({ name }) => run(() => client.get(`/datasets/${encodeURIComponent(name)}`)),
  );

  server.registerTool(
    "weka_upload_dataset",
    {
      title: "Upload dataset",
      description:
        "Upload an ARFF or CSV file from a local path. The last attribute is treated as the class.",
      inputSchema: {
        filePath: z.string().describe("Absolute path to a .arff or .csv file on disk"),
        name: z.string().optional().describe("Stored name (no extension); defaults to the filename"),
      },
    },
    async ({ filePath, name }) => run(() => client.uploadDataset(name, filePath)),
  );

  server.registerTool(
    "weka_delete_dataset",
    {
      title: "Delete dataset",
      description: "Remove an uploaded dataset.",
      inputSchema: { name: z.string() },
    },
    async ({ name }) => run(() => client.del(`/datasets/${encodeURIComponent(name)}`)),
  );
}
