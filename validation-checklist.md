# Travel Assistant Validation Checklist

## Prerequisites
- [ ] Java 27 installed (`java -version`), or Docker for the compose setup
- [ ] `OPENAI_API_KEY` exported or set in `.env`
- [ ] `INTERNAL_API_TOKEN` set in `.env` (or let `start-dev.sh` generate one)
- [ ] Ports 8080, 8081, 8082, 8083 and 9000 are free

## Build
```bash
./mvnw clean install
```
- [ ] All modules compile and the 81 unit tests pass
- [ ] Each service module has an executable jar in `target/`

## Startup
```bash
./scripts/start-dev.sh
```
- [ ] All five services start (see `logs/`)
- [ ] The agents start (they connect to their MCP server at startup)
- [ ] `http://localhost:9000` shows the chat UI with both agents marked green

## End to end
```bash
./scripts/test-api.sh
```
- [ ] Both MCP servers answer the `initialize` handshake
- [ ] MCP `tools/list` shows 6 flight tools and 7 hotel tools
- [ ] Both agent cards are served at `/.well-known/agent-card.json` with the correct `url`
- [ ] A direct A2A `message/send` to the Flight Agent ends in state `completed`
- [ ] The orchestrator chat returns an itinerary with flight and hotel IDs taken from the agents

## Booking accuracy
- [ ] `./mvnw test` passes (booking tests: invented IDs, wrong passenger counts, missing names and bad dates are
      rejected; confirm books exactly the proposal; a proposal cannot be confirmed twice)
- [ ] Choosing an option and giving the traveller names shows a booking card with exactly the presented IDs,
      dates and prices, and a total equal to the sum of the offers
- [ ] Typing "Yes, I confirm" books exactly the pending card (same as its Confirm button); "yes, but a cheaper
      hotel" books nothing
- [ ] After Confirm the card shows a flight and a hotel bookingId; "What did I book?" answers with those IDs
- [ ] After a restart, continuing an old conversation shows "This conversation is no longer available"

## Conversation
- [ ] In the UI, ask for a trip, then follow up with "make the hotel cheaper" and check that the answer keeps the
      same dates and destination
- [ ] "New conversation" starts without the previous context

## Security
- [ ] `./scripts/test-api.sh` step 6: MCP and A2A calls without the token return `401`, the injection is `PROMPT_INJECTION`
- [ ] Agent cards list the `internalToken` security scheme; `/.well-known/agent-card.json` needs no token
- [ ] All services listen on `127.0.0.1` only (`lsof -iTCP -sTCP:LISTEN`)
- [ ] A service started without `INTERNAL_API_TOKEN` fails at startup with a clear message
- [ ] "Ignore all previous instructions" in the chat UI is blocked and does not appear in later answers
- [ ] A message over 2000 characters returns `INPUT_TOO_LONG`; more than 20 chat requests a minute return `429`

## Docker
```bash
docker compose up --build   # with OPENAI_API_KEY and INTERNAL_API_TOKEN in .env
```
- [ ] All five containers start (the agents may restart once while the MCP servers boot)
- [ ] Only port 9000 is published (`docker compose ps`)
- [ ] `http://localhost:9000/api/v1/travel/agents/status` shows both agents available with `http://flight-agent:8080`
      and `http://hotel-agent:8082` endpoints
