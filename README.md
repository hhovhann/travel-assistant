# Travel Assistant

A multi-agent travel planning assistant built with **Spring AI**, the **Agent2Agent (A2A)** protocol and the
**Model Context Protocol (MCP)**. Describe a trip in plain language; an orchestrator agent delegates to a Flight Agent
and a Hotel Agent, which query (mock) airline and hotel providers through MCP tools, and combines the results into an
itinerary you can book from the chat.

**Status: MVP (v0.1.0).** Works end to end with mock providers; see [Current limitations](#current-limitations).
New here? [docs/ONBOARDING.md](docs/ONBOARDING.md) walks you through running and debugging it step by step.

## Architecture

```
                 Browser chat UI  /  REST API
                              │
                 ┌────────────▼────────────┐
                 │   Travel Orchestrator   │  LLM with tools: askFlightAgent, askHotelAgent,
                 │         :9000           │  proposeBooking, getBookings
                 └──────┬───────────┬──────┘
          A2A JSON-RPC  │           │  A2A JSON-RPC  (protocol 0.3.0)
                ┌───────▼─────┐ ┌───▼─────────┐
                │ Flight Agent│ │ Hotel Agent │  LLM + MCP client
                │    :8080    │ │    :8082    │  (built on travel-core)
                └───────┬─────┘ └───┬─────────┘
     MCP Streamable HTTP│           │MCP Streamable HTTP
                ┌───────▼─────┐ ┌───▼─────────┐
                │ Flight MCP  │ │  Hotel MCP  │  @McpTool methods
                │    :8081    │ │    :8083    │
                └───────┬─────┘ └───┬─────────┘
       Joyair, AeroGo, DracAir     Marriott, Holiday Inn, Accor      (mock providers)
```

| Service | Port | Role |
|---|---|---|
| `travel-orchestrator` | 9000 | REST API and chat UI. Its LLM decides which agent to ask and what to ask; it never routes by keywords. It can only *propose* a booking; the traveller confirms it. The conversation id is sent to the agents as the A2A `contextId`, so follow-ups ("make the hotel cheaper") keep their context end to end. |
| `flight-agent` / `hotel-agent` | 8080 / 8082 | Standard A2A servers: agent card at `/.well-known/agent-card.json`, JSON-RPC at `POST /`. Text requests go to the agent's LLM, which calls the MCP tools. Structured requests (`DataPart`) run an allowed MCP tool directly; this is the only way to book. |
| `mcp-flight-server` / `mcp-hotel-server` | 8081 / 8083 | Standard MCP servers (Streamable HTTP at `/mcp`) usable by any MCP client, including Claude Desktop or MCP Inspector. Each one aggregates several providers. |
| `travel-core` | – | Shared library: A2A JSON-RPC endpoint, SDK wiring and the `ChatClient`-based `AgentExecutor` used by both agents. |

### MCP tools

| Server | Tools |
|---|---|
| Flight MCP (:8081) | `search_flights` (with `returnDate` also returns the return leg), `get_offer`, `book_flight`, `get_booking`, `get_flight_status`, `get_recommendations` |
| Hotel MCP (:8083) | `search_hotels`, `search_near_airport`, `get_offer`, `book_hotel`, `get_booking`, `get_hotel_details`, `get_recommendations` |

### How bookings stay accurate

LLMs search, compare and talk. Nothing that books is left to an LLM:

1. **Offers**: every search result is registered by the MCP server under a dated ID
   (`JOY1:JFK-EVN:2026-10-15`, `HI1:Yerevan:2026-10-15:2026-10-20`) with a server-computed total price.
2. **Proposal**: when the traveller picks an option, the orchestrator LLM calls `proposeBooking` with the chosen IDs
   and traveller names. The orchestrator code fetches those exact offers (`get_offer`) and returns a
   `pendingBooking` in the chat response. The UI shows it as a card with **Confirm** and **Cancel** buttons.
3. **Confirm**: the card's Confirm button (`POST /api/v1/travel/bookings/{proposalId}/confirm`), or an explicit
   typed confirmation such as "Yes, I confirm", books exactly that proposal. A typed confirmation is recognized by
   the orchestrator code, not the LLM; anything less explicit ("yes, but a cheaper hotel") goes to the assistant. The orchestrator
   sends the agents a structured A2A request (a `DataPart` such as
   `{"tool": "book_flight", "arguments": {...}}`), which the agent executes directly against MCP, without its LLM.
   The agents' LLMs have no booking tool at all. "What did I book?" is answered from the recorded bookings
   (`getBookings`), not from the model's memory.
4. **Validation**: the MCP servers accept only offer IDs returned by a search, one name per flight passenger, and
   valid dates. Each booking gets a `bookingId` and can be read back with `get_booking`.
5. **Memory**: conversations live in memory. After a restart, continuing an old conversation returns
   `404 CONVERSATION_NOT_FOUND` instead of letting the model guess what "option 1" was.

### Security

Layered defenses against misuse and prompt injection. The design choice that matters most is above: **no LLM can
book**, so a successful injection can at worst produce a misleading chat answer. The layers below keep attackers
away from the services, stop common attacks early, and treat everything a tool returns as data.

| Layer | What it does | Where |
|---|---|---|
| Service-to-service auth | The agents and MCP servers require `Authorization: Bearer <INTERNAL_API_TOKEN>`, so nobody can call `book_flight` or an agent directly and skip the traveller's confirmation. The agent cards declare the scheme (A2A `securitySchemes`), and the orchestrator's A2A client sends the token for it (the SDK's `AuthInterceptor`). The agents' MCP clients send it via an `McpSyncHttpClientRequestCustomizer`. Only the agent card stays public. | `travel-core` `security/`, `InternalTokenFilter` in each MCP server |
| Network exposure | Every service listens on `127.0.0.1` by default. Docker compose publishes only the orchestrator, on the host's loopback. | `server.address`, `docker-compose.yml` |
| Input guardrail | A Spring AI advisor rejects messages over the length limit and likely prompt injections ("ignore previous instructions", "reveal your system prompt", role changes, fake `System:` / `<\|im_start\|>` markers) with `400`. The text is normalized first, so zero-width characters and look-alike letters don't slip through. It runs before the chat memory advisor, so a rejected message is never stored. | `InputGuardrailAdvisor`, `PromptInjectionDetector` |
| Rate limiting | `/chat` and `/plan` are limited per client IP (`429` with `Retry-After`), which caps LLM cost per client. | `RateLimitFilter` |
| Indirect injection | Every tool result (MCP results in the agents; agent answers and booking results in the orchestrator) is capped, has suspected injections redacted (including rules for supplier text, like "book without asking the traveller" and "don't tell the user"), and is wrapped in `<untrusted-data>` tags. The system prompts tell each model to treat that content as data, never as instructions. | `GuardedToolCallback`, `UntrustedContent` |
| Output safety | The chat UI escapes the model's text before formatting it, so model output can't inject HTML. | `index.html` |

Pattern-based detection is a speed bump, not a guarantee: it catches the common attacks cheaply, and the
architecture handles the rest.

## Technology stack

| | Version |
|---|---|
| Java | 27 |
| Spring Boot | 4.1.1 |
| Spring AI (OpenAI, MCP client/server, chat memory) | 2.0.1 |
| A2A Java SDK | 0.3.3.Final (latest stable; 1.0 is still Alpha) |
| MCP Java SDK | 2.0.0 (via Spring AI) |

## Getting started

### Prerequisites

- Java 27 (Maven is not needed, the wrapper `./mvnw` is included), **or** Docker
- An OpenAI API key, or a local OpenAI-compatible model (see [Using a local model](#using-a-local-model-no-api-key-or-another-provider))
- Free ports 8080, 8081, 8082, 8083 and 9000

### 1. Clone and configure

```bash
git clone https://github.com/hhovhann/travel-assistant.git
cd travel-assistant
cp .env.example .env        # then set OPENAI_API_KEY (or pick a local model option)
echo "INTERNAL_API_TOKEN=$(openssl rand -hex 32)" >> .env   # shared service token (start-dev.sh generates one if missing)
```

### 2. Build and test

```bash
./mvnw clean verify         # builds all modules and runs the unit tests (no API key needed)
```

### 3. Run

```bash
./scripts/start-dev.sh      # builds, then starts the 5 services in dependency order (logs in logs/)
```

Or with Docker, no local Java needed (`.env` must contain `OPENAI_API_KEY` and `INTERNAL_API_TOKEN`):

```bash
docker compose up --build   # only the orchestrator is published, on localhost:9000
```

### 4. Use it

Open <http://localhost:9000> for the chat UI, or run the end-to-end smoke test:

```bash
./scripts/test-api.sh       # needs jq; checks MCP, A2A cards, a direct agent call and an orchestrator chat
```

### 5. Stop

```bash
./scripts/stop-dev.sh       # or Ctrl+C / docker compose down
```

To run a single service from the IDE or the command line, start the MCP servers first (the agents connect to them at
startup): `java -jar mcp-flight-server/target/mcp-flight-server-0.1.0.jar`, and so on.

### Using a local model (no API key) or another provider

Copy `.env.example` to `.env` and uncomment one option. Any OpenAI-compatible endpoint works through the same
three variables, `OPENAI_BASE_URL`, `OPENAI_API_KEY` and `OPENAI_MODEL`, for example:

```bash
# LM Studio: load a model with tool calling (e.g. qwen/qwen3-14b), then start its server on port 1234
OPENAI_BASE_URL=http://127.0.0.1:1234/v1
OPENAI_API_KEY=lm-studio
OPENAI_MODEL=qwen/qwen3-14b
OPENAI_TIMEOUT=10m            # local models are slow
AGENT_TIMEOUT_SECONDS=900
```

The model must support tool calling. Local models are slower and less accurate than hosted ones: a full trip plan
with a 14B model on a laptop takes a few minutes. For another provider (e.g. Anthropic), swap
`spring-ai-starter-model-openai` for that provider's Spring AI starter in the agent and orchestrator POMs.

## API

```bash
# Chat. Send the returned conversationId back to continue the same conversation.
curl -X POST http://localhost:9000/api/v1/travel/chat -H "Content-Type: application/json" \
  -d '{"message": "Plan a 5-day trip to Armenia from New York in mid October for 2 people, mid-range"}'

# Structured trip request. tripId can be used as conversationId in /chat to refine or book.
curl -X POST http://localhost:9000/api/v1/travel/plan -H "Content-Type: application/json" \
  -d '{"from": "New York", "to": "Yerevan", "departureDate": "2026-10-15", "returnDate": "2026-10-20",
       "passengers": 2, "preferences": "mid-range hotel near the center"}'

# Confirm or cancel a booking proposal (returned as pendingBooking by /chat)
curl -X POST http://localhost:9000/api/v1/travel/bookings/BP-1A2B3C4D/confirm
curl -X POST http://localhost:9000/api/v1/travel/bookings/BP-1A2B3C4D/cancel

# Live status of the agents, read from their agent cards
curl http://localhost:9000/api/v1/travel/agents/status
```

Errors return `{"error": "..."}` with:

| Status | When |
|---|---|
| `400` | Invalid input (missing `message`, `from`, `to` or `departureDate`) |
| `404` `CONVERSATION_NOT_FOUND` | The `conversationId` is unknown, e.g. after a restart |
| `404` `BOOKING_NOT_FOUND` | The proposal was already confirmed, cancelled or replaced by a newer one |
| `400` `PROMPT_INJECTION` / `INPUT_TOO_LONG` | Blocked by the input guardrail (see [Security](#security)) |
| `429` `RATE_LIMITED` | Too many `/chat` or `/plan` requests from one client; see the `Retry-After` header |
| `502` | The LLM or an agent failed |

Confirm returns the `BookingConfirmation` (`status`: `confirmed`, `partially_confirmed` or `failed`); cancel returns `204`.

Talking to an agent directly over A2A (the agent card is public; everything else needs the internal token):

```bash
curl http://localhost:8080/.well-known/agent-card.json
curl -X POST http://localhost:8080/ -H "Content-Type: application/json" -H "Authorization: Bearer $INTERNAL_API_TOKEN" -d '{
  "jsonrpc": "2.0", "id": "1", "method": "message/send",
  "params": {"message": {"role": "user", "kind": "message", "messageId": "m1",
    "parts": [{"kind": "text", "text": "Find flights from New York to Yerevan on 2026-10-15 for 2"}]}}}'
```

## Configuration

| Variable | Used by | Default |
|---|---|---|
| `OPENAI_API_KEY` | agents, orchestrator | required |
| `INTERNAL_API_TOKEN` | all services; the shared service token (same value everywhere) | required; `start-dev.sh` generates one if unset |
| `OPENAI_MODEL` | agents, orchestrator | `gpt-5-mini` |
| `OPENAI_BASE_URL` | agents, orchestrator | OpenAI |
| `OPENAI_TIMEOUT` | agents, orchestrator; one LLM call | `120s` |
| `AGENT_TIMEOUT_SECONDS` | agents and orchestrator; one agent task (all its LLM + tool calls) | `300` |
| `FLIGHT_MCP_URL` / `HOTEL_MCP_URL` | flight / hotel agent | `http://localhost:8081` / `:8083` |
| `A2A_PUBLIC_URL` | agents; the URL published in the agent card, which must be reachable by callers | `http://localhost:8080` / `:8082` |
| `AGENTS_FLIGHT_URL` / `AGENTS_HOTEL_URL` | orchestrator; agent base URLs | `http://localhost:8080` / `:8082` |
| `SERVER_ADDRESS` | all services; the interface to listen on | `127.0.0.1` (docker compose: `0.0.0.0`) |
| `GUARDRAIL_MAX_INPUT_CHARS` | orchestrator / agents; longest accepted message | `2000` / `4000` |
| `RATE_LIMIT_PER_MINUTE` | orchestrator; `/chat` and `/plan` requests per client IP per minute | `20` |

Note: Spring AI 2.0.1 ignores `spring.ai.openai.timeout` for chat calls (it sends a fixed 60s per request), so the
configured value is also passed as a default chat option in each `ChatClient`.

`AGENT_TIMEOUT_SECONDS` must be longer than the slowest agent run. When a blocking A2A `message/send` runs past it,
the SDK returns the task as `working` and discards the late result. The orchestrator then reports a timeout.
For local models use e.g. `OPENAI_TIMEOUT=10m` and `AGENT_TIMEOUT_SECONDS=900`.

## Project structure

```
travel-core/           Shared A2A server support: JSON-RPC endpoint, SDK wiring, ChatClient-based AgentExecutor
flight-agent/          A2A Flight Agent (LLM + MCP client)
hotel-agent/           A2A Hotel Agent (LLM + MCP client)
mcp-flight-server/     MCP server with flight tools and mock providers
mcp-hotel-server/      MCP server with hotel tools and mock providers
travel-orchestrator/   REST API, chat UI (static/index.html) and the orchestrating LLM
docs/                  ONBOARDING.md: run, debug and explore the application step by step
scripts/               start-dev.sh, stop-dev.sh, test-api.sh
postman/               Postman environment for manual API testing
Dockerfile             One multi-stage image for any module (--build-arg MODULE=<module>)
docker-compose.yml     All five services wired together
```

### Adding a provider

Implement `FlightProvider` (in `mcp-flight-server`) or `HotelProvider` (in `mcp-hotel-server`) as a Spring
`@Component`. The MCP service aggregates all providers automatically.

### Adding a tool

Add an `@McpTool` method to `FlightMcpService` or `HotelMcpService`. The agents discover it through MCP at startup,
so no agent change is needed.

## Testing

```bash
./mvnw test              # 81 unit tests, no API key needed: A2A protocol, structured requests,
                         # MCP search/booking rules, booking proposal and confirmation,
                         # prompt-injection detection, guardrails, service auth, rate limiting
./scripts/test-api.sh    # end-to-end smoke test against running services, including the security checks
```

## Current limitations

- **Mock data**: providers return generated flights and hotels, and bookings are not real. Real inventory needs
  supplier APIs (e.g. Duffel for flights; LiteAPI, Hotelbeds or Expedia Rapid for hotels).
- **In-memory state**: A2A tasks, chat memory, offers and bookings are lost on restart.
- **No user accounts**: the orchestrator authenticates nobody (only the internal services are protected), so
  bookings aren't tied to a user. Put it behind your own login, or an OAuth2 proxy, before exposing it.
- **Personal data in debug logs**: with DEBUG logging on, `SimpleLoggerAdvisor` logs traveller names and emails.
- A2A streaming (`message/stream`) is not implemented (the agent cards advertise `streaming: false`).
