import pytest

from flight_agent import policy


@pytest.fixture(autouse=True)
def memory_store():
    policy.set_store(policy.InMemoryPolicyStore())
    yield
    policy.set_store(None)


def test_within_policy():
    assert policy.check_travel_policy("standard", "economy", 700, 0)["status"] == "within_policy"


def test_needs_approval_above_auto_limit_but_under_cap():
    result = policy.check_travel_policy("standard", "economy", 1000, 1)
    assert result["status"] == "needs_approval"


def test_over_policy_reports_every_reason():
    result = policy.check_travel_policy("standard", "economy", 1500, 2)
    assert result["status"] == "over_policy" and len(result["reasons"]) == 2


def test_grade_and_cabin_are_case_insensitive_and_unknown_is_explicit():
    assert policy.check_travel_policy("SENIOR", "Business", 5000, 1)["status"] == "needs_approval"
    assert policy.check_travel_policy("intern", "first", 100, 0)["status"] == "unknown"


def test_route_history_normalizes_codes_and_handles_missing_routes():
    assert policy.get_route_price_history("jfk", "evn")["avg_fare_usd"] == 780
    assert policy.get_route_price_history("XXX", "YYY")["status"] == "no_history"


def test_bigquery_store_uses_parameterized_queries():
    class Row(dict):
        def items(self):
            return super().items()

    class Job:
        def result(self, max_results):
            return [Row(max_fare_usd=1200.0, max_stops=1, approval_over_usd=900.0)]

    class Client:
        project = "proj"
        queries = []

        def query(self, sql, job_config):
            self.queries.append((sql, job_config))
            return Job()

    client = Client()
    store = policy.BigQueryPolicyStore("travel", client=client)
    assert store.get_policy("standard'; DROP TABLE x;--", "economy")["max_fare_usd"] == 1200.0
    sql, config = client.queries[0]
    assert "DROP TABLE" not in sql and "`proj.travel.travel_policy`" in sql
    assert {p.name for p in config.query_parameters} == {"grade", "cabin"}
