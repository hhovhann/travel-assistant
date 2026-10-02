variable "project_id" { type = string }

variable "region" {
  type    = string
  default = "europe-west1"
}

variable "image" {
  description = "Flight agent image, e.g. europe-west1-docker.pkg.dev/PROJECT/travel/flight-agent-adk:TAG"
  type        = string
}

variable "mcp_url" {
  description = "Base URL of the flight MCP server (Cloud Run or internal)"
  type        = string
}

variable "mcp_service_name" {
  description = "Cloud Run service name of the MCP server; the agent's service account gets run.invoker on it. Empty if the MCP server is not on Cloud Run."
  type        = string
  default     = ""
}

variable "model" {
  type    = string
  default = "gemini-2.5-flash"
}

variable "orchestrator_service_account" {
  description = "Service account email of the orchestrator; the only identity allowed to call the flight agent"
  type        = string
}

variable "bq_location" {
  type    = string
  default = "EU"
}
