import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { WekaClient } from "../client.js";
import { run } from "./util.js";

/** Shared base fields for the model+dataset diagnostics endpoints. */
const base = {
  model: z.string(),
  dataset: z.string(),
};

export function register(server: McpServer, client: WekaClient): void {
  server.registerTool(
    "weka_diag_errors",
    {
      title: "Classifier errors",
      description: "Per-instance predicted vs actual (Weka 'Visualize classifier errors').",
      inputSchema: { ...base, sample: z.number().int().optional(), seed: z.number().int().optional() },
    },
    async (args) => run(() => client.post("/diagnostics/errors", args)),
  );

  server.registerTool(
    "weka_diag_threshold_curve",
    {
      title: "Threshold / ROC curve",
      description: "ROC / threshold curve + AUC for a chosen positive class (nominal only).",
      inputSchema: { ...base, classValue: z.string().optional() },
    },
    async (args) => run(() => client.post("/diagnostics/threshold-curve", args)),
  );

  server.registerTool(
    "weka_diag_margin_curve",
    {
      title: "Margin curve",
      description: "Cumulative margin distribution (nominal only); useful for ensemble diagnostics.",
      inputSchema: { ...base },
    },
    async (args) => run(() => client.post("/diagnostics/margin-curve", args)),
  );

  server.registerTool(
    "weka_diag_cost_curve",
    {
      title: "Cost curve",
      description: "Drummond-Holte cost curve for a chosen positive class (nominal only).",
      inputSchema: { ...base, classValue: z.string().optional() },
    },
    async (args) => run(() => client.post("/diagnostics/cost-curve", args)),
  );

  server.registerTool(
    "weka_diag_calibration",
    {
      title: "Calibration",
      description: "Reliability diagram + Brier score for a chosen class (nominal only).",
      inputSchema: { ...base, classValue: z.string().optional(), bins: z.number().int().optional() },
    },
    async (args) => run(() => client.post("/diagnostics/calibration", args)),
  );

  server.registerTool(
    "weka_diag_residuals",
    {
      title: "Residuals",
      description: "Sampled actual/predicted/residual for numeric-class models (NOT_NUMERIC_CLASS otherwise).",
      inputSchema: { ...base, sample: z.number().int().optional(), seed: z.number().int().optional() },
    },
    async (args) => run(() => client.post("/diagnostics/residuals", args)),
  );

  server.registerTool(
    "weka_diag_pr_curve",
    {
      title: "Precision-recall curve",
      description: "Precision-recall curve + AUPRC for a chosen class (nominal only).",
      inputSchema: { ...base, classValue: z.string().optional() },
    },
    async (args) => run(() => client.post("/diagnostics/pr-curve", args)),
  );
}
