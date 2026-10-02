data "google_project" "this" {}

locals {
  apis = [
    "run.googleapis.com", "aiplatform.googleapis.com", "bigquery.googleapis.com",
    "artifactregistry.googleapis.com", "iam.googleapis.com", "cloudtrace.googleapis.com", "logging.googleapis.com",
  ]
  service_name = "flight-agent"
  # Cloud Run's deterministic URL, known before the service exists; it is the audience callers mint ID tokens for
  service_url = "https://${local.service_name}-${data.google_project.this.number}.${var.region}.run.app"
}

resource "google_project_service" "apis" {
  for_each           = toset(local.apis)
  service            = each.key
  disable_on_destroy = false
}

# --- Identity: one service account per workload, least privilege ---------------------------------------------------

resource "google_service_account" "flight_agent" {
  account_id   = "flight-agent"
  display_name = "Flight agent (ADK) runtime"
}

resource "google_project_iam_member" "agent_roles" {
  for_each = toset([
    "roles/aiplatform.user",  # call Gemini on Vertex AI
    "roles/bigquery.jobUser", # run queries (billing/quotas), no data access by itself
    "roles/cloudtrace.agent", # export traces
    "roles/logging.logWriter",
  ])
  project = var.project_id
  role    = each.key
  member  = "serviceAccount:${google_service_account.flight_agent.email}"
}

# --- BigQuery data foundation: policy + fare history the agent reasons over ----------------------------------------

resource "google_bigquery_dataset" "travel" {
  dataset_id  = "travel"
  location    = var.bq_location
  description = "Travel policy and fare history for the travel agents"
  depends_on  = [google_project_service.apis]
}

# Read-only on this one dataset: the agent cannot see anything else in BigQuery
resource "google_bigquery_dataset_iam_member" "agent_reads_travel" {
  dataset_id = google_bigquery_dataset.travel.dataset_id
  role       = "roles/bigquery.dataViewer"
  member     = "serviceAccount:${google_service_account.flight_agent.email}"
}

resource "google_bigquery_table" "travel_policy" {
  dataset_id          = google_bigquery_dataset.travel.dataset_id
  table_id            = "travel_policy"
  deletion_protection = true
  schema = jsonencode([
    { name = "traveller_grade", type = "STRING", mode = "REQUIRED" },
    { name = "cabin", type = "STRING", mode = "REQUIRED" },
    { name = "max_fare_usd", type = "NUMERIC", mode = "REQUIRED" },
    { name = "max_stops", type = "INT64", mode = "REQUIRED" },
    { name = "approval_over_usd", type = "NUMERIC", mode = "REQUIRED" },
  ])
}

resource "google_bigquery_table" "route_fares" {
  dataset_id          = google_bigquery_dataset.travel.dataset_id
  table_id            = "route_fares"
  deletion_protection = true
  schema = jsonencode([
    { name = "origin", type = "STRING", mode = "REQUIRED" },
    { name = "destination", type = "STRING", mode = "REQUIRED" },
    { name = "cabin", type = "STRING", mode = "REQUIRED" },
    { name = "avg_fare_usd", type = "NUMERIC", mode = "REQUIRED" },
    { name = "p90_fare_usd", type = "NUMERIC", mode = "REQUIRED" },
    { name = "samples", type = "INT64", mode = "REQUIRED" },
  ])
}

# --- Cloud Run: the agent --------------------------------------------------------------------------------------------

resource "google_cloud_run_v2_service" "flight_agent" {
  name                = local.service_name
  location            = var.region
  ingress             = "INGRESS_TRAFFIC_ALL"
  deletion_protection = false

  template {
    service_account = google_service_account.flight_agent.email
    scaling {
      min_instance_count = 0
      max_instance_count = 5
    }
    containers {
      image = var.image
      resources {
        limits = { cpu = "1", memory = "1Gi" }
      }
      startup_probe {
        http_get { path = "/healthz" }
      }
      env {
        name  = "GOOGLE_GENAI_USE_VERTEXAI"
        value = "true"
      }
      env {
        name  = "GOOGLE_CLOUD_PROJECT"
        value = var.project_id
      }
      env {
        name  = "GOOGLE_CLOUD_LOCATION"
        value = var.region
      }
      env {
        name  = "MODEL"
        value = var.model
      }
      env {
        name  = "FLIGHT_MCP_URL"
        value = var.mcp_url
      }
      env {
        name  = "MCP_AUDIENCE"
        value = var.mcp_url
      }
      env {
        name  = "AUTH_MODE"
        value = "oidc"
      }
      env {
        name  = "OIDC_AUDIENCE"
        value = local.service_url
      }
      env {
        name  = "ALLOWED_CALLERS"
        value = var.orchestrator_service_account
      }
      env {
        name  = "A2A_PUBLIC_URL"
        value = local.service_url
      }
      env {
        name  = "POLICY_BACKEND"
        value = "bigquery"
      }
      env {
        name  = "BQ_DATASET"
        value = google_bigquery_dataset.travel.dataset_id
      }
    }
  }
  depends_on = [google_project_service.apis, google_project_iam_member.agent_roles]
}

# Platform-level auth first: Cloud Run rejects any caller that is not the orchestrator before the app sees the request
# (the app verifies the ID token again, so a mistaken IAM change alone does not open the agent).
resource "google_cloud_run_v2_service_iam_member" "orchestrator_invokes_agent" {
  name     = google_cloud_run_v2_service.flight_agent.name
  location = var.region
  role     = "roles/run.invoker"
  member   = "serviceAccount:${var.orchestrator_service_account}"
}

resource "google_cloud_run_v2_service_iam_member" "agent_invokes_mcp" {
  count    = var.mcp_service_name == "" ? 0 : 1
  name     = var.mcp_service_name
  location = var.region
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.flight_agent.email}"
}
