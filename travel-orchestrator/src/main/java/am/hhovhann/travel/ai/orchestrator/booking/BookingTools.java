package am.hhovhann.travel.ai.orchestrator.booking;

import am.hhovhann.travel.ai.orchestrator.agent.TravelAgentTools;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The only booking capability the orchestrator LLM has: preparing a proposal. Booking itself happens when the
 * traveller presses Confirm in the app.
 */
public class BookingTools {

    private final BookingService bookingService;

    public BookingTools(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Tool(description = """
            Prepare the booking of the option the traveller chose, for them to review and confirm in the app.
            Pass the exact flightIds of every flight leg and the exact hotelId of that option, as returned by the
            agents, and the full name of every traveller. This does not book anything.""")
    public String proposeBooking(
            @ToolParam(description = "flightIds of every leg of the chosen option, e.g. [\"JOY1:JFK-EVN:2026-10-15\", \"JOY1:EVN-JFK:2026-10-20\"]", required = false) List<String> flightIds,
            @ToolParam(description = "hotelId of the chosen option, e.g. HI1:Yerevan:2026-10-15:2026-10-20", required = false) String hotelId,
            @ToolParam(description = "Full name of every traveller") List<String> travellerNames,
            @ToolParam(description = "Contact email", required = false) String contactEmail,
            ToolContext toolContext) {
        String conversationId = (String) toolContext.getContext().get(TravelAgentTools.CONVERSATION_ID);
        try {
            BookingProposal proposal = bookingService.propose(conversationId, flightIds, hotelId, travellerNames, contactEmail);
            String flights = proposal.flights().stream()
                    .map(flight -> "%s %s on %s, total %s".formatted(flight.get("flightId"), flight.get("flightNumber"),
                            flight.get("date"), flight.get("totalPrice")))
                    .collect(Collectors.joining("; "));
            String hotel = proposal.hotel() == null ? "none" : "%s %s, %s to %s, total %s".formatted(
                    proposal.hotel().get("hotelId"), proposal.hotel().get("name"), proposal.hotel().get("checkIn"),
                    proposal.hotel().get("checkOut"), proposal.hotel().get("totalPrice"));
            return """
                    Booking proposal %s is ready and shown to the traveller with Confirm and Cancel buttons.
                    Flights: %s. Hotel: %s. Travellers: %s. Grand total: %s %s.
                    Nothing is booked yet: tell the traveller to review the summary and press Confirm."""
                    .formatted(proposal.proposalId(), flights.isEmpty() ? "none" : flights, hotel,
                            String.join(", ", proposal.travellerNames()), proposal.totalPrice(), proposal.currency());
        } catch (RuntimeException e) {
            return "Cannot prepare the booking: " + e.getMessage();
        }
    }

    @Tool(description = """
            Get what the traveller has booked in this conversation: the confirmed bookings with their booking IDs,
            and the booking proposal still waiting for confirmation, if any. Use it for any question about bookings.""")
    public Map<String, Object> getBookings(ToolContext toolContext) {
        String conversationId = (String) toolContext.getContext().get(TravelAgentTools.CONVERSATION_ID);
        Map<String, Object> bookings = new LinkedHashMap<>();
        bookings.put("confirmed", bookingService.confirmedFor(conversationId));
        bookings.put("awaitingConfirmation", bookingService.pendingFor(conversationId).orElse(null));
        return bookings;
    }
}
