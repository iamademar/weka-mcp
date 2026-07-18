// Orchestrates the standalone WEKA-over-MCP stack on Azure Container Apps.
//
//   ACR ─▶ environment ─▶ storage(Azure Files) ─▶ weka-api(internal) ─▶ weka-mcp(public)
//
// Two-pass deploy (see scripts/deploy.sh): the container apps reference image tags
// that must already exist in ACR, so pass 1 provisions ACR only (deployApps=false),
// then images are pushed, then pass 2 provisions everything (deployApps=true).

targetScope = 'resourceGroup'

@description('Azure region for all resources.')
param location string = resourceGroup().location

@description('Base name for derived resource names (lowercase alphanumeric).')
@minLength(3)
@maxLength(11)
param namePrefix string = 'wekamcp'

@description('If false, deploy ACR only (pass 1). If true, deploy the full stack (pass 2).')
param deployApps bool = false

@description('Image tag for both images (e.g. a git sha or "latest").')
param imageTag string = 'latest'

@description('Shared secret for the X-Internal-Auth header on weka-mcp POST /mcp. Leave EMPTY for open demo mode (no auth — anyone can connect from ChatGPT/Claude with zero setup); set a value to require the header.')
@secure()
param internalAuthSharedSecret string = ''

@description('Whether weka-mcp gets public ingress.')
param mcpExternalIngress bool = true

@description('Optional custom hostname for weka-mcp, e.g. weka-mcp.example.com. Empty = use only the default *.azurecontainerapps.io FQDN. See infrastructure/README.md for the two-phase DNS/cert flow.')
param mcpCustomDomainHostname string = ''

@description('When true (and a hostname is set), create the Azure-managed TLS cert and bind it (phase B). Leave false for the first apply (phase A) so DNS can validate before the cert is issued.')
param mcpCustomDomainIssueCert bool = false

@description('Adopt an EXISTING managed-certificate resource id instead of creating one. Set this when a cert for the hostname was already issued (e.g. via `az containerapp hostname bind`) — Azure refuses a second cert with the same subject, so this lets Bicep own the binding without a duplicate. Takes precedence over mcpCustomDomainIssueCert. Empty = ignore.')
param mcpCustomDomainExistingCertId string = ''

@description('Max upload size (MB) accepted by weka-api. Kept small for the public demo so nobody bloats the shared volume.')
param maxUploadMb string = '20'

@description('Concurrent requests per weka-mcp replica before scaling up (HTTP scale rule).')
param scaleConcurrentRequests int = 20

// --- sizing (capped for a bounded public demo: max 1 replica each, both
//     scale-to-zero, so idle cost is ~$10/mo and the compute ceiling is fixed).
param apiCpu string = '2.0'
param apiMemory string = '4Gi'
param apiMinReplicas int = 0
param apiMaxReplicas int = 1
param mcpCpu string = '0.5'
param mcpMemory string = '1Gi'
param mcpMinReplicas int = 0
param mcpMaxReplicas int = 1

// Derived names. ACR + storage must be globally unique -> append a short hash.
var suffix = substring(uniqueString(resourceGroup().id), 0, 6)
var acrName = toLower('${namePrefix}acr${suffix}')
var storageName = toLower('${namePrefix}stor${suffix}')
var environmentBaseName = namePrefix

// ---------------------------------------------------------------------------
// Pass 1 + 2: registry
// ---------------------------------------------------------------------------
module acr 'modules/acr.bicep' = {
  name: 'acr'
  params: {
    name: acrName
    location: location
  }
}

// Pull the admin credentials for the apps to authenticate to ACR.
resource acrResource 'Microsoft.ContainerRegistry/registries@2023-11-01-preview' existing = {
  name: acrName
}

// ---------------------------------------------------------------------------
// Pass 2 only: environment, storage, both apps
// ---------------------------------------------------------------------------
module environment 'modules/environment.bicep' = if (deployApps) {
  name: 'environment'
  params: {
    name: environmentBaseName
    location: location
  }
}

