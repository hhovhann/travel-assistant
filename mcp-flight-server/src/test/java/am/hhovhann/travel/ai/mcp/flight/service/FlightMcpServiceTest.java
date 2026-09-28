package am.hhovhann.travel.ai.mcp.flight.service;

import am.hhovhann.travel.ai.mcp.flight.provider.AeroGoProvider;
import am.hhovhann.travel.ai.mcp.flight.provider.DracAirProvider;
import am.hhovhann.travel.ai.mcp.flight.provider.JoyairProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlightMcpServiceTest {

    private final FlightMcpService service = new FlightMcpService(
            List.of(new JoyairProvider(), new AeroGoProvider(), new DracAirProvider()));

    @Test
    void roundTripSearchReturnsBothLegsWithDatedIdsAndTotals() {
        Map<String, Object> result = service.searchFlights("JFK", "EVN", "2026-10-15", "2026-10-20", 2, null, null);

        List<Map<String, Object>> outbound = flights(result, "outboundFlights");
        List<Map<String, Object>> inbound = flights(result, "returnFlights");
        assertThat(outbound).isNotEmpty().allSatisfy(flight -> {
            assertThat((String) flight.get("flightId")).endsWith(":JFK-EVN:2026-10-15");
            assertThat(flight.get("totalPrice")).isEqualTo(((BigDecimal) flight.get("price")).multiply(BigDecimal.TWO));
        });
        assertThat(inbound).isNotEmpty().allSatisfy(flight ->
                assertThat((String) flight.get("flightId")).endsWith(":EVN-JFK:2026-10-20"));
        assertThat(outbound).extracting(flight -> (BigDecimal) flight.get("price")).isSorted();
    }

    @Test
    void booksSearchedFlightsAndCanRetrieveTheBooking() {
        Map<String, Object> result = service.searchFlights("JFK", "EVN", "2026-10-15", "2026-10-20", 2, null, null);
        Map<String, Object> outbound = flights(result, "outboundFlights").getFirst();
        Map<String, Object> inbound = flights(result, "returnFlights").getFirst();

        Map<String, Object> booking = service.bookFlight(
                List.of((String) outbound.get("flightId"), (String) inbound.get("flightId")),
                List.of("Anna Petrosyan", "Aram Petrosyan"), "anna@example.com");

        assertThat(booking.get("status")).isEqualTo("confirmed");
        assertThat(booking.get("totalPrice")).isEqualTo(
                ((BigDecimal) outbound.get("totalPrice")).add((BigDecimal) inbound.get("totalPrice")));
        assertThat(service.getBooking((String) booking.get("bookingId"))).isEqualTo(booking);
    }

    @Test
    void returnsSearchedOffersById() {
        Map<String, Object> offer = flights(service.searchFlights("JFK", "EVN", "2026-10-15", null, 2, null, null),
                "outboundFlights").getFirst();

        assertThat(service.getOffer((String) offer.get("flightId"))).isEqualTo(offer);
        assertThatThrownBy(() -> service.getOffer("XY234")).hasMessageContaining("Unknown flightId 'XY234'");
    }

    @Test
    void rejectsFlightIdsThatNoSearchReturned() {
        assertThatThrownBy(() -> service.bookFlight(List.of("XY234"), List.of("Anna Petrosyan"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown flightId 'XY234'");
    }

    @Test
    void requiresOneNamePerPassenger() {
        Map<String, Object> result = service.searchFlights("JFK", "EVN", "2026-10-15", null, 2, null, null);
        String flightId = (String) flights(result, "outboundFlights").getFirst().get("flightId");

        assertThatThrownBy(() -> service.bookFlight(List.of(flightId), List.of(), null))
                .hasMessageContaining("passengerNames is required");
        assertThatThrownBy(() -> service.bookFlight(List.of(flightId), List.of("Anna Petrosyan"), null))
                .hasMessageContaining("offered for 2 passenger(s) but 1 name(s)");
    }

    @Test
    void rejectsInvalidDates() {
        assertThatThrownBy(() -> service.searchFlights("JFK", "EVN", "mid October", null, 2, null, null))
                .hasMessageContaining("departureDate must be a date in YYYY-MM-DD format");
        assertThatThrownBy(() -> service.searchFlights("JFK", "EVN", "2026-10-15", "2026-10-10", 2, null, null))
                .hasMessageContaining("returnDate must not be before departureDate");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> flights(Map<String, Object> result, String key) {
        return (List<Map<String, Object>>) result.get(key);
    }
}
