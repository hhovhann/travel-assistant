#!/usr/bin/env bash
# Loads the CSV seeds (the same files the local in-memory store reads) into the BigQuery tables Terraform created.
# Usage: scripts/load_bq.sh PROJECT_ID [DATASET]
set -euo pipefail
project="${1:?usage: load_bq.sh PROJECT_ID [DATASET]}"; dataset="${2:-travel}"
cd "$(dirname "$0")/../data"
for table in travel_policy route_fares; do
  bq --project_id="$project" load --replace --source_format=CSV --skip_leading_rows=1 "$dataset.$table" "$table.csv"
done
