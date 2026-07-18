// Subscription-scoped monthly cost budget with email alerts.
//
// A budget ALERTS (email) when spend crosses a threshold — it does NOT hard-stop
// billing. The real compute ceiling is maxReplicas on the container apps plus
// scale-to-zero; this is the tripwire that tells you if something is off.
//
// Scoped to the subscription because Microsoft.Consumption/budgets cannot be
// resource-group-scoped for actual-cost budgets in a repeatable way; the filter
// restricts it to this deployment's resource group.

targetScope = 'subscription'

@description('Budget name.')
param name string = 'weka-mcp-demo-budget'

@description('Monthly amount (in the billing currency) that triggers alerts.')
param amount int = 25

@description('Resource group to scope the budget to (only this RG counts toward it).')
param resourceGroupName string

@description('Email address(es) notified when a threshold is crossed.')
param contactEmails array

@description('First day of the budget period, YYYY-MM-01. Must be the first of a month.')
param startDate string

resource budget 'Microsoft.Consumption/budgets@2023-11-01' = {
  name: name
  properties: {
    category: 'Cost'
    amount: amount
    timeGrain: 'Monthly'
    timePeriod: {
      startDate: startDate
    }
    filter: {
      dimensions: {
        name: 'ResourceGroupName'
        operator: 'In'
        values: [
          resourceGroupName
        ]
      }
    }
    notifications: {
      // Warn at 80% of the monthly budget...
      Actual_GreaterThan_80_Percent: {
        enabled: true
        operator: 'GreaterThan'
        threshold: 80
        contactEmails: contactEmails
        thresholdType: 'Actual'
      }
      // ...and again when actual spend reaches 100%.
      Actual_GreaterThan_100_Percent: {
        enabled: true
        operator: 'GreaterThan'
        threshold: 100
        contactEmails: contactEmails
        thresholdType: 'Actual'
      }
    }
  }
}

output budgetName string = budget.name
