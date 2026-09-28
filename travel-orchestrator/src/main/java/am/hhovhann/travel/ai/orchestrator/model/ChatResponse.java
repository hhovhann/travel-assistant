package am.hhovhann.travel.ai.orchestrator.model;

import am.hhovhann.travel.ai.orchestrator.booking.BookingConfirmation;
import am.hhovhann.travel.ai.orchestrator.booking.BookingProposal;

/**
 * @param pendingBooking set when this turn prepared a booking; the client shows it with Confirm and Cancel
 * @param booking        set when this turn confirmed the pending booking (the message was an explicit confirmation)
 */
public record ChatResponse(String conversationId, String response, BookingProposal pendingBooking,
                           BookingConfirmation booking) {
}
