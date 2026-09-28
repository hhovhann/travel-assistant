#!/bin/bash
# Smoke test of all layers. Needs the services running (start-dev.sh or docker compose) and jq.

ORCHESTRATOR=http://localhost:9000
MCP_ACCEPT='Accept: application/json, text/event-stream'

echo "1. MCP servers (Streamable HTTP handshake)"
for url in http://localhost:8081/mcp http://localhost:8083/mcp; do
    curl -s -X POST "$url" -H 'Content-Type: application/json' -H "$MCP_ACCEPT" \
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke-test","version":"1"}}}' \
        | jq -r '"   \(.result.serverInfo.name): protocol \(.result.protocolVersion)"'
done

echo "2. A2A agent cards"
for url in http://localhost:8080 http://localhost:8082; do
    curl -s "$url/.well-known/agent-card.json" | jq -r '"   \(.name) -> \(.url) skills: \([.skills[].id] | join(", "))"'
done

echo "3. A2A message/send to the Flight Agent (calls the LLM)"
curl -s -X POST http://localhost:8080/ -H 'Content-Type: application/json' -d '{
  "jsonrpc": "2.0", "id": "smoke-1", "method": "message/send",
  "params": {"message": {"role": "user", "kind": "message", "messageId": "smoke-msg-1",
    "parts": [{"kind": "text", "text": "Find flights from New York to Yerevan on 2026-10-15 for 2 passengers"}]}}
}' | jq -r '"   task state: \(.result.status.state // .error.message)"'

echo "4. Orchestrator agent status"
curl -s "$ORCHESTRATOR/api/v1/travel/agents/status" | jq -c 'map_values(.available)'

echo "5. Orchestrator chat (orchestrator -> agents -> MCP, calls the LLM several times)"
curl -s -X POST "$ORCHESTRATOR/api/v1/travel/chat" -H 'Content-Type: application/json' \
    -d '{"message": "Plan a 5-day trip to Yerevan from New York starting 2026-10-15 for 2 people, mid-range"}' \
    | jq -r '.response // .error'
