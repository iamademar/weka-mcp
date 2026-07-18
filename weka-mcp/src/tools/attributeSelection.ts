import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { WekaClient } from "../client.js";
import { run } from "./util.js";

export function register(server: McpServer, client: WekaClient): void {
  server.registerTool(
    "weka_attribute_selection",
    {
      title: "Attribute selection",
      description:
        "Run an evaluator + search and return the selected subset (and per-attribute merit for Ranker). " +
        "A subset evaluator needs a subset search; an attribute evaluator needs Ranker.",
      inputSchema: {
        dataset: z.string(),
        evaluator: z.string().describe("e.g. weka.attributeSelection.InfoGainAttributeEval"),
        search: z.string().describe("e.g. weka.attributeSelection.Ranker"),
        classIndex: z.number().int().optional(),
        evaluatorOptions: z.array(z.string()).optional(),
        searchOptions: z.array(z.string()).optional(),
        folds: z.number().int().optional().describe("Cross-validated selection if set"),
      },
    },
    async (args) => run(() => client.post("/attribute-selection", args)),
  );
}
