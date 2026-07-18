// weka-api Container App — the Java/Javalin WEKA REST service.
//
// INTERNAL-ONLY ingress (external: false): no public DNS. Reachable only from
// inside the managed environment, i.e. only by weka-mcp. This is the user's hard
// rule — weka-api is never on the public internet.
//
// Scale-to-zero (minReplicas: 0, maxReplicas: 1): ~$0 compute when idle; first
// call after idle absorbs a JVM + WEKA cold start under weka-mcp's 210s client
// timeout. min=0/max=1 also means only ever one replica, so the Azure Files mount
// has no concurrent-writer contention.

@description('Container App name.')
param name string

@description('Azure region.')
param location string

@description('Resource ID of the Container Apps managed environment.')
param environmentId string

@description('Full image reference, e.g. myacr.azurecr.io/weka-api:latest.')
param image string

@description('ACR login server (for the registry credential).')
param registryServer string

@description('ACR admin username.')
param registryUsername string

@description('ACR admin password.')
@secure()
param registryPassword string

@description('Name of the environment storage link (Azure Files) to mount.')
param environmentStorageName string

@description('Mount path for the Azure Files share inside the container.')
param mountPath string = '/app/state'

@description('vCPU for weka-api (WEKA ML needs headroom).')
param cpu string = '2.0'

@description('Memory for weka-api. Must pair with cpu per Container Apps ratios (2.0 -> 4Gi).')
param memory string = '4Gi'

param minReplicas int = 0
param maxReplicas int = 1

@description('JVM options; sizes the heap under the container memory cap.')
param javaToolOptions string = '-XX:MaxRAMPercentage=75.0'

param maxUploadMb string = '100'
param logLevel string = 'INFO'

var registryPasswordSecretName = 'acr-password'

resource wekaApi 'Microsoft.App/containerApps@2024-03-01' = {
  name: name
  location: location
  properties: {
    managedEnvironmentId: environmentId
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: {
        external: false          // INTERNAL ONLY
        targetPort: 7070
        transport: 'http'
        allowInsecure: false
      }
      secrets: [
        {
          name: registryPasswordSecretName
          value: registryPassword
        }
      ]
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
          name: 'weka-api'
          image: image
          resources: {
            cpu: json(cpu)
            memory: memory
          }
          env: [
            { name: 'PORT', value: '7070' }
            { name: 'MODELS_DIR', value: '${mountPath}/models' }
            { name: 'DATA_DIR', value: '${mountPath}/data' }
            { name: 'MAX_UPLOAD_MB', value: maxUploadMb }
            { name: 'LOG_LEVEL', value: logLevel }
            { name: 'JAVA_TOOL_OPTIONS', value: javaToolOptions }
          ]
          volumeMounts: [
            {
              volumeName: 'weka-state'
              mountPath: mountPath
            }
          ]
          probes: [
            {
              // Startup probe with a generous failureThreshold so Container Apps
              // does not kill the JVM mid-boot on a cold start.
              type: 'Startup'
              httpGet: {
                path: '/health'
                port: 7070
              }
              initialDelaySeconds: 5
              periodSeconds: 5
              failureThreshold: 30   // ~150s grace
            }
            {
              type: 'Liveness'
              httpGet: {
                path: '/health'
                port: 7070
              }
              periodSeconds: 30
              failureThreshold: 3
            }
          ]
        }
      ]
      volumes: [
        {
          name: 'weka-state'
          storageType: 'AzureFile'
          storageName: environmentStorageName
        }
      ]
      scale: {
        minReplicas: minReplicas
        maxReplicas: maxReplicas
      }
    }
  }
}

// Internal FQDN weka-mcp uses as WEKA_API_URL (https, ingress maps 443 -> 7070).
output internalFqdn string = wekaApi.properties.configuration.ingress.fqdn
output name string = wekaApi.name
