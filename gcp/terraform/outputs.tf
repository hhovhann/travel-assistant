output "agent_url" { value = google_cloud_run_v2_service.flight_agent.uri }
output "agent_service_account" { value = google_service_account.flight_agent.email }
output "bigquery_dataset" { value = google_bigquery_dataset.travel.dataset_id }
