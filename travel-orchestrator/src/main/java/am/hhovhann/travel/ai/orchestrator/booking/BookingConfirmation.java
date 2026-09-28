package am.hhovhann.travel.ai.orchestrator.booking;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * @param status {@code confirmed}, or {@code partially_confirmed} when one of the bookings failed (see errors)
 */
public record BookingConfirmation(
        String proposalId,
        String status,
        Map<String, Object> flightBooking,
        Map<String, Object> hotelBooking,
        BigDecimal totalPrice,
        String currency,
        List<String> errors
) {
}
