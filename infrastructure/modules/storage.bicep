// Storage account + Azure Files share, linked to the Container Apps environment as
// a named storage. weka-api mounts this share so serialized models (/app/models)
// and uploaded datasets (/app/data) survive replica restarts / scale-to-zero.
//
// One share is mounted at /app/state; MODELS_DIR and DATA_DIR point at subdirs
// (models/ and data/) created on first write by weka-api's DatasetService /
// ModelService (both call Files.createDirectories).

@description('Globally-unique storage account name (3-24 chars, lowercase alphanumeric).')
param storageAccountName string

@description('Azure region.')
param location string

@description('Name of the parent Container Apps managed environment.')
param environmentName string

@description('File share name.')
param shareName string = 'weka-share'

@description('Name of the environment storage link weka-api references.')
param environmentStorageName string = 'weka-state'

@description('Share quota in GB.')
param shareQuotaGb int = 16

resource storageAccount 'Microsoft.Storage/storageAccounts@2023-05-01' = {
  name: storageAccountName
  location: location
  sku: {
    name: 'Standard_LRS'
  }
  kind: 'StorageV2'
  properties: {
    allowBlobPublicAccess: false
    minimumTlsVersion: 'TLS1_2'
  }
}

resource fileService 'Microsoft.Storage/storageAccounts/fileServices@2023-05-01' = {
  parent: storageAccount
  name: 'default'
}

resource share 'Microsoft.Storage/storageAccounts/fileServices/shares@2023-05-01' = {
  parent: fileService
  name: shareName
  properties: {
    shareQuota: shareQuotaGb
    enabledProtocols: 'SMB'
  }
}

// Reference the existing managed environment to attach the storage link as a child.
resource environment 'Microsoft.App/managedEnvironments@2024-03-01' existing = {
  name: environmentName
}

resource envStorage 'Microsoft.App/managedEnvironments/storages@2024-03-01' = {
  parent: environment
  name: environmentStorageName
  properties: {
    azureFile: {
      accountName: storageAccount.name
      accountKey: storageAccount.listKeys().keys[0].value
      shareName: share.name
      accessMode: 'ReadWrite'
    }
  }
}

output environmentStorageName string = envStorage.name
output storageAccountName string = storageAccount.name
