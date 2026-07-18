/**
 * Smoke test: build the server with a mock client and assert the expected tools
 * are registered. Requires no running Weka API. Exits non-zero on failure.
 */
import assert from "node:assert/strict";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { createServer } from "../src/server.js";
import type { WekaClient } from "../src/client.js";

// A WekaClient stand-in — never actually called by tool *listing*.
const mockClient = {} as unknown as WekaClient;

const EXPECTED = [
  "weka_health",
  "weka_list_algorithms",
  "weka_list_clusterers",
  "weka_list_associators",
  "weka_list_as_evaluators",
  "weka_list_as_searches",
  "weka_list_filters",
  "weka_filter_metadata",
  "weka_list_datasets",
  "weka_get_dataset",
  "weka_upload_dataset",
  "weka_delete_dataset",
  "weka_attribute_stats",
  "weka_dataset_summary",
  "weka_histogram",
  "weka_scatter",
  "weka_scatter_matrix",
  "weka_transform",
  "weka_transform_preview",
  "weka_train",
  "weka_train_search",
  "weka_train_update",
  "weka_predict",
  "weka_predict_dataset",
  "weka_evaluate",
  "weka_learning_curve",
  "weka_list_models",
  "weka_get_model",
  "weka_delete_model",
  "weka_model_tree",
  "weka_model_graph",
  "weka_download_model",
  "weka_import_model",
  "weka_cluster_train",
  "weka_cluster_assign",
  "weka_cluster_evaluate",
  "weka_attribute_selection",
  "weka_associate",
  "weka_experiment",
  "weka_diag_errors",
  "weka_diag_threshold_curve",
  "weka_diag_margin_curve",
  "weka_diag_cost_curve",
  "weka_diag_calibration",
  "weka_diag_residuals",
  "weka_diag_pr_curve",
];

async function main(): Promise<void> {
  const server = createServer(mockClient);
  const client = new Client({ name: "smoke", version: "0.0.0" });
  const [a, b] = InMemoryTransport.createLinkedPair();
  await Promise.all([server.connect(a), client.connect(b)]);

  const { tools } = await client.listTools();
  const names = tools.map((t) => t.name).sort();

  for (const expected of EXPECTED) {
    assert.ok(names.includes(expected), `missing tool: ${expected}`);
  }
  assert.equal(names.length, EXPECTED.length, `tool count ${names.length} != ${EXPECTED.length}`);

  // Every tool must carry a description (good agent ergonomics).
  for (const t of tools) {
    assert.ok(t.description && t.description.length > 0, `tool ${t.name} has no description`);
  }

  await client.close();
  await server.close();
  console.log(`smoke OK: ${names.length} tools registered, all described`);
}

main().catch((err) => {
  console.error("smoke FAILED:", err);
  process.exit(1);
});
