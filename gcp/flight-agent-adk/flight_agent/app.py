"""ASGI entrypoint: the ADK agent served over A2A, behind service-to-service auth.

    uvicorn flight_agent.app:create_app --factory --host 0.0.0.0 --port 8080
"""

from __future__ import annotations

from urllib.parse import urlparse

from google.adk.a2a.utils.agent_to_a2a import to_a2a
from starlette.responses import JSONResponse, PlainTextResponse
from starlette.routing import Route

from .agent import build_agent
from .card import build_card
from .auth import BearerAuthMiddleware, oidc_verifier, token_verifier
from .config import Settings


def create_app(settings: Settings | None = None):
    settings = settings or Settings()
    public = urlparse(settings.public_url)
    app = to_a2a(
        build_agent(settings),
        host=public.hostname or "localhost",
        port=public.port or (443 if public.scheme == "https" else 80),
        protocol=public.scheme or "http",
    )
    # Registered first, so it wins over the library's A2A 1.0 card at the same path (see card.py)
    card = build_card(settings.public_url, settings.auth_mode)
    app.router.routes.insert(0, Route("/.well-known/agent-card.json", lambda _request: JSONResponse(card)))
    app.router.routes.append(Route("/healthz", lambda _request: PlainTextResponse("ok")))
    if settings.auth_mode == "oidc":
        verifier = oidc_verifier(settings.oidc_audience, settings.allowed_callers)
    elif settings.auth_mode == "token":
        verifier = token_verifier(settings.internal_token)
    else:
        raise ValueError(f"Unknown AUTH_MODE {settings.auth_mode!r} (expected 'token' or 'oidc')")
    app.add_middleware(BearerAuthMiddleware, verifier=verifier)
    return app

