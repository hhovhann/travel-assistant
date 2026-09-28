package am.hhovhann.travel.ai.orchestrator.agent;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Exposes the remote A2A agents to the orchestrator LLM as tools, so the model decides which agent to involve and
 * what to ask, instead of keyword routing.
 */
public class TravelAgentTools {

    public static final String CONVERSATION_ID = "conversationId";

    private final RemoteAgent flightAgent;
    private final RemoteAgent hotelAgent;

    public TravelAgentTools(RemoteAgent flightAgent, RemoteAgent hotelAgent) {
        this.flightAgent = flightAgent;
        this.hotelAgent = hotelAgent;
    }

    @Tool(description = """
            Ask the Flight Agent to search, recommend, check the status of, or book flights. Write a complete,
            self-contained request with origin and destination cities, exact dates (YYYY-MM-DD), passenger count,
            cabin class and preferences. Booking requires the flightId and passenger details.""")
    public String askFlightAgent(@ToolParam(description = "The request for the Flight Agent") String request,
                                 ToolContext toolContext) {
        return flightAgent.send(request, conversationId(toolContext));
    }

    @Tool(description = """
            Ask the Hotel Agent to search, recommend, describe or book hotels. Write a complete, self-contained
            request with the city, exact check-in and check-out dates (YYYY-MM-DD), guest and room count and
            preferences. Booking requires the hotelId and guest details.""")
    public String askHotelAgent(@ToolParam(description = "The request for the Hotel Agent") String request,
                                ToolContext toolContext) {
        return hotelAgent.send(request, conversationId(toolContext));
    }

    private static String conversationId(ToolContext toolContext) {
        return (String) toolContext.getContext().get(CONVERSATION_ID);
    }
}
