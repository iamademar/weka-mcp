#!/usr/bin/env bash
# Build both images in the cloud with `az acr build` (no local Docker; ACR builds
# linux/amd64 regardless of your host arch) and tag them with $IMAGE_TAG.
#
# Requires: az login; the ACR must already exist (deploy.sh pass 1 creates it).
#
# Usage:
#   ACR_NAME=wekamcpacr123 IMAGE_TAG=latest ./build-push.sh
set -euo pipefail

ACR_NAME="${ACR_NAME:?set ACR_NAME (the registry name, not the login server)}"
IMAGE_TAG="${IMAGE_TAG:-latest}"

# Resolve repo root (this script lives in infrastructure/scripts/).
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

echo "==> Building weka-api:${IMAGE_TAG} in ACR ${ACR_NAME}"
az acr build --registry "$ACR_NAME" --image "weka-api:${IMAGE_TAG}" "$ROOT/weka-api"

echo "==> Building weka-mcp:${IMAGE_TAG} in ACR ${ACR_NAME}"
az acr build --registry "$ACR_NAME" --image "weka-mcp:${IMAGE_TAG}" "$ROOT/weka-mcp"

echo "==> Done. Images pushed:"
echo "    ${ACR_NAME}.azurecr.io/weka-api:${IMAGE_TAG}"
echo "    ${ACR_NAME}.azurecr.io/weka-mcp:${IMAGE_TAG}"
