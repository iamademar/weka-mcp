// Azure-managed TLS certificate for a weka-mcp custom domain.
//
// Free, auto-renewed cert issued by Container Apps for one hostname. It can only
// be created AFTER the domain's DNS validation records resolve (the asuid.<sub>
// TXT + the CNAME to the app's default FQDN), because issuance performs a DNS
// challenge. That ordering is why custom domains are a two-phase apply — see
// scripts/bind-custom-domain.sh and the container-app-weka-mcp module header.
//
// This module is only instantiated once a hostname is supplied AND the caller
// has opted into cert creation (phase B). Phase A binds the hostname with no
// cert (bindingType: Disabled) purely so Azure surfaces the verification id.

@description('Container Apps managed environment name (the cert is a child of it).')
param environmentName string

@description('Azure region. Must match the environment.')
param location string

@description('Custom hostname to issue the cert for, e.g. weka-mcp.example.com.')
param hostname string

// Deterministic name (no timestamp/hash) so repeat deploys are idempotent and
// the mcp module can reference this exact cert by id.
var certName = 'mc-${replace(hostname, '.', '-')}'

resource environment 'Microsoft.App/managedEnvironments@2024-03-01' existing = {
  name: environmentName
}

resource managedCert 'Microsoft.App/managedEnvironments/managedCertificates@2024-03-01' = {
  parent: environment
  name: certName
  location: location
  properties: {
    subjectName: hostname
    // CNAME challenge: matches the CNAME record the domain already points at the
    // app's default FQDN. (Use 'HTTP' only for apex domains that use an A record.)
    domainControlValidation: 'CNAME'
  }
}

output certificateId string = managedCert.id
output certificateName string = managedCert.name
