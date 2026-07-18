import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { WekaClient } from "../client.js";
import { run } from "./util.js";

/** Discovery endpoints: list the classnames available for each Weka panel. */
export function register(server: McpServer, client: WekaClient): void {
  server.registerTool(
    "weka_list_algorithms",
    {
      title: "List classifiers",
      description: "List Weka classifier classnames grouped by family (trees, bayes, functions, ...).",
      inputSchema: {},
    },
    async () => run(() => client.get("/algorithms")),
  );

  server.registerTool(
    "weka_list_clusterers",
    {
      title: "List clusterers",
      description: "List discoverable weka.clusterers.* classnames.",
      inputSchema: {},
    },
    async () => run(() => client.get("/clusterers")),
  );

  server.registerTool(
    "weka_list_associators",
    {
      title: "List associators",
      description: "List discoverable weka.associations.* classnames (Apriori, FPGrowth, ...).",
      inputSchema: {},
    },
    async () => run(() => client.get("/associators")),
  );

  server.registerTool(
    "weka_list_as_evaluators",
    {
      title: "List attribute-selection evaluators",
      description: "List weka.attributeSelection.* evaluator classes (CfsSubsetEval, InfoGainAttributeEval, ...).",
      inputSchema: {},
    },
    async () => run(() => client.get("/attribute-selection/evaluators")),
  );

  server.registerTool(
    "weka_list_as_searches",
    {
      title: "List attribute-selection searches",
      description: "List weka.attributeSelection.* search classes (BestFirst, Ranker, ...).",
      inputSchema: {},
    },
    async () => run(() => client.get("/attribute-selection/searches")),
  );

  server.registerTool(
    "weka_list_filters",
    {
      title: "List filters",
      description: "List Weka filters grouped by family, with supervised/level flags.",
      inputSchema: {},
    },
    async () => run(() => client.get("/filters")),
  );

  server.registerTool(
    "weka_filter_metadata",
    {
      title: "Filter metadata",
      description: "Per-filter description and full options schema for building an options form.",
      inputSchema: {
        filter: z.string().describe("Fully-qualified weka.filters.* classname"),
      },
    },
    async ({ filter }) => run(() => client.get("/filters/metadata", { filter })),
  );
}
