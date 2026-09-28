package am.hhovhann.travel.ai.orchestrator.booking;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * A booking prepared for the traveller to confirm. Flights and hotel are the exact offers held by the MCP servers,
 * and the total is the sum of their server-computed totals.
 */
public record BookingProposal(
        String proposalId,
        String conversationId,
        List<Map<String, Object>> flights,
        Map<String, Object> hotel,
        List<String> travellerNames,
        String contactEmail,
        BigDecimal totalPrice,
        String currency
) {
}
