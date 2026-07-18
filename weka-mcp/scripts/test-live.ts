/**
 * Live end-to-end test through the MCP tool layer against a running Weka API.
 * Exercises health -> upload -> train -> predict -> evaluate. Skips (exit 0)
 * if the Weka API is unreachable, so it is safe to run in CI without Docker.
 *
 *   WEKA_API_URL=http://localhost:7070 npm run test:live
 */
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { WekaClient } from "../src/client.js";
import { createServer } from "../src/server.js";

const __dirname = dirname(fileURLToPath(import.meta.url));
const IRIS = resolve(__dirname, "../../api/src/test/resources/iris.arff");
const BASE = (process.env.WEKA_API_URL ?? "http://localhost:7070").replace(/\/+$/, "");

function text(res: CallToolResult): string {
  return res.content.map((c) => ("text" in c ? c.text : "")).join("\n");
}

async function reachable(): Promise<boolean> {
  try {
    const r = await fetch(`${BASE}/health`, { signal: AbortSignal.timeout(2000) });
    return r.ok;
  } catch {
    return false;
  }
}

async function main(): Promise<void> {
  if (!(await reachable())) {
    console.log(`SKIP test:live — Weka API not reachable at ${BASE} (start it with: docker compose up -d)`);
    return;
  }

  const server = createServer(new WekaClient(BASE));
  const client = new Client({ name: "test-live", version: "0.0.0" });
  const [a, b] = InMemoryTransport.createLinkedPair();
  await Promise.all([server.connect(a), client.connect(b)]);

  const call = async (name: string, args: Record<string, unknown>): Promise<CallToolResult> => {
    const res = (await client.callTool({ name, arguments: args })) as CallToolResult;
    assert.ok(!res.isError, `${name} errored: ${text(res)}`);
    return res;
  };

  // 1. health
  const health = JSON.parse(text(await call("weka_health", {})));
  assert.equal(health.status, "ok");
  console.log(`  health: weka ${health.wekaVersion}`);

  // 2. upload iris (unique name so reruns don't collide)
  const dataset = `iris_mcp_${Date.now()}`;
  const up = JSON.parse(text(await call("weka_upload_dataset", { filePath: IRIS, name: dataset })));
  assert.equal(up.numInstances, 150);
  console.log(`  upload: ${dataset} (${up.numInstances} instances)`);

  // 3. train J48
  const modelName = `${dataset}_j48`;
  const tr = JSON.parse(
    text(await call("weka_train", { dataset, algorithm: "weka.classifiers.trees.J48", modelName })),
  );
  assert.equal(tr.modelName, modelName);
  console.log(`  train: ${tr.algorithm} in ${tr.trainingTimeMs}ms`);

  // 4. predict
  const pr = JSON.parse(
    text(
      await call("weka_predict", {
        model: modelName,
        instances: [{ sepallength: 5.1, sepalwidth: 3.5, petallength: 1.4, petalwidth: 0.2 }],
      }),
    ),
  );
  assert.ok(pr.predictions[0].predictedClass);
  console.log(`  predict: ${pr.predictions[0].predictedClass}`);

  // 5. evaluate with 10-fold CV
  const ev = JSON.parse(
    text(await call("weka_evaluate", { model: modelName, dataset, method: "cross_validation", folds: 10 })),
  );
  assert.ok(ev.accuracy > 0.9, `accuracy ${ev.accuracy} too low`);
  console.log(`  evaluate (10-fold CV): accuracy=${ev.accuracy}`);

  // cleanup
  await call("weka_delete_model", { name: modelName });
  await call("weka_delete_dataset", { name: dataset });

  await client.close();
  await server.close();
  console.log("test:live OK — health -> upload -> train -> predict -> evaluate through MCP tools");
}

main().catch((err) => {
  console.error("test:live FAILED:", err);
  process.exit(1);
});
