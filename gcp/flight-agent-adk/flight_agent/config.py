from __future__ import annotations

import os
from dataclasses import dataclass, field


def _csv(value: str) -> frozenset[str]:
    return frozenset(part.strip() for part in value.split(",") if part.strip())


@dataclass(frozen=True)
class Settings:
    # Model: Gemini on Vertex AI (GOOGLE_GENAI_USE_VERTEXAI=true, GOOGLE_CLOUD_PROJECT, GOOGLE_CLOUD_LOCATION)
    model: str = field(default_factory=lambda: os.getenv("MODEL", "gemini-2.5-flash"))
    mcp_url: str = field(default_factory=lambda: os.getenv("FLIGHT_MCP_URL", "http://localhost:8081").rstrip("/") + "/mcp")
    mcp_timeout_s: float = field(default_factory=lambda: float(os.getenv("MCP_TIMEOUT_SECONDS", "30")))
    max_input_chars: int = field(default_factory=lambda: int(os.getenv("GUARDRAIL_MAX_INPUT_CHARS", "4000")))
    max_tool_result_chars: int = field(default_factory=lambda: int(os.getenv("GUARDRAIL_MAX_TOOL_CHARS", "20000")))
    # Inbound auth for this agent's A2A endpoint, and outbound auth towards the MCP server
    auth_mode: str = field(default_factory=lambda: os.getenv("AUTH_MODE", "token"))
    internal_token: str = field(default_factory=lambda: os.getenv("INTERNAL_API_TOKEN", ""))
    oidc_audience: str = field(default_factory=lambda: os.getenv("OIDC_AUDIENCE", ""))
    allowed_callers: frozenset[str] = field(default_factory=lambda: _csv(os.getenv("ALLOWED_CALLERS", "")))
    mcp_audience: str = field(default_factory=lambda: os.getenv("MCP_AUDIENCE", ""))
    # Policy / price-history data: in-memory CSV seeds locally, BigQuery when deployed
    policy_backend: str = field(default_factory=lambda: os.getenv("POLICY_BACKEND", "memory"))
    bq_dataset: str = field(default_factory=lambda: os.getenv("BQ_DATASET", "travel"))
    public_url: str = field(default_factory=lambda: os.getenv("A2A_PUBLIC_URL", "http://localhost:8080"))
