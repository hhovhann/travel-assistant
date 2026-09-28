package am.hhovhann.travel.ai.orchestrator.controller;

import am.hhovhann.travel.ai.orchestrator.booking.BookingConfirmation;
import am.hhovhann.travel.ai.orchestrator.booking.BookingNotFoundException;
import am.hhovhann.travel.ai.orchestrator.booking.BookingProposal;
import am.hhovhann.travel.ai.orchestrator.booking.BookingService;
import am.hhovhann.travel.ai.orchestrator.booking.ConfirmationPhrases;
import am.hhovhann.travel.ai.orchestrator.model.ChatRequest;
import am.hhovhann.travel.ai.orchestrator.model.ChatResponse;
import am.hhovhann.travel.ai.orchestrator.model.TravelPlan;
import am.hhovhann.travel.ai.orchestrator.model.TripRequest;
import am.hhovhann.travel.ai.orchestrator.service.TravelOrchestratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/travel")
public class TravelController {

    private static final Logger LOGGER = LoggerFactory.getLogger(TravelController.class);

    private final TravelOrchestratorService orchestratorService;
    private final BookingService bookingService;

    public TravelController(TravelOrchestratorService orchestratorService, BookingService bookingService) {
        this.orchestratorService = orchestratorService;
        this.bookingService = bookingService;
    }

    @PostMapping("/plan")
    public TravelPlan planTrip(@RequestBody TripRequest trip) {
        if (isBlank(trip.from()) || isBlank(trip.to()) || trip.departureDate() == null) {
            throw new IllegalArgumentException("'from', 'to' and 'departureDate' are required");
        }
        return orchestratorService.planTrip(trip);
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        if (isBlank(request.message())) {
            throw new IllegalArgumentException("'message' is required");
        }
        String conversationId = request.conversationId();
        if (isBlank(conversationId)) {
            conversationId = UUID.randomUUID().toString();
        } else if (!orchestratorService.hasConversation(conversationId)) {
            throw new ConversationNotFoundException(conversationId);
        }
        Optional<BookingProposal> before = bookingService.pendingFor(conversationId);
        if (before.isPresent() && ConfirmationPhrases.isConfirmation(request.message())) {
            // An explicit "yes" to the pending proposal books exactly that proposal, like its Confirm button;
            // the LLM is not asked, so it cannot claim a booking that did not happen or book something else
            BookingConfirmation confirmation = bookingService.confirm(before.get().proposalId());
            return new ChatResponse(conversationId, describe(confirmation), null, confirmation);
        }
        String answer = orchestratorService.chat(conversationId, request.message());
        Optional<BookingProposal> after = bookingService.pendingFor(conversationId);
        BookingProposal newProposal = after.isPresent() && !after.equals(before) ? after.get() : null;
        return new ChatResponse(conversationId, answer, newProposal, null);
    }

    /**
     * Books exactly the proposal the traveller reviewed. No LLM is involved.
     */
    @PostMapping("/bookings/{proposalId}/confirm")
    public BookingConfirmation confirmBooking(@PathVariable String proposalId) {
        return bookingService.confirm(proposalId);
    }

    @PostMapping("/bookings/{proposalId}/cancel")
    public ResponseEntity<Void> cancelBooking(@PathVariable String proposalId) {
        bookingService.cancel(proposalId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/agents/status")
    public Map<String, Object> agentsStatus() {
        return orchestratorService.agentsStatus();
    }

    @ExceptionHandler(ConversationNotFoundException.class)
    public ResponseEntity<Map<String, String>> conversationNotFound(ConversationNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "code", "CONVERSATION_NOT_FOUND",
                "error", "This conversation is no longer available (the assistant was restarted). "
                        + "Please start a new conversation."));
    }

    @ExceptionHandler(BookingNotFoundException.class)
    public ResponseEntity<Map<String, String>> bookingNotFound(BookingNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "BOOKING_NOT_FOUND", "error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> upstreamFailure(RuntimeException e) {
        LOGGER.error("Travel request failed", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    private static String describe(BookingConfirmation confirmation) {
        StringBuilder text = new StringBuilder(switch (confirmation.status()) {
            case "confirmed" -> "Booked.";
            case "partially_confirmed" -> "Only part of the booking went through.";
            default -> "The booking failed; nothing was booked.";
        });
        if (confirmation.flightBooking() != null) {
            text.append(" Flight booking ").append(confirmation.flightBooking().get("bookingId")).append('.');
        }
        if (confirmation.hotelBooking() != null) {
            text.append(" Hotel booking ").append(confirmation.hotelBooking().get("bookingId")).append('.');
        }
        if (!"failed".equals(confirmation.status())) {
            text.append(" Total ").append(confirmation.totalPrice()).append(' ').append(confirmation.currency()).append('.');
        }
        confirmation.errors().forEach(error -> text.append(' ').append(error));
        return text.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static class ConversationNotFoundException extends RuntimeException {
        ConversationNotFoundException(String conversationId) {
            super("Unknown conversation " + conversationId);
        }
    }
}
