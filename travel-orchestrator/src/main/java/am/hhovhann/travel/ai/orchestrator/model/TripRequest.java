package am.hhovhann.travel.ai.orchestrator.model;

import java.time.LocalDate;

public record TripRequest(
        String from,
        String to,
        LocalDate departureDate,
        LocalDate returnDate,
        Integer passengers,
        String preferences
) {
}
