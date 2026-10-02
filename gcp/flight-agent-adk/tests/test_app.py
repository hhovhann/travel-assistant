from starlette.testclient import TestClient

from flight_agent.app import create_app
from flight_agent.config import Settings


def _app():
    return create_app(Settings(auth_mode="token", internal_token="s3cret", public_url="http://localhost:8080"))


def test_agent_card_is_public_and_describes_the_agent():
    with TestClient(_app()) as client:
        response = client.get("/.well-known/agent-card.json")
        assert response.status_code == 200
        card = response.json()
        # the A2A 0.3 shape the Java SDK client reads, with the bearer scheme declared
        assert card["name"] == "Flight Agent" and card["protocolVersion"] == "0.3.0"
        assert card["url"] == "http://localhost:8080" and card["preferredTransport"] == "JSONRPC"
        assert card["securitySchemes"]["internalToken"]["scheme"] == "bearer"
        assert card["security"] == [{"internalToken": []}]
        assert client.get("/healthz").text == "ok"


def test_rpc_requires_credentials():
    with TestClient(_app()) as client:
        body = {"jsonrpc": "2.0", "id": "1", "method": "message/send", "params": {}}
        assert client.post("/", json=body).status_code == 401
        assert client.post("/", json=body, headers={"Authorization": "Bearer wrong"}).status_code == 401
        # with the right token the request reaches the A2A layer (a JSON-RPC error for empty params, not a 401)
        assert client.post("/", json=body, headers={"Authorization": "Bearer s3cret"}).status_code != 401
