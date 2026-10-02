import pytest
from starlette.applications import Starlette
from starlette.responses import PlainTextResponse
from starlette.routing import Route
from starlette.testclient import TestClient

from flight_agent.auth import BearerAuthMiddleware, OutboundAuth, oidc_verifier, token_verifier


def _client(verifier):
    async def secret(request):
        return PlainTextResponse(f"hello {request.scope['state']['caller']}")

    async def card(_):
        return PlainTextResponse("card")

    app = Starlette(routes=[Route("/", secret, methods=["POST"]), Route("/.well-known/agent-card.json", card)])
    app.add_middleware(BearerAuthMiddleware, verifier=verifier)
    return TestClient(app)


def test_missing_wrong_and_malformed_credentials_get_401():
    client = _client(token_verifier("s3cret"))
    assert client.post("/").status_code == 401
    assert client.post("/", headers={"Authorization": "Bearer nope"}).status_code == 401
    assert client.post("/", headers={"Authorization": "Basic s3cret"}).status_code == 401
    response = client.post("/", headers={"Authorization": "Bearer"})
    assert response.status_code == 401 and response.headers["www-authenticate"] == "Bearer"


def test_valid_token_passes_and_agent_card_stays_public():
    client = _client(token_verifier("s3cret"))
    assert client.post("/", headers={"Authorization": "Bearer s3cret"}).text == "hello internal-token"
    assert client.get("/.well-known/agent-card.json").status_code == 200


def test_token_mode_requires_a_token():
    with pytest.raises(ValueError):
        token_verifier("")


def test_oidc_allow_list(monkeypatch):
    from google.oauth2 import id_token

    claims = {"email": "orchestrator@proj.iam.gserviceaccount.com", "email_verified": True}
    monkeypatch.setattr(id_token, "verify_oauth2_token", lambda token, req, audience: dict(claims))
    verify = oidc_verifier("https://flight-agent", frozenset({"orchestrator@proj.iam.gserviceaccount.com"}))
    assert verify("any") == "orchestrator@proj.iam.gserviceaccount.com"

    claims["email"] = "attacker@proj.iam.gserviceaccount.com"  # valid Google token, wrong caller
    assert verify("any") is None
    claims.update(email="orchestrator@proj.iam.gserviceaccount.com", email_verified=False)
    assert verify("any") is None

    def bad(token, req, audience):
        raise ValueError("Token expired")

    monkeypatch.setattr(id_token, "verify_oauth2_token", bad)
    assert verify("any") is None


def test_oidc_mode_requires_audience_and_callers():
    with pytest.raises(ValueError):
        oidc_verifier("", frozenset())


def test_outbound_id_token_is_cached():
    calls = []
    auth = OutboundAuth("oidc", audience="https://mcp", _fetch=lambda aud: calls.append(aud) or "tok")
    assert auth.header() == {"Authorization": "Bearer tok"}
    auth.header()
    assert calls == ["https://mcp"]


def test_outbound_token_mode():
    assert OutboundAuth("token", token="abc").header() == {"Authorization": "Bearer abc"}
    assert OutboundAuth("token").header() == {}
