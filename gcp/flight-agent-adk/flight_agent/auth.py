"""Service-to-service auth for the agent's A2A endpoint and for its calls to the MCP server.

Two modes, chosen with AUTH_MODE:
  token  Shared bearer token (INTERNAL_API_TOKEN). Same as the Java services; for local development.
  oidc   Google-signed ID tokens. On Cloud Run each caller presents an ID token minted for its own service account
         (audience = this service's URL) and we accept only an allow-list of caller service accounts. No shared
         secret exists to leak or rotate.
The agent card stays public in both modes: callers need it to discover the agent and its auth scheme.
"""

from __future__ import annotations

import hmac
import json
import logging
import time
from collections.abc import Callable
from dataclasses import dataclass, field

from starlette.requests import Request
from starlette.responses import Response
from starlette.types import ASGIApp, Receive, Scope, Send

logger = logging.getLogger(__name__)

PUBLIC_PATHS = frozenset({"/.well-known/agent-card.json", "/.well-known/agent.json", "/healthz"})

# Returns the caller's identity (e.g. its service-account email) when the bearer credential is valid, else None.
Verifier = Callable[[str], str | None]


def token_verifier(expected: str) -> Verifier:
    if not expected:
        raise ValueError("INTERNAL_API_TOKEN must be set when AUTH_MODE=token")
    expected_bytes = expected.encode()

    def verify(credential: str) -> str | None:
        return "internal-token" if hmac.compare_digest(credential.encode(), expected_bytes) else None

    return verify


def oidc_verifier(audience: str, allowed_callers: frozenset[str]) -> Verifier:
    if not audience or not allowed_callers:
        raise ValueError("OIDC_AUDIENCE and ALLOWED_CALLERS must be set when AUTH_MODE=oidc")
    from google.auth.transport import requests as google_requests
    from google.oauth2 import id_token

    transport = google_requests.Request()

    def verify(credential: str) -> str | None:
        try:
            claims = id_token.verify_oauth2_token(credential, transport, audience=audience)
        except ValueError as error:  # bad signature, expired, wrong audience, ...
            logger.warning("Rejected ID token: %s", error)
            return None
        email = claims.get("email")
        if not claims.get("email_verified") or email not in allowed_callers:
            logger.warning("Rejected ID token from non-allow-listed caller %s", email)
            return None
        return email

    return verify


class BearerAuthMiddleware:
    """Pure-ASGI middleware: 401 unless `Authorization: Bearer <credential>` verifies."""

    def __init__(self, app: ASGIApp, verifier: Verifier, public_paths: frozenset[str] = PUBLIC_PATHS) -> None:
        self.app = app
        self.verifier = verifier
        self.public_paths = public_paths

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or scope["path"] in self.public_paths:
            await self.app(scope, receive, send)
            return
        header = Request(scope).headers.get("authorization", "")
        scheme, _, credential = header.partition(" ")
        caller = self.verifier(credential.strip()) if scheme.lower() == "bearer" and credential else None
        if caller is None:
            logger.warning("Rejected unauthenticated %s %s", scope["method"], scope["path"])
            response = Response(
                json.dumps({"error": "Missing or invalid credentials"}),
                status_code=401,
                media_type="application/json",
                headers={"WWW-Authenticate": "Bearer"},
            )
            await response(scope, receive, send)
            return
        scope.setdefault("state", {})["caller"] = caller
        await self.app(scope, receive, send)


@dataclass
class OutboundAuth:
    """Builds the Authorization header this agent sends to its MCP server."""

    mode: str
    token: str = ""
    audience: str = ""
    _fetch: Callable[[str], str] | None = field(default=None, repr=False)
    _cached: tuple[str, float] | None = field(default=None, repr=False)

    # ID tokens live for an hour; refresh well before expiry instead of calling the metadata server per request
    _TTL_SECONDS = 45 * 60

    def header(self) -> dict[str, str]:
        if self.mode == "token":
            return {"Authorization": f"Bearer {self.token}"} if self.token else {}
        now = time.monotonic()
        if self._cached is None or now >= self._cached[1]:
            token = (self._fetch or _metadata_id_token)(self.audience)
            self._cached = (token, now + self._TTL_SECONDS)
        return {"Authorization": f"Bearer {self._cached[0]}"}


def _metadata_id_token(audience: str) -> str:
    """ID token for this workload's service account (Cloud Run metadata server / ADC); cached by google-auth."""
    from google.auth.transport import requests as google_requests
    from google.oauth2 import id_token

    return id_token.fetch_id_token(google_requests.Request(), audience)


