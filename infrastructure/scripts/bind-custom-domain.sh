#!/usr/bin/env bash
# Bind a custom domain to the deployed weka-mcp Container App, via Bicep, in the
# two phases Azure requires. A managed TLS cert can only be issued once the
# domain's DNS validation records resolve, and an SniEnabled binding needs the
# cert to already exist — so a single apply can't do it from scratch. This wraps
# both passes and the DNS step in between.
#
#   phase A: apply with mcpCustomDomainHostname set, IssueCert=false
#            -> binds the hostname cert-less; Azure now knows the domain.
#   DNS:     you create the asuid.<sub> TXT + the CNAME (printed below).
#   phase B: apply with IssueCert=true
#            -> issues the Azure-managed cert and binds it (SniEnabled).
#
# Idempotent: re-running phase B (or a normal deploy.sh with the two params set)
# reconciles to the same bound state.
#
# Config via env:
#   AZURE_RESOURCE_GROUP   default weka-mcp-rg
#   AZURE_NAME_PREFIX      default wekamcp        (must match deploy.sh)
#   AZURE_LOCATION         default eastus2
#   IMAGE_TAG              default latest
#   MCP_CUSTOM_DOMAIN      required, e.g. weka-mcp.example.com
#   PHASE                  a | b | auto   (default auto: run A, guide DNS, then B)
set -euo pipefail

RG="${AZURE_RESOURCE_GROUP:-weka-mcp-rg}"
NAME_PREFIX="${AZURE_NAME_PREFIX:-wekamcp}"
LOCATION="${AZURE_LOCATION:-eastus2}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
PHASE="${PHASE:-auto}"
DOMAIN="${MCP_CUSTOM_DOMAIN:?set MCP_CUSTOM_DOMAIN, e.g. weka-mcp.example.com}"

INFRA="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MCP_APP="${NAME_PREFIX}-weka-mcp"

apply() {  # $1 = issueCert (true|false)
  az deployment group create \
    --resource-group "$RG" \
    --template-file "$INFRA/main.bicep" \
    --parameters "$INFRA/main.parameters.json" \
    --parameters deployApps=true namePrefix="$NAME_PREFIX" location="$LOCATION" \
                 imageTag="$IMAGE_TAG" \
                 mcpCustomDomainHostname="$DOMAIN" mcpCustomDomainIssueCert="$1" \
    --output none
}

phase_a() {
  echo "==> Phase A: bind '${DOMAIN}' cert-less so Azure accepts the domain"
  apply false

  VERIFY_ID="$(az containerapp show -g "$RG" -n "$MCP_APP" \
    --query 'properties.customDomainVerificationId' -o tsv)"
  FQDN="$(az containerapp show -g "$RG" -n "$MCP_APP" \
    --query 'properties.configuration.ingress.fqdn' -o tsv)"

  SUB="${DOMAIN%%.*}"
  echo ""
  echo "==> Create these DNS records for ${DOMAIN}, then run PHASE=b (or wait for auto):"
  echo "    TXT    asuid.${SUB}    ${VERIFY_ID}"
  echo "    CNAME  ${SUB}          ${FQDN}"
  echo ""
}

phase_b() {
  echo "==> Phase B: issue the Azure-managed cert and bind it (SniEnabled)"
  echo "    (cert issuance runs a DNS challenge and can take up to ~20 min)"
  apply true
  echo ""
  echo "==> Bound. MCP endpoint: https://${DOMAIN}/mcp"
  echo "    Verify: curl -s -o /dev/null -w '%{http_code}\\n' https://${DOMAIN}/healthz"
}

case "$PHASE" in
  a) phase_a ;;
  b) phase_b ;;
  auto)
    phase_a
    echo "==> Create the DNS records above. Waiting for the TXT to resolve..."
    for i in $(seq 1 60); do
      got="$(dig +short TXT "asuid.${DOMAIN%%.*}.${DOMAIN#*.}" 2>/dev/null | tr -d '"' || true)"
      if [[ -n "$got" ]]; then echo "    TXT resolves; proceeding."; break; fi
      echo "    ($i/60) not yet; sleeping 30s"; sleep 30
    done
    phase_b ;;
  *) echo "PHASE must be a|b|auto" >&2; exit 1 ;;
esac
