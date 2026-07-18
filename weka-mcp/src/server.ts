import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { WekaClient } from "./client.js";

import { register as registerHealth } from "./tools/health.js";
import { register as registerAlgorithms } from "./tools/algorithms.js";
import { register as registerDatasets } from "./tools/datasets.js";
import { register as registerEda } from "./tools/eda.js";
import { register as registerTransform } from "./tools/transform.js";
import { register as registerTrain } from "./tools/train.js";
import { register as registerPredict } from "./tools/predict.js";
import { register as registerEvaluate } from "./tools/evaluate.js";
import { register as registerModels } from "./tools/models.js";
import { register as registerCluster } from "./tools/cluster.js";
import { register as registerAttributeSelection } from "./tools/attributeSelection.js";
import { register as registerAssociate } from "./tools/associate.js";
import { register as registerExperiment } from "./tools/experiment.js";
import { register as registerDiagnostics } from "./tools/diagnostics.js";

/**
 * Build an McpServer with every Weka tool registered against `client`.
 * Shared by the stdio entrypoint (index.ts) and the HTTP transport (http.ts),
 * so the tool definitions are the single source of truth for both surfaces.
 */
export function createServer(client: WekaClient): McpServer {
  const server = new McpServer({
    name: "weka-mcp",
    version: "0.1.0",
  });

  for (const register of [
    registerHealth,
    registerAlgorithms,
    registerDatasets,
    registerEda,
    registerTransform,
    registerTrain,
    registerPredict,
    registerEvaluate,
    registerModels,
    registerCluster,
    registerAttributeSelection,
    registerAssociate,
    registerExperiment,
    registerDiagnostics,
  ]) {
    register(server, client);
  }

  return server;
}
