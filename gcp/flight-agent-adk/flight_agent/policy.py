"""Travel-policy and fare-history tools backed by BigQuery (or the same CSV seeds in memory, for local runs/tests).

Why a data foundation: the agent should not just list fares, it should say "this is over your policy limit" or
"this fare is 40% above the route's typical price". Those facts live in BigQuery tables, so they are governed,
queryable by analysts, and updatable without redeploying the agent.
"""

from __future__ import annotations

import csv
import os
from pathlib import Path
from typing import Any, Protocol

DATA_DIR = Path(os.getenv("DATA_DIR", Path(__file__).resolve().parents[2] / "data"))


class PolicyStore(Protocol):
    def get_policy(self, grade: str, cabin: str) -> dict[str, Any] | None: ...

    def get_route_fares(self, origin: str, destination: str, cabin: str) -> dict[str, Any] | None: ...


class InMemoryPolicyStore:
    def __init__(self, data_dir: Path = DATA_DIR) -> None:
        self._policy = {(r["traveller_grade"], r["cabin"]): r for r in _read(data_dir / "travel_policy.csv")}
        self._fares = {(r["origin"], r["destination"], r["cabin"]): r for r in _read(data_dir / "route_fares.csv")}

    def get_policy(self, grade: str, cabin: str) -> dict[str, Any] | None:
        row = self._policy.get((grade, cabin))
        return None if row is None else {
            "max_fare_usd": float(row["max_fare_usd"]),
            "max_stops": int(row["max_stops"]),
            "approval_over_usd": float(row["approval_over_usd"]),
        }

    def get_route_fares(self, origin: str, destination: str, cabin: str) -> dict[str, Any] | None:
        row = self._fares.get((origin, destination, cabin))
        return None if row is None else {
            "avg_fare_usd": float(row["avg_fare_usd"]),
            "p90_fare_usd": float(row["p90_fare_usd"]),
            "samples": int(row["samples"]),
        }


class BigQueryPolicyStore:
    """Parameterized queries only; the client uses Application Default Credentials (the Cloud Run service account)."""

    def __init__(self, dataset: str, client: Any = None) -> None:
        from google.cloud import bigquery

        self._bq = bigquery
        self._client = client or bigquery.Client()
        self._dataset = f"{self._client.project}.{dataset}"

    def _one(self, sql: str, params: list[Any]) -> dict[str, Any] | None:
        job = self._client.query(sql, job_config=self._bq.QueryJobConfig(query_parameters=params))
        rows = list(job.result(max_results=1))
        return None if not rows else dict(rows[0].items())

    def get_policy(self, grade: str, cabin: str) -> dict[str, Any] | None:
        row = self._one(
            f"SELECT CAST(max_fare_usd AS FLOAT64) AS max_fare_usd, max_stops, "
            f"CAST(approval_over_usd AS FLOAT64) AS approval_over_usd "
            f"FROM `{self._dataset}.travel_policy` WHERE traveller_grade = @grade AND cabin = @cabin LIMIT 1",
            [self._bq.ScalarQueryParameter("grade", "STRING", grade), self._bq.ScalarQueryParameter("cabin", "STRING", cabin)],
        )
        return row

    def get_route_fares(self, origin: str, destination: str, cabin: str) -> dict[str, Any] | None:
        return self._one(
            f"SELECT CAST(avg_fare_usd AS FLOAT64) AS avg_fare_usd, CAST(p90_fare_usd AS FLOAT64) AS p90_fare_usd, samples "
            f"FROM `{self._dataset}.route_fares` WHERE origin = @o AND destination = @d AND cabin = @c LIMIT 1",
            [
                self._bq.ScalarQueryParameter("o", "STRING", origin),
                self._bq.ScalarQueryParameter("d", "STRING", destination),
                self._bq.ScalarQueryParameter("c", "STRING", cabin),
            ],
        )


def _read(path: Path) -> list[dict[str, str]]:
    with path.open(newline="") as handle:
        return list(csv.DictReader(handle))


_store: PolicyStore | None = None


def get_store() -> PolicyStore:
    global _store
    if _store is None:
        backend = os.getenv("POLICY_BACKEND", "memory")
        _store = BigQueryPolicyStore(os.getenv("BQ_DATASET", "travel")) if backend == "bigquery" else InMemoryPolicyStore()
    return _store


def set_store(store: PolicyStore | None) -> None:
    """Test hook."""
    global _store
    _store = store


# ---- ADK tools: plain functions, the type hints and docstrings become the tool schema ----

def check_travel_policy(traveller_grade: str, cabin: str, fare_usd: float, stops: int) -> dict[str, Any]:
    """Checks a flight option against the company travel policy.

    Args:
        traveller_grade: One of "standard", "senior", "executive".
        cabin: One of "economy", "premium_economy", "business".
        fare_usd: Total fare for one traveller in USD.
        stops: Number of stops on the longest leg.

    Returns:
        A dict with `status` ("within_policy", "needs_approval" or "over_policy") and the reasons.
    """
    policy = get_store().get_policy(traveller_grade.lower(), cabin.lower())
    if policy is None:
        return {"status": "unknown", "reasons": [f"No policy defined for grade={traveller_grade}, cabin={cabin}"]}
    reasons = []
    if fare_usd > policy["max_fare_usd"]:
        reasons.append(f"fare ${fare_usd:.0f} exceeds the ${policy['max_fare_usd']:.0f} limit")
    if stops > policy["max_stops"]:
        reasons.append(f"{stops} stops exceeds the maximum of {policy['max_stops']}")
    if reasons:
        return {"status": "over_policy", "reasons": reasons}
    if fare_usd > policy["approval_over_usd"]:
        return {"status": "needs_approval", "reasons": [f"fare ${fare_usd:.0f} is above the ${policy['approval_over_usd']:.0f} auto-approval limit"]}
    return {"status": "within_policy", "reasons": []}


def get_route_price_history(origin: str, destination: str, cabin: str = "economy") -> dict[str, Any]:
    """Looks up the typical fare for a route from historical bookings, to judge whether an offer is a good price.

    Args:
        origin: Origin IATA airport code, e.g. "JFK".
        destination: Destination IATA airport code, e.g. "EVN".
        cabin: One of "economy", "premium_economy", "business".

    Returns:
        A dict with `avg_fare_usd`, `p90_fare_usd` and `samples`, or `status: "no_history"`.
    """
    fares = get_store().get_route_fares(origin.upper(), destination.upper(), cabin.lower())
    return fares or {"status": "no_history", "route": f"{origin.upper()}-{destination.upper()}", "cabin": cabin}
