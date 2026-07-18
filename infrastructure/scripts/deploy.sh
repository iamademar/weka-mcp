#!/usr/bin/env bash
# End-to-end deploy of the WEKA-over-MCP stack to Azure Container Apps.
#
# Two-pass, because the container apps reference image tags that must already
# exist in ACR:
#   pass 1  -> deploy ACR only (main.bicep deployApps=false)
#   build   -> az acr build both images into that ACR
#   pass 2  -> deploy the full stack (main.bicep deployApps=true)
#   budget  -> subscription-scoped cost alert (optional)
#
# Requires: az login, and the az containerapp extension (auto-installed on first use).
#
# Config via env (all optional except where noted):
#   AZURE_RESOURCE_GROUP   default weka-mcp-rg
#   AZURE_LOCATION         default eastus
#   AZURE_NAME_PREFIX      default wekamcp   (3-11 lowercase alphanumeric)
#   IMAGE_TAG              default latest
#   MCP_EXTERNAL_INGRESS   default true
#
#   OPEN_DEMO              default true. When true, weka-mcp is deployed with NO
#                          auth (empty secret) so anyone can connect from ChatGPT/
#                          Claude with zero setup. Set OPEN_DEMO=false to require
#                          the X-Internal-Auth header instead.
#   INTERNAL_AUTH_SHARED_SECRET  only used when OPEN_DEMO=false; generated if unset.
#
#   BUDGET_AMOUNT          default 25   (monthly cost-alert threshold; 0 disables)
#   BUDGET_EMAIL           email for budget alerts; defaults to the signed-in user
set -euo pipefail

RG="${AZURE_RESOURCE_GROUP:-weka-mcp-rg}"
LOCATION="${AZURE_LOCATION:-eastus}"
NAME_PREFIX="${AZURE_NAME_PREFIX:-wekamcp}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
MCP_EXTERNAL_INGRESS="${MCP_EXTERNAL_INGRESS:-true}"
OPEN_DEMO="${OPEN_DEMO:-true}"
BUDGET_AMOUNT="${BUDGET_AMOUNT:-25}"

INFRA="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# --- auth mode ---
if [[ "$OPEN_DEMO" == "true" ]]; then
  AUTH_SECRET=""   # empty => open mode (requireInternalAuth skips the check)
  echo "==> OPEN DEMO mode: weka-mcp will be PUBLIC with NO auth (anyone can call /mcp)."
else
  AUTH_SECRET="${INTERNAL_AUTH_SHARED_SECRET:-}"
  if [[ -z "$AUTH_SECRET" ]]; then
    AUTH_SECRET="$(openssl rand -hex 32)"
    echo "==> Generated INTERNAL_AUTH_SHARED_SECRET (save this — needed to call /mcp):"
    echo "    ${AUTH_SECRET}"
  fi
fi

echo "==> Resource group ${RG} in ${LOCATION}"
az group create --name "$RG" --location "$LOCATION" --output none

echo "==> Pass 1: deploy ACR only"
az deployment group create \
  --resource-group "$RG" \
  --template-file "$INFRA/main.bicep" \
  --parameters "$INFRA/main.parameters.json" \
  --parameters deployApps=false namePrefix="$NAME_PREFIX" location="$LOCATION" \
  --output none

ACR_NAME="$(az deployment group show --resource-group "$RG" --name main \
  --query 'properties.outputs.acrName.value' -o tsv)"
echo "    ACR: ${ACR_NAME}"

echo "==> Build & push images into ${ACR_NAME}"
ACR_NAME="$ACR_NAME" IMAGE_TAG="$IMAGE_TAG" "$INFRA/scripts/build-push.sh"

echo "==> Pass 2: deploy full stack (environment, storage, both apps)"
az deployment group create \
  --resource-group "$RG" \
  --template-file "$INFRA/main.bicep" \
  --parameters "$INFRA/main.parameters.json" \
  --parameters deployApps=true namePrefix="$NAME_PREFIX" location="$LOCATION" \
               imageTag="$IMAGE_TAG" mcpExternalIngress="$MCP_EXTERNAL_INGRESS" \
               internalAuthSharedSecret="$AUTH_SECRET" \
  --output none

# --- budget alert (subscription-scoped) ---
if [[ "$BUDGET_AMOUNT" != "0" ]]; then
  BUDGET_EMAIL="${BUDGET_EMAIL:-$(az account show --query user.name -o tsv)}"
  # Budgets must start on the first of a month.
  BUDGET_START="$(date -u +%Y-%m-01)"
  echo "==> Budget alert: \$${BUDGET_AMOUNT}/mo on RG ${RG}, notifying ${BUDGET_EMAIL}"
  az deployment sub create \
    --location "$LOCATION" \
    --template-file "$INFRA/modules/budget.bicep" \
    --parameters name="${NAME_PREFIX}-demo-budget" amount="$BUDGET_AMOUNT" \
                 resourceGroupName="$RG" contactEmails="[\"${BUDGET_EMAIL}\"]" \
                 startDate="$BUDGET_START" \
    --output none || echo "    (budget deploy skipped — needs Cost Management permissions; non-fatal)"
fi

MCP_URL="$(az deployment group show --resource-group "$RG" --name main \
  --query 'properties.outputs.mcpUrl.value' -o tsv)"
API_FQDN="$(az deployment group show --resource-group "$RG" --name main \
  --query 'properties.outputs.wekaApiInternalFqdn.value' -o tsv)"

echo ""
echo "==> Deployed."
echo "    weka-mcp (public):   ${MCP_URL}"
echo "    weka-mcp MCP endpoint: ${MCP_URL}/mcp"
echo "    weka-api (internal): https://${API_FQDN}"
echo ""
if [[ "$OPEN_DEMO" == "true" ]]; then
  echo "Open demo — no header needed. Verify with:"
  echo "    AZURE_RESOURCE_GROUP=${RG} NAME_PREFIX=${NAME_PREFIX} ${INFRA}/scripts/verify-cloud.sh"
else
  echo "Verify with:"
  echo "    AZURE_RESOURCE_GROUP=${RG} NAME_PREFIX=${NAME_PREFIX} \\"
  echo "    INTERNAL_AUTH_SHARED_SECRET=${AUTH_SECRET} \\"
  echo "    ${INFRA}/scripts/verify-cloud.sh"
fi
