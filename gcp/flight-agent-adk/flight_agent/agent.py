"""Flight Agent on Google ADK: Gemini + the flight MCP server's search tools + BigQuery-backed policy checks.

Same contract as the Spring AI flight-agent: the LLM searches and explains, it can never book (`book_flight` is
filtered out of its toolset; the orchestrator books with a structured, human-confirmed call).
"""

from __future__ import annotations

import json
import logging
from datetime import date
from typing import Any

from google.adk.agents import LlmAgent
from google.adk.agents.callback_context import CallbackContext
from google.adk.agents.readonly_context import ReadonlyContext
from google.adk.models import LlmRequest, LlmResponse
from google.adk.tools import BaseTool, ToolContext
from google.adk.tools.mcp_tool import McpToolset, StreamableHTTPConnectionParams
from google.adk.tools.mcp_tool.mcp_tool import McpTool
from google.genai import types

from . import guardrails
from .auth import OutboundAuth
from .config import Settings
from .policy import check_travel_policy, get_route_price_history

logger = logging.getLogger(__name__)

# The LLM must not be able to book, even if a supplier's text or a user message talks it into trying
LLM_FORBIDDEN_TOOLS = frozenset({"book_flight"})

INSTRUCTION = """\
You are the Flight Agent of a travel assistant. Today is {today}.
Use the flight tools to search flights, look up offers and bookings, and check flight status.
- Convert city names to IATA airport codes (e.g. New York -> JFK, Yerevan -> EVN) before searching.
- Resolve relative dates to YYYY-MM-DD using today's date. For round trips pass the returnDate.
- If origin, destination, departure date or passenger count is missing, ask for it instead of guessing.
- Base your answer only on tool results. Show the cheapest and the best-value options per leg with the
  full flightId, flight number, times, stops, price per passenger and totalPrice as returned by the tool.
- When the traveller's grade is known, call check_travel_policy for the options you recommend and say if one is over
  policy or needs approval. Use get_route_price_history to say whether a fare is high or low for the route.
- You cannot book. Bookings are made by the travel assistant after the traveller confirms.
- If a tool returns an error, report it as it is.
""" + guardrails.SYSTEM_PROMPT_RULE


def _instruction(_: ReadonlyContext) -> str:
    # A callable avoids ADK's {state} templating of the braces in the prompt
    return INSTRUCTION.replace("{today}", date.today().isoformat())


def _last_user_text(llm_request: LlmRequest) -> str:
    for content in reversed(llm_request.contents or []):
        if content.role == "user":
            text = "\n".join(part.text for part in content.parts or [] if part.text)
            if text:
                return text
    return ""


def make_input_guardrail(max_chars: int):
    """before_model_callback: reject over-long or injection-looking traveller messages before they reach the model."""

    def input_guardrail(_: CallbackContext, llm_request: LlmRequest) -> LlmResponse | None:
        text = _last_user_text(llm_request)
        if len(text) > max_chars:
            reason = f"INPUT_TOO_LONG: messages are limited to {max_chars} characters."
        elif (rule := guardrails.detect_in_user_input(text)) is not None:
            logger.warning("Blocked suspected prompt injection (rule=%s)", rule)
            reason = "PROMPT_INJECTION: this request was blocked. Please rephrase your travel question."
        else:
            return None
        return LlmResponse(content=types.Content(role="model", parts=[types.Part(text=reason)]))

    return input_guardrail


REQUIRED_TOOLS = frozenset({"search_flights"})


def require_backend_tools(_: CallbackContext, llm_request: LlmRequest) -> LlmResponse | None:
    """before_model_callback: fail closed when the MCP toolset did not load.

    ADK logs "will run without the tools from toolset McpToolset" and carries on when the MCP connection fails (bad or
    expired credentials, server down). A flight agent without flight search would answer from the model's imagination,
    so refuse instead of letting the model speak.
    """
    missing = REQUIRED_TOOLS - set(llm_request.tools_dict)
    if not missing:
        return None
    logger.error("Flight tools unavailable (%s); refusing to answer without them", ", ".join(sorted(missing)))
    return LlmResponse(content=types.Content(role="model", parts=[types.Part(
        text="SERVICE_UNAVAILABLE: the flight search backend is not reachable right now. Please try again shortly.")]))


def make_untrusted_wrapper(max_chars: int):
    """after_tool_callback: MCP results come from suppliers, so cap them, redact injections and mark them as data."""

    def wrap_result(tool: BaseTool, _args: dict[str, Any], _ctx: ToolContext, response: dict[str, Any]) -> dict[str, Any] | None:
        if not isinstance(tool, McpTool):
            return None  # our own policy tools return trusted data
        payload = response.get("structuredContent") or response.get("content") or response
        return {"result": guardrails.wrap_untrusted(tool.name, json.dumps(payload, default=str), max_chars)}

    return wrap_result


def build_agent(settings: Settings | None = None, model: Any = None) -> LlmAgent:
    """`model` overrides the configured Gemini model (tests pass a scripted fake)."""
    settings = settings or Settings()
    outbound = OutboundAuth(settings.auth_mode, token=settings.internal_token, audience=settings.mcp_audience)
    mcp = McpToolset(
        connection_params=StreamableHTTPConnectionParams(url=settings.mcp_url, timeout=settings.mcp_timeout_s),
        tool_filter=lambda tool, _ctx=None: tool.name not in LLM_FORBIDDEN_TOOLS,
        header_provider=lambda _ctx: outbound.header(),
    )
    return LlmAgent(
        name="flight_agent",
        model=model or settings.model,
        description="Searches, recommends and checks flights against travel policy and historical fares",
        instruction=_instruction,
        tools=[mcp, check_travel_policy, get_route_price_history],
        before_model_callback=[make_input_guardrail(settings.max_input_chars), require_backend_tools],
        after_tool_callback=make_untrusted_wrapper(settings.max_tool_result_chars),
    )


root_agent = build_agent()
