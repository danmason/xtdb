# Resource group setup

resource "azurerm_resource_group" "cloud_benchmark" {
  name     = "cloud-benchmark-resources"
  location = "West Europe"
}

resource "azurerm_virtual_network" "cloud_benchmark" {
  name                = "cloud-benchmark-network"
  resource_group_name = azurerm_resource_group.cloud_benchmark.name
  location            = azurerm_resource_group.cloud_benchmark.location
  address_space       = ["10.0.0.0/16"]
}

# Blob Storage Configuration

resource "azurerm_storage_account" "cloud_benchmark" {
  name                     = "xtdbazurebenchmark"
  resource_group_name      = azurerm_resource_group.cloud_benchmark.name
  location                 = azurerm_resource_group.cloud_benchmark.location
  account_tier             = "Standard"
  account_replication_type = "LRS"
}

resource "azurerm_storage_container" "cloud_benchmark" {
  name                  = "xtdbazurebenchmarkcontainer"
  storage_account_name  = azurerm_storage_account.cloud_benchmark.name
  container_access_type = "private"
}

# Service Bus Setup
resource "azurerm_eventgrid_system_topic" "cloud_benchmark" {
  name                = "cloud-benchmark-system-topic"
  location            = azurerm_resource_group.cloud_benchmark.location
  resource_group_name = azurerm_resource_group.cloud_benchmark.name

  source_arm_resource_id = azurerm_storage_account.cloud_benchmark.id
  topic_type             = "Microsoft.Storage.StorageAccounts"
}

resource "azurerm_servicebus_namespace" "cloud_benchmark" {
  name                = "cloud-benchmark-eventbus"
  location            = azurerm_resource_group.cloud_benchmark.location
  resource_group_name = azurerm_resource_group.cloud_benchmark.name
  sku                 = "Standard"
}

resource "azurerm_servicebus_topic" "cloud_benchmark" {
  name                = "cloud-benchmark-servicebus-topic"
  namespace_id        = azurerm_servicebus_namespace.cloud_benchmark.id
  default_message_ttl = "PT5M"
}

resource "azurerm_eventgrid_system_topic_event_subscription" "cloud_benchmark" {
  name                          = "cloud-benchmark-system-topic-servicebus-topic-subscription"
  system_topic                  = azurerm_eventgrid_system_topic.cloud_benchmark.name
  resource_group_name           = azurerm_resource_group.cloud_benchmark.name
  event_delivery_schema         = "EventGridSchema"
  service_bus_topic_endpoint_id = azurerm_servicebus_topic.cloud_benchmark.id
}

# Metrics Config
resource "azurerm_log_analytics_workspace" "cloud_benchmark" {
  name                = "cloud-benchmark-log-analytics-workspace"
  location            = azurerm_resource_group.cloud_benchmark.location
  resource_group_name = azurerm_resource_group.cloud_benchmark.name
  sku                 = "PerGB2018"
  retention_in_days   = 30
}

# User Assigned Identity & Roles
resource "azurerm_user_assigned_identity" "cloud_benchmark" {
  location            = azurerm_resource_group.cloud_benchmark.location
  name                = "cloud-benchmark-identity"
  resource_group_name = azurerm_resource_group.cloud_benchmark.name
}

resource "azurerm_role_assignment" "cloud_benchmark" {
  principal_id         = azurerm_user_assigned_identity.cloud_benchmark.principal_id
  role_definition_name = "AcrPull"
  scope                = azurerm_resource_group.cloud_benchmark.id
}

resource "azurerm_role_assignment" "cloud_benchmark_monitoring" {
  principal_id         = azurerm_user_assigned_identity.cloud_benchmark.principal_id
  role_definition_name = "Monitoring Metrics Publisher"
  scope                = azurerm_resource_group.cloud_benchmark.id
}

resource "azurerm_role_assignment" "cloud_benchmark_blob_contributor" {
  principal_id         = azurerm_user_assigned_identity.cloud_benchmark.principal_id
  role_definition_name = "Storage Blob Data Contributor"
  scope                = azurerm_storage_account.cloud_benchmark.id
}

resource "azurerm_role_assignment" "cloud_benchmark_eventgrid_contributor" {
  principal_id         = azurerm_user_assigned_identity.cloud_benchmark.principal_id
  role_definition_name = "EventGrid Contributor"
  scope                = azurerm_eventgrid_system_topic.cloud_benchmark.id
}

resource "azurerm_role_assignment" "cloud_benchmark_servicebus_contributor" {
  principal_id         = azurerm_user_assigned_identity.cloud_benchmark.principal_id
  role_definition_name = "Azure Service Bus Data Owner"
  scope                = azurerm_servicebus_namespace.cloud_benchmark.id
}

resource "azurerm_container_registry" "acr" {
  name                = "cloudbenchmarkregistry"
  resource_group_name = azurerm_resource_group.cloud_benchmark.name
  location            = azurerm_resource_group.cloud_benchmark.location
  sku                 = "Basic"
  admin_enabled       = true
}

## Kubernetes Cluster
resource "azurerm_kubernetes_cluster" "cloud_benchmark" {
  name                = "cloud-benchmark-cluster"
  location            = "East US"
  resource_group_name = azurerm_resource_group.cloud_benchmark.name
  dns_prefix          = "cloud-benchmark-cluster"

  default_node_pool {
    name       = "default"
    node_count = 1
    vm_size    = "Standard_D2_v2"
    upgrade_settings {
      drain_timeout_in_minutes      = 0
      max_surge                     = "10%"
      node_soak_duration_in_minutes = 0
    }
  }

  identity {
    type = "UserAssigned"
    identity_ids = [
      azurerm_user_assigned_identity.cloud_benchmark.id
    ]
  }

  oidc_issuer_enabled       = true
  workload_identity_enabled = true
}

resource "azurerm_role_assignment" "cloud_benchmark_cluster_role" {
  principal_id                     = azurerm_kubernetes_cluster.cloud_benchmark.kubelet_identity[0].object_id
  role_definition_name             = "AcrPull"
  scope                            = azurerm_container_registry.acr.id
  skip_service_principal_aad_check = true
}

resource "azurerm_federated_identity_credential" "cloud_benchmark" {
  name                = "cloud-benchmark-worker"
  resource_group_name = azurerm_resource_group.cloud_benchmark.name
  audience            = ["api://AzureADTokenExchange"]
  issuer              = azurerm_kubernetes_cluster.cloud_benchmark.oidc_issuer_url
  parent_id           = azurerm_user_assigned_identity.cloud_benchmark.id
  subject             = "system:serviceaccount:${var.kubernetes_namespace}:${var.kubernetes_service_account_name}"
}

output "user_managed_identity_client_id" {
  value = azurerm_user_assigned_identity.cloud_benchmark.client_id
}

output "storage_account_name" {
  value = azurerm_storage_account.cloud_benchmark.name
}

output "storage_account_container" {
  value = azurerm_storage_container.cloud_benchmark.name
}

output "service_bus_namespace" {
  value = azurerm_servicebus_namespace.cloud_benchmark.name
}

output "service_bus_topic" {
  value = azurerm_servicebus_topic.cloud_benchmark.name
}