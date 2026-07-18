// Azure Container Registry (Basic) holding the weka-api and weka-mcp images.
// admin user is enabled so the Container Apps can pull with registry credentials
// without configuring a managed identity + AcrPull role assignment (simpler for a
// self-contained deploy). Flip to managed identity later if you prefer.

@description('Globally-unique ACR name (5-50 chars, lowercase alphanumeric).')
param name string

@description('Azure region.')
param location string

@description('ACR SKU.')
@allowed([ 'Basic', 'Standard', 'Premium' ])
param sku string = 'Basic'

resource registry 'Microsoft.ContainerRegistry/registries@2023-11-01-preview' = {
  name: name
  location: location
  sku: {
    name: sku
  }
  properties: {
    adminUserEnabled: true
  }
}

output loginServer string = registry.properties.loginServer
output name string = registry.name
