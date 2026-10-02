# Build context is the gcp/ directory:  docker build -f flight-agent-adk/Dockerfile -t flight-agent-adk .
FROM python:3.12-slim
COPY --from=ghcr.io/astral-sh/uv:0.5 /uv /usr/local/bin/uv
WORKDIR /app
COPY flight-agent-adk/pyproject.toml flight-agent-adk/uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY flight-agent-adk/flight_agent ./flight_agent
COPY data /app/data
ENV DATA_DIR=/app/data PATH="/app/.venv/bin:$PATH" PYTHONUNBUFFERED=1
RUN useradd --system --uid 10001 app
USER app
# Cloud Run sets PORT; the agent card's public URL comes from A2A_PUBLIC_URL
CMD ["sh", "-c", "uvicorn flight_agent.app:create_app --factory --host 0.0.0.0 --port ${PORT:-8080}"]
