import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { WekaClient } from "../client.js";
import { run } from "./util.js";

/** Exploratory data analysis — read-only, query-param driven endpoints. */
export function register(server: McpServer, client: WekaClient): void {
  server.registerTool(
    "weka_attribute_stats",
    {
      title: "Attribute statistics",
      description: "Per-attribute summary (min/max/mean/stdDev for numeric, value counts for nominal).",
      inputSchema: { dataset: z.string(), attribute: z.string() },
    },
    async ({ dataset, attribute }) =>
      run(() => client.get(`/datasets/${encodeURIComponent(dataset)}/attribute-stats`, { attribute })),
  );

  server.registerTool(
    "weka_dataset_summary",
    {
      title: "Dataset summary",
      description: "Statistics for every attribute on the dataset.",
      inputSchema: { dataset: z.string() },
    },
    async ({ dataset }) => run(() => client.get(`/datasets/${encodeURIComponent(dataset)}/summary`)),
  );

  server.registerTool(
    "weka_histogram",
    {
      title: "Histogram",
      description: "Binned counts for an attribute; optionally broken down by class.",
      inputSchema: {
        dataset: z.string(),
        attribute: z.string(),
        bins: z.number().int().optional(),
        groupBy: z.string().optional().describe("e.g. 'class' to break each bin down by class label"),
      },
    },
    async ({ dataset, attribute, bins, groupBy }) =>
      run(() =>
        client.get(`/datasets/${encodeURIComponent(dataset)}/histogram`, { attribute, bins, groupBy }),
      ),
  );

  server.registerTool(
    "weka_scatter",
    {
      title: "Scatter plot data",
      description: "Per-point x/y data for two attributes; points carry the class when nominal.",
      inputSchema: {
        dataset: z.string(),
        x: z.string(),
        y: z.string(),
        sample: z.number().int().optional(),
        jitter: z.boolean().optional(),
        seed: z.number().int().optional(),
      },
    },
    async ({ dataset, x, y, sample, jitter, seed }) =>
      run(() => client.get(`/datasets/${encodeURIComponent(dataset)}/scatter`, { x, y, sample, jitter, seed })),
  );

  server.registerTool(
    "weka_scatter_matrix",
    {
      title: "Scatter matrix data",
      description: "All unordered pairs of the listed attributes (server caps at 6 attributes).",
      inputSchema: {
        dataset: z.string(),
        attributes: z.string().describe("Comma-separated attribute names, e.g. 'sepallength,sepalwidth'"),
        sample: z.number().int().optional(),
        seed: z.number().int().optional(),
      },
    },
    async ({ dataset, attributes, sample, seed }) =>
      run(() =>
        client.get(`/datasets/${encodeURIComponent(dataset)}/scatter-matrix`, { attributes, sample, seed }),
      ),
  );
}
