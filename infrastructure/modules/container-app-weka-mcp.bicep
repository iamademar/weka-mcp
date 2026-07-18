// weka-mcp Container App — the TypeScript MCP server (HTTP transport, dist/http.js).
//
// This is the path weka-mcp/src/http.ts:18-19 and config.ts:11 reference by name.
//
// PUBLIC ingress (external: true): reachable from the internet so an external MCP
// client can call POST /mcp. Because it is public, the X-Internal-Auth shared
// secret is MANDATORY here — requireInternalAuth (http.ts) enforces it whenever
// INTERNAL_AUTH_SHARED_SECRET is set. A built-in per-IP rate limit (300/60s)
// backstops abuse. GET /healthz stays open (probes) and leaks nothing.
//
// It reaches weka-api over the environment-internal FQDN (never public).

@description('Container App name.')
param name string

@description('Azure region.')
param location string

@description('Resource ID of the Container Apps managed environment.')
param environmentId string

@description('Full image reference, e.g. myacr.azurecr.io/weka-mcp:latest.')
param image string

@description('ACR login server (for the registry credential).')
param registryServer string

@description('ACR admin username.')
param registryUsername string

@description('ACR admin password.')
@secure()
param registryPassword string

@description('Internal FQDN of weka-api (from the api module output). No scheme/port.')
param wekaApiInternalFqdn string

@description('Shared secret required in the X-Internal-Auth header on POST /mcp.')
@secure()
param internalAuthSharedSecret string

@description('vCPU for weka-mcp (thin Node forwarder).')
param cpu string = '0.5'

@description('Memory for weka-mcp. Pairs with cpu per Container Apps ratios (0.5 -> 1Gi).')
param memory string = '1Gi'

param minReplicas int = 0
param maxReplicas int = 3

@description('Whether weka-mcp gets public (external) ingress.')
param externalIngress bool = true

// --- custom domain (optional, two-phase) ---
// Empty hostname => no custom domain (the default; ingress is unchanged).
// With a hostname:
//   phase A (certificateId empty) => bind the hostname with bindingType 'Disabled'
//     and NO cert, so Azure accepts the domain and the DNS validation can settle.
//   phase B (certificateId set)   => bind with 'SniEnabled' + the managed cert.
// The managed cert can only be created after DNS validates, hence the two phases.
// See scripts/bind-custom-domain.sh.
@description('Custom hostname for weka-mcp, e.g. weka-mcp.example.com. Empty = none.')
param customDomainHostname string = ''

@description('Managed-certificate resource id to bind to the custom hostname. Empty = phase A (bind without a cert).')
param customDomainCertificateId string = ''

param wekaApiTimeoutMs string = '210000'

@description('Concurrent requests per replica before a new one is added (HTTP scale rule).')
param scaleConcurrentRequests int = 20

var registryPasswordSecretName = 'acr-password'
var internalAuthSecretName = 'internal-auth-shared-secret'

// customDomains block for the ingress. Empty array (no hostname) leaves ingress
// exactly as before. With a hostname: SniEnabled once we have a cert, else the
// cert-less Disabled binding that lets Azure accept the domain (phase A).
var hasCustomDomain = !empty(customDomainHostname)
var customDomains = hasCustomDomain ? [
  empty(customDomainCertificateId) ? {
    name: customDomainHostname
    bindingType: 'Disabled'
  } : {
    name: customDomainHostname
    bindingType: 'SniEnabled'
    certificateId: customDomainCertificateId
  }
] : []

// Open (demo) mode when no secret is provided: requireInternalAuth (http.ts)
// skips the check when INTERNAL_AUTH_SHARED_SECRET is unset, so we neither
// create the secret nor set the env var — POST /mcp is then reachable with no
// header, which is what lets ChatGPT/Claude connect with zero setup.
var authEnabled = !empty(internalAuthSharedSecret)

resource wekaMcp 'Microsoft.App/containerApps@2024-03-01' = {
  name: name
  location: location
  properties: {
    managedEnvironmentId: environmentId
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: {
        external: externalIngress
        targetPort: 3000
        transport: 'auto'
        allowInsecure: false
        customDomains: customDomains
      }
      secrets: concat([
        {
          name: registryPasswordSecretName
          value: registryPassword
        }
      ], authEnabled ? [
        {
          name: internalAuthSecretName
          value: internalAuthSharedSecret
        }
      ] : [])
      registries: [
        {
          server: registryServer
          username: registryUsername
          passwordSecretRef: registryPasswordSecretName
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'weka-mcp'
          image: image
          resources: {
            cpu: json(cpu)
            memory: memory
          }
          env: concat([
            // Internal ingress terminates TLS on 443 and maps to weka-api's
            // targetPort 7070 — so this is https:// with NO :7070 suffix,
            // unlike compose's http://weka-api:7070.
            { name: 'WEKA_API_URL', value: 'https://${wekaApiInternalFqdn}' }
            { name: 'MCP_HTTP_PORT', value: '3000' }
            { name: 'WEKA_API_TIMEOUT_MS', value: wekaApiTimeoutMs }
          ], authEnabled ? [
            { name: 'INTERNAL_AUTH_SHARED_SECRET', secretRef: internalAuthSecretName }
          ] : [])
          probes: [
            {
              type: 'Liveness'
              httpGet: {
                path: '/healthz'
                port: 3000
              }
              periodSeconds: 30
              failureThreshold: 3
            }
            {
              type: 'Readiness'
              httpGet: {
                path: '/healthz'
                port: 3000
              }
              periodSeconds: 10
              failureThreshold: 3
            }
          ]
        }
      ]
      scale: {
        minReplicas: minReplicas
        maxReplicas: maxReplicas
        // Only add a replica once a replica is handling this many concurrent
        // requests; combined with minReplicas: 0 this scales up on real load
        // and back to zero when idle. maxReplicas is the hard compute ceiling.
        rules: [
          {
            name: 'http-concurrency'
            http: {
              metadata: {
                concurrentRequests: '${scaleConcurrentRequests}'
              }
            }
          }
        ]
      }
    }
  }
}

output fqdn string = wekaMcp.properties.configuration.ingress.fqdn
output name string = wekaMcp.name
