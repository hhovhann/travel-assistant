"""The agent card, in A2A v0.3 shape, matching the Java flight-agent's card.

ADK 2.x serves A2A 1.0 cards (`supportedInterfaces`) and accepts v0.3 JSON-RPC calls. The existing orchestrator uses the
A2A Java SDK 0.3.x, which reads `url` / `preferredTransport` / `securitySchemes` from the card, so we publish the card it
understands. Written by hand (not generated from the agent) so it is reviewable and needs no MCP call at startup.
"""

from __future__ import annotations

from typing import Any

from a2a.compat.v0_3 import types as t

# Same scheme name as the Java services (InternalAuth.SCHEME): the Java client's AuthInterceptor keys on it.
SCHEME = "internalToken"


def build_card(public_url: str, auth_mode: str = "token") -> dict[str, Any]:
    description = (
        "Internal service token sent as `Authorization: Bearer <token>`."
        if auth_mode == "token"
        else "Google-signed ID token for an allow-listed service account, sent as `Authorization: Bearer <id_token>`."
    )
    card = t.AgentCard(
        name="Flight Agent",
        description="Searches, recommends and checks flights across multiple airline providers, "
                    "against travel policy and historical fares (Gemini on Google ADK)",
        url=public_url.rstrip("/"),
        preferred_transport="JSONRPC",
        protocol_version="0.3.0",
        version="0.3.0",
        capabilities=t.AgentCapabilities(streaming=False, push_notifications=False, state_transition_history=False),
        security_schemes={SCHEME: t.SecurityScheme(root=t.HTTPAuthSecurityScheme(scheme="bearer", description=description))},
        security=[{SCHEME: []}],
        default_input_modes=["text/plain"],
        default_output_modes=["text/plain"],
        skills=[
            t.AgentSkill(
                id="flight_search",
                name="Flight Search",
                description="Find and compare flights between two cities for given dates and passengers",
                tags=["flights", "search"],
                examples=["Find flights from New York to Yerevan on 2026-10-15 for 2 passengers"],
            ),
            t.AgentSkill(
                id="policy_check",
                name="Travel Policy & Fare Check",
                description="Check an option against the company travel policy and the route's historical fares (BigQuery)",
                tags=["flights", "policy", "bigquery"],
                examples=["Is a $1,400 economy fare JFK-EVN within policy for a standard traveller?"],
            ),
        ],
    )
    return card.model_dump(by_alias=True, exclude_none=True)
