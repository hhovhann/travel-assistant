package am.hhovhann.travel.ai.orchestrator.booking;

import am.hhovhann.travel.ai.orchestrator.agent.RemoteAgent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookingServiceTest {

    private static final String OUT = "AERO1:JFK-EVN:2026-10-15";
    private static final String BACK = "AERO1:EVN-JFK:2026-10-20";
    private static final String HOTEL = "ACC3:Yerevan:2026-10-15:2026-10-20";
    private static final List<String> NAMES = List.of("Anna Petrosyan", "Aram Petrosyan");

    private final RemoteAgent flightAgent = mock(RemoteAgent.class);
    private final RemoteAgent hotelAgent = mock(RemoteAgent.class);
    private final ChatMemory chatMemory = MessageWindowChatMemory.builder().build();
    private final BookingService service = new BookingService(flightAgent, hotelAgent, chatMemory);

    BookingServiceTest() {
        when(flightAgent.invoke("get_offer", Map.of("flightId", OUT))).thenReturn(flight(OUT, "641.00"));
        when(flightAgent.invoke("get_offer", Map.of("flightId", BACK))).thenReturn(flight(BACK, "641.00"));
        when(hotelAgent.invoke("get_offer", Map.of("hotelId", HOTEL))).thenReturn(Map.of(
                "hotelId", HOTEL, "name", "Ibis Yerevan", "totalPrice", "450.00", "currency", "USD"));
    }

    @Test
    void proposalHoldsTheServerOffersAndTheirTotal() {
        BookingProposal proposal = service.propose("c1", List.of(OUT, BACK), HOTEL, NAMES, "anna@example.com");

        assertThat(proposal.flights()).extracting(flight -> flight.get("flightId")).containsExactly(OUT, BACK);
        assertThat(proposal.hotel().get("name")).isEqualTo("Ibis Yerevan");
        assertThat(proposal.totalPrice()).isEqualByComparingTo(new BigDecimal("1732.00"));
        assertThat(service.pendingFor("c1")).contains(proposal);
    }

    @Test
    void confirmBooksExactlyTheProposedOffersAndTravellers() {
        BookingProposal proposal = service.propose("c1", List.of(OUT, BACK), HOTEL, NAMES, "anna@example.com");
        when(flightAgent.invoke(eq("book_flight"), any())).thenReturn(Map.of("bookingId", "FB-1"));
        when(hotelAgent.invoke(eq("book_hotel"), any())).thenReturn(Map.of("bookingId", "HB-1"));

        BookingConfirmation confirmation = service.confirm(proposal.proposalId());

        assertThat(confirmation.status()).isEqualTo("confirmed");
        verify(flightAgent).invoke("book_flight", Map.of(
                "flightIds", List.of(OUT, BACK), "passengerNames", NAMES, "contactEmail", "anna@example.com"));
        verify(hotelAgent).invoke("book_hotel", Map.of(
                "hotelId", HOTEL, "guestNames", NAMES, "contactEmail", "anna@example.com"));
        assertThat(chatMemory.get("c1").getLast().getText()).contains("FB-1", "HB-1");
        assertThat(service.confirmedFor("c1")).containsExactly(confirmation);
        assertThat(service.pendingFor("c1")).isEmpty();
        assertThatThrownBy(() -> service.confirm(proposal.proposalId())).isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    void proposingTheSameBookingAgainKeepsTheExistingProposal() {
        BookingProposal first = service.propose("c1", List.of(OUT, BACK), HOTEL, NAMES, "anna@example.com");
        BookingProposal again = service.propose("c1", List.of(OUT, BACK), HOTEL, NAMES, "anna@example.com");

        assertThat(again.proposalId()).isEqualTo(first.proposalId());
    }

    @Test
    void reportsPartialFailure() {
        BookingProposal proposal = service.propose("c1", List.of(OUT), HOTEL, NAMES, null);
        when(flightAgent.invoke(eq("book_flight"), any())).thenReturn(Map.of("bookingId", "FB-1"));
        when(hotelAgent.invoke(eq("book_hotel"), any())).thenThrow(new IllegalStateException("Hotel Agent is unavailable"));

        BookingConfirmation confirmation = service.confirm(proposal.proposalId());

        assertThat(confirmation.status()).isEqualTo("partially_confirmed");
        assertThat(confirmation.errors()).containsExactly("Hotel booking failed: Hotel Agent is unavailable");
    }

    @Test
    void rejectsMissingNamesAndWrongPassengerCountBeforeAnythingIsProposed() {
        assertThatThrownBy(() -> service.propose("c1", List.of(OUT), null, List.of(" "), null))
                .hasMessageContaining("full name of every traveller");
        assertThatThrownBy(() -> service.propose("c1", List.of(OUT), null, List.of("Anna Petrosyan"), null))
                .hasMessageContaining("searched for 2 passenger(s) but 1 traveller name(s)");
        assertThat(service.pendingFor("c1")).isEmpty();
    }

    @Test
    void unknownOfferIsRejected() {
        when(flightAgent.invoke("get_offer", Map.of("flightId", "XY234")))
                .thenThrow(new IllegalStateException("Unknown flightId 'XY234'"));

        assertThatThrownBy(() -> service.propose("c1", List.of("XY234"), null, NAMES, null))
                .hasMessageContaining("Unknown flightId 'XY234'");
    }

    @Test
    void newProposalReplacesThePreviousOneAndCancelBooksNothing() {
        BookingProposal first = service.propose("c1", List.of(OUT), null, NAMES, null);
        BookingProposal second = service.propose("c1", List.of(OUT), HOTEL, NAMES, null);

        assertThatThrownBy(() -> service.confirm(first.proposalId())).isInstanceOf(BookingNotFoundException.class);
        service.cancel(second.proposalId());
        assertThat(service.pendingFor("c1")).isEmpty();
        verify(flightAgent, never()).invoke(eq("book_flight"), any());
    }

    private static Map<String, Object> flight(String flightId, String total) {
        return Map.of("flightId", flightId, "flightNumber", "AG201", "date", flightId.substring(flightId.lastIndexOf(':') + 1),
                "passengers", 2, "totalPrice", total, "currency", "USD");
    }
}
