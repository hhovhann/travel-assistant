#!/bin/bash
# Builds the project and starts all services in the background. Run from the project root.
set -e

if [ -z "$OPENAI_API_KEY" ] && ! grep -qs '^OPENAI_API_KEY=' .env; then
    echo "ERROR: set OPENAI_API_KEY (export it, or put OPENAI_API_KEY=... in a .env file in the project root)"
    exit 1
fi

# Shared service token: from the environment, else from .env, else generated for this run
if [ -z "$INTERNAL_API_TOKEN" ]; then
    INTERNAL_API_TOKEN=$(grep -s '^INTERNAL_API_TOKEN=' .env | cut -d= -f2-)
fi
if [ -z "$INTERNAL_API_TOKEN" ]; then
    INTERNAL_API_TOKEN=$(openssl rand -hex 32)
    echo "INTERNAL_API_TOKEN is not set; generated one for this run (add it to .env to keep it)."
fi
export INTERNAL_API_TOKEN

./mvnw -q -B clean package -DskipTests
mkdir -p logs
# For scripts/test-api.sh; readable only by you
(umask 077 && printf '%s' "$INTERNAL_API_TOKEN" > logs/internal-token)
: > logs/pids.txt

start() {
    echo "Starting $1 on port $2..."
    nohup java -jar "$1/target/$1-0.1.0.jar" > "logs/$1.log" 2>&1 &
    echo $! >> logs/pids.txt
}

wait_for() {
    for _ in $(seq 1 60); do
        curl -s -o /dev/null "$1" && return 0
        sleep 1
    done
    echo "ERROR: $1 did not come up, see logs/"
    exit 1
}

# MCP servers first: the agents connect to them at startup
start mcp-flight-server 8081
start mcp-hotel-server 8083
wait_for http://localhost:8081/mcp
wait_for http://localhost:8083/mcp

start flight-agent 8080
start hotel-agent 8082
start travel-orchestrator 9000
wait_for http://localhost:8080/.well-known/agent-card.json
wait_for http://localhost:8082/.well-known/agent-card.json
wait_for http://localhost:9000/

echo ""
echo "All services started. Open http://localhost:9000 for the chat UI."
echo "Logs: logs/   Stop: ./scripts/stop-dev.sh   Smoke test: ./scripts/test-api.sh"
