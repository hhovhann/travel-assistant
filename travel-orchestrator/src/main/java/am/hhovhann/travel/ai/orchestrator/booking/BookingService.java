package am.hhovhann.travel.ai.orchestrator.booking;

import am.hhovhann.travel.ai.orchestrator.agent.RemoteAgent;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Two-step booking that does not depend on the LLM getting it right.
 * <ol>
 *     <li>{@link #propose}: the LLM names the chosen offer IDs and travellers; the offers are fetched from the MCP
 *     servers (so IDs, dates and prices are the real ones) and stored as a proposal the traveller can review.</li>
 *     <li>{@link #confirm}: when the traveller confirms in the app, exactly that proposal is booked through structured
 *     A2A requests, which the agents execute without their LLM.</li>
 * </ol>
 */
@Service
public class BookingService {

    private final RemoteAgent flightAgent;
    private final RemoteAgent hotelAgent;
    private final ChatMemory chatMemory;
    private final Map<String, BookingProposal> proposals = new ConcurrentHashMap<>();
    private final Map<String, List<BookingConfirmation>> confirmedByConversation = new ConcurrentHashMap<>();

    public BookingService(@Qualifier("flightAgent") RemoteAgent flightAgent,
                          @Qualifier("hotelAgent") RemoteAgent hotelAgent,
                          ChatMemory chatMemory) {
        this.flightAgent = flightAgent;
        this.hotelAgent = hotelAgent;
        this.chatMemory = chatMemory;
    }

    /**
     * @throws IllegalArgumentException if the input is incomplete or does not match the offers
     * @throws IllegalStateException if an offer is unknown to the MCP server or an agent is unavailable
     */
    public BookingProposal propose(String conversationId, List<String> flightIds, String hotelId,
                                   List<String> travellerNames, String contactEmail) {
        List<String> flightOfferIds = flightIds == null ? List.of() : flightIds.stream().filter(this::hasText).toList();
        if (flightOfferIds.isEmpty() && !hasText(hotelId)) {
            throw new IllegalArgumentException("Nothing to book: pass the flightIds and/or the hotelId of the chosen option");
        }
        List<String> names = travellerNames == null ? List.of()
                : travellerNames.stream().filter(this::hasText).map(String::trim).toList();
        if (names.isEmpty()) {
            throw new IllegalArgumentException("The full name of every traveller is required; ask the traveller for them");
        }

        List<Map<String, Object>> flights = flightOfferIds.stream()
                .map(flightId -> flightAgent.invoke("get_offer", Map.of("flightId", flightId)))
                .toList();
        for (Map<String, Object> flight : flights) {
            int passengers = ((Number) flight.get("passengers")).intValue();
            if (passengers != names.size()) {
                throw new IllegalArgumentException("Flight " + flight.get("flightId") + " was searched for " + passengers
                        + " passenger(s) but " + names.size() + " traveller name(s) were given");
            }
        }
        Map<String, Object> hotel = hasText(hotelId) ? hotelAgent.invoke("get_offer", Map.of("hotelId", hotelId)) : null;
        String email = hasText(contactEmail) ? contactEmail.trim() : null;

        // Proposing the same booking again (e.g. after "yes" is typed) keeps the proposal the traveller already sees
        Optional<BookingProposal> pending = pendingFor(conversationId);
        if (pending.isPresent() && pending.get().flights().equals(flights) && Objects.equals(pending.get().hotel(), hotel)
                && pending.get().travellerNames().equals(names) && Objects.equals(pending.get().contactEmail(), email)) {
            return pending.get();
        }

        List<Map<String, Object>> offers = new ArrayList<>(flights);
        if (hotel != null) {
            offers.add(hotel);
        }
        BigDecimal total = offers.stream()
                .map(offer -> new BigDecimal(offer.get("totalPrice").toString()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BookingProposal proposal = new BookingProposal("BP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                conversationId, flights, hotel, names, email, total, (String) offers.getFirst().get("currency"));
        // Only the latest proposal of a conversation can be confirmed
        proposals.values().removeIf(existing -> existing.conversationId().equals(conversationId));
        proposals.put(proposal.proposalId(), proposal);
        return proposal;
    }

    public Optional<BookingProposal> pendingFor(String conversationId) {
        return proposals.values().stream()
                .filter(proposal -> proposal.conversationId().equals(conversationId))
                .findFirst();
    }

    public BookingConfirmation confirm(String proposalId) {
        BookingProposal proposal = proposals.remove(proposalId);
        if (proposal == null) {
            throw new BookingNotFoundException(proposalId);
        }

        List<String> errors = new ArrayList<>();
        Map<String, Object> flightBooking = null;
        Map<String, Object> hotelBooking = null;
        if (!proposal.flights().isEmpty()) {
            Map<String, Object> arguments = contact(proposal, Map.of(
                    "flightIds", proposal.flights().stream().map(flight -> flight.get("flightId")).toList(),
                    "passengerNames", proposal.travellerNames()));
            try {
                flightBooking = flightAgent.invoke("book_flight", arguments);
            } catch (RuntimeException e) {
                errors.add("Flight booking failed: " + e.getMessage());
            }
        }
        if (proposal.hotel() != null) {
            Map<String, Object> arguments = contact(proposal, Map.of(
                    "hotelId", proposal.hotel().get("hotelId"),
                    "guestNames", proposal.travellerNames()));
            try {
                hotelBooking = hotelAgent.invoke("book_hotel", arguments);
            } catch (RuntimeException e) {
                errors.add("Hotel booking failed: " + e.getMessage());
            }
        }

        String status = errors.isEmpty() ? "confirmed" : (flightBooking != null || hotelBooking != null)
                ? "partially_confirmed" : "failed";
        BookingConfirmation confirmation = new BookingConfirmation(proposalId, status, flightBooking, hotelBooking,
                proposal.totalPrice(), proposal.currency(), errors);
        confirmedByConversation.computeIfAbsent(proposal.conversationId(), id -> new CopyOnWriteArrayList<>()).add(confirmation);
        remember(proposal.conversationId(), describe(confirmation));
        return confirmation;
    }

    public List<BookingConfirmation> confirmedFor(String conversationId) {
        return confirmedByConversation.getOrDefault(conversationId, List.of());
    }

    public void cancel(String proposalId) {
        BookingProposal proposal = proposals.remove(proposalId);
        if (proposal == null) {
            throw new BookingNotFoundException(proposalId);
        }
        remember(proposal.conversationId(), "The traveller cancelled booking proposal " + proposalId + "; nothing was booked.");
    }

    /**
     * Records the outcome in the conversation, so the assistant knows what was booked on the next turn.
     */
    private void remember(String conversationId, String note) {
        chatMemory.add(conversationId, new AssistantMessage(note));
    }

    private static String describe(BookingConfirmation confirmation) {
        StringBuilder note = new StringBuilder("Booking " + confirmation.proposalId() + " " + confirmation.status() + ".");
        if (confirmation.flightBooking() != null) {
            note.append(" Flight bookingId ").append(confirmation.flightBooking().get("bookingId")).append('.');
        }
        if (confirmation.hotelBooking() != null) {
            note.append(" Hotel bookingId ").append(confirmation.hotelBooking().get("bookingId")).append('.');
        }
        confirmation.errors().forEach(error -> note.append(' ').append(error));
        return note.toString();
    }

    private static Map<String, Object> contact(BookingProposal proposal, Map<String, Object> arguments) {
        Map<String, Object> withContact = new LinkedHashMap<>(arguments);
        if (proposal.contactEmail() != null) {
            withContact.put("contactEmail", proposal.contactEmail());
        }
        return withContact;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
