#!/usr/bin/env bash
# Verify the deployed stack end-to-end, entirely over the public HTTPS /mcp endpoint:
#   1. GET  <mcp>/healthz            (proves weka-mcp is up + its WEKA_API_URL)
#   2. MCP  weka_upload_dataset      (iris.arff — baked into the image at /app/fixtures)
#   3. MCP  weka_train               (J48 on iris)
#   4. MCP  weka_predict             -> expect "Iris-setosa"
#
# weka_upload_dataset reads a path INSIDE the weka-mcp container; the image ships
# the fixture at /app/fixtures/iris.arff (see weka-mcp/Dockerfile), so no exec /
# file staging is needed — the whole smoke runs over the public endpoint.
#
# Config via env:
#   AZURE_RESOURCE_GROUP  default weka-mcp-rg
#   NAME_PREFIX           default wekamcp     (must match deploy.sh)
#   INTERNAL_AUTH_SHARED_SECRET   required
set -euo pipefail

RG="${AZURE_RESOURCE_GROUP:-weka-mcp-rg}"
NAME_PREFIX="${NAME_PREFIX:-wekamcp}"
# In open-demo mode there is no secret; only send the header if one is provided.
SECRET="${INTERNAL_AUTH_SHARED_SECRET:-}"
MCP_APP="${NAME_PREFIX}-weka-mcp"

MCP_FQDN="$(az containerapp show --resource-group "$RG" --name "$MCP_APP" \
  --query 'properties.configuration.ingress.fqdn' -o tsv)"
MCP="https://${MCP_FQDN}/mcp"
echo "==> weka-mcp: https://${MCP_FQDN}"

echo "==> 1. health (also wakes weka-mcp from scale-to-zero)"
curl -fsS "https://${MCP_FQDN}/healthz"; echo

hdr=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
if [[ -n "$SECRET" ]]; then
  hdr+=(-H "X-Internal-Auth: ${SECRET}")
  echo "==> (sending X-Internal-Auth header)"
else
  echo "==> (open mode: no auth header)"
fi

echo "==> 2. upload (fixture baked into the image)"
curl -fsS --max-time 260 "${hdr[@]}" "$MCP" -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
  "params":{"name":"weka_upload_dataset","arguments":{"filePath":"/app/fixtures/iris.arff","name":"iris"}}}'; echo

echo "==> 3. train (J48) — first call may absorb the weka-api JVM cold start"
curl -fsS --max-time 260 "${hdr[@]}" "$MCP" -d '{"jsonrpc":"2.0","id":2,"method":"tools/call",
  "params":{"name":"weka_train","arguments":{"dataset":"iris",
    "algorithm":"weka.classifiers.trees.J48","modelName":"iris-j48"}}}'; echo

echo "==> 4. predict (expect Iris-setosa)"
RESP="$(curl -fsS --max-time 260 "${hdr[@]}" "$MCP" -d '{"jsonrpc":"2.0","id":3,"method":"tools/call",
  "params":{"name":"weka_predict","arguments":{"model":"iris-j48",
    "instances":[{"sepallength":5.1,"sepalwidth":3.5,"petallength":1.4,"petalwidth":0.2}]}}}')"
echo "$RESP"

if echo "$RESP" | grep -q 'Iris-setosa'; then
  echo ""
  echo "==> PASS: cloud stack returned Iris-setosa."
else
  echo ""
  echo "==> FAIL: expected Iris-setosa in the prediction response." >&2
  exit 1
fi