module storage 'modules/storage.bicep' = if (deployApps) {
  name: 'storage'
  params: {
    storageAccountName: storageName
    location: location
    environmentName: environment!.outputs.environmentName
  }
}

module wekaApi 'modules/container-app-weka-api.bicep' = if (deployApps) {
  name: 'weka-api'
  params: {
    name: '${namePrefix}-weka-api'
    location: location
    environmentId: environment!.outputs.environmentId
    image: '${acr.outputs.loginServer}/weka-api:${imageTag}'
    registryServer: acr.outputs.loginServer
    registryUsername: acrResource.listCredentials().username
    registryPassword: acrResource.listCredentials().passwords[0].value
    environmentStorageName: storage!.outputs.environmentStorageName
    cpu: apiCpu
    memory: apiMemory
    minReplicas: apiMinReplicas
    maxReplicas: apiMaxReplicas
    maxUploadMb: maxUploadMb
  }
}

// Managed cert — phase B only: created once a hostname is supplied AND the
// caller opts in (DNS having already validated). Phase A leaves this off so the
// mcp app binds the hostname cert-less and Azure can settle DNS validation.
// If an existing cert id is supplied we adopt it and create nothing (Azure
// rejects a duplicate cert with the same subject name).
var adoptExistingCert = !empty(mcpCustomDomainExistingCertId)
var wantMcpCert = deployApps && !empty(mcpCustomDomainHostname) && mcpCustomDomainIssueCert && !adoptExistingCert
module mcpCustomCert 'modules/managed-certificate.bicep' = if (wantMcpCert) {
  name: 'mcp-custom-cert'
  params: {
    environmentName: environment!.outputs.environmentName
    location: location
    hostname: mcpCustomDomainHostname
  }
}
// The cert id to bind: the adopted existing one, else the one we just created,
// else empty (phase A: cert-less Disabled binding).
var mcpCertId = adoptExistingCert ? mcpCustomDomainExistingCertId : (wantMcpCert ? mcpCustomCert!.outputs.certificateId : '')

module wekaMcp 'modules/container-app-weka-mcp.bicep' = if (deployApps) {
  name: 'weka-mcp'
  params: {
    name: '${namePrefix}-weka-mcp'
    location: location
    environmentId: environment!.outputs.environmentId
    image: '${acr.outputs.loginServer}/weka-mcp:${imageTag}'
    registryServer: acr.outputs.loginServer
    registryUsername: acrResource.listCredentials().username
    registryPassword: acrResource.listCredentials().passwords[0].value
    wekaApiInternalFqdn: wekaApi!.outputs.internalFqdn
    internalAuthSharedSecret: internalAuthSharedSecret
    externalIngress: mcpExternalIngress
    customDomainHostname: mcpCustomDomainHostname
    customDomainCertificateId: mcpCertId
    cpu: mcpCpu
    memory: mcpMemory
    minReplicas: mcpMinReplicas
    maxReplicas: mcpMaxReplicas
    scaleConcurrentRequests: scaleConcurrentRequests
  }
}

// ---------------------------------------------------------------------------
// Outputs
// ---------------------------------------------------------------------------
output acrLoginServer string = acr.outputs.loginServer
output acrName string = acr.outputs.name
output mcpFqdn string = deployApps ? wekaMcp!.outputs.fqdn : ''
output wekaApiInternalFqdn string = deployApps ? wekaApi!.outputs.internalFqdn : ''
output mcpUrl string = deployApps ? 'https://${wekaMcp!.outputs.fqdn}' : ''
// The public URL to hand to MCP clients: the custom domain once bound, else the
// default Azure FQDN. In phase A the domain is bound but has no cert yet, so the
// custom URL only serves TLS after phase B completes.
output mcpCustomDomainUrl string = (deployApps && !empty(mcpCustomDomainHostname)) ? 'https://${mcpCustomDomainHostname}' : ''
