package am.hhovhann.travel.ai.mcp.flight.service;

import am.hhovhann.travel.ai.mcp.flight.provider.FlightProvider;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exposes the aggregated flight providers as MCP tools.
 * <p>
 * Every search result is registered as an offer under a dated flightId (e.g. {@code JOY1:JFK-EVN:2026-10-15}) with a
 * computed total price. Only registered offers can be booked, so a model cannot book a flight, price or passenger
 * count that no search returned.
 */
@Service
public class FlightMcpService {

    private static final int MAX_RESULTS_PER_LEG = 10;

    private final List<FlightProvider> flightProviders;
    private final Map<String, Map<String, Object>> offers = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> bookings = new ConcurrentHashMap<>();

    public FlightMcpService(List<FlightProvider> flightProviders) {
        this.flightProviders = flightProviders;
    }

    @McpTool(name = "search_flights", description = """
            Search flights from all providers, cheapest first. With a returnDate, returnFlights holds the flights
            back from the destination. price is per passenger, totalPrice is for all passengers. Use the returned
            flightId values for booking.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public Map<String, Object> searchFlights(
            @McpToolParam(description = "Origin IATA airport code, e.g. JFK") String from,
            @McpToolParam(description = "Destination IATA airport code, e.g. EVN") String to,
            @McpToolParam(description = "Departure date in YYYY-MM-DD format") String departureDate,
            @McpToolParam(description = "Return date in YYYY-MM-DD format, for round trips", required = false) String returnDate,
            @McpToolParam(description = "Number of passengers") Integer passengers,
            @McpToolParam(description = "Cabin class: economy, business or first", required = false) String cabinClass,
            @McpToolParam(description = "Additional preferences", required = false) String preferences) {

        requireText(from, "from");
        requireText(to, "to");
        LocalDate departure = parseDate(departureDate, "departureDate");
        if (passengers == null || passengers < 1) {
            throw new IllegalArgumentException("passengers must be at least 1");
        }
        String cabin = cabinClass != null ? cabinClass : "economy";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passengers", passengers);
        result.put("outboundFlights", searchLeg(from, to, departure, passengers, cabin, preferences));
        if (returnDate != null && !returnDate.isBlank()) {
            LocalDate back = parseDate(returnDate, "returnDate");
            if (back.isBefore(departure)) {
                throw new IllegalArgumentException("returnDate must not be before departureDate");
            }
            result.put("returnFlights", searchLeg(to, from, back, passengers, cabin, preferences));
        }
        result.put("providers", flightProviders.stream().map(FlightProvider::getName).toList());
        return result;
    }

    @McpTool(name = "book_flight", description = """
            Book one or more flights (e.g. outbound and return) for the same passengers. Only flightId values
            returned by search_flights are accepted, and one full name is required per passenger of the offer.
            Returns the booking with its bookingId.""")
    public Map<String, Object> bookFlight(
            @McpToolParam(description = "flightId values from search_flights, e.g. [\"JOY1:JFK-EVN:2026-10-15\"]") List<String> flightIds,
            @McpToolParam(description = "Full name of every passenger, e.g. [\"Anna Petrosyan\", \"Aram Petrosyan\"]") List<String> passengerNames,
            @McpToolParam(description = "Contact email for the booking", required = false) String contactEmail) {

        if (flightIds == null || flightIds.isEmpty()) {
            throw new IllegalArgumentException("flightIds is required");
        }
        List<String> names = cleanNames(passengerNames);
        if (names.isEmpty()) {
            throw new IllegalArgumentException("passengerNames is required: ask the traveller for the full name of every passenger");
        }

        List<Map<String, Object>> legs = new ArrayList<>();
        for (String flightId : flightIds) {
            Map<String, Object> offer = offers.get(flightId);
            if (offer == null) {
                throw new IllegalArgumentException("Unknown flightId '" + flightId
                        + "'. Only flightIds returned by search_flights can be booked; search again.");
            }
            int offerPassengers = (Integer) offer.get("passengers");
            if (names.size() != offerPassengers) {
                throw new IllegalArgumentException("Flight " + flightId + " was offered for " + offerPassengers
                        + " passenger(s) but " + names.size() + " name(s) were given");
            }
            legs.add(offer);
        }

        Map<String, Object> passengerDetails = new LinkedHashMap<>();
        passengerDetails.put("passengerNames", names);
        if (contactEmail != null && !contactEmail.isBlank()) {
            passengerDetails.put("contactEmail", contactEmail);
        }

        List<Map<String, Object>> bookedLegs = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> offer : legs) {
            String flightId = (String) offer.get("flightId");
            Map<String, Object> confirmation = providerFor(flightId).bookFlight(flightId, passengerDetails);
            Map<String, Object> leg = new LinkedHashMap<>(offer);
            leg.put("confirmationNumber", confirmation.get("confirmationNumber"));
            bookedLegs.add(leg);
            total = total.add((BigDecimal) offer.get("totalPrice"));
        }

        Map<String, Object> booking = new LinkedHashMap<>();
        booking.put("bookingId", "FB-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        booking.put("status", "confirmed");
        booking.putAll(passengerDetails);
        booking.put("flights", bookedLegs);
        booking.put("totalPrice", total);
        booking.put("currency", legs.getFirst().get("currency"));
        bookings.put((String) booking.get("bookingId"), booking);
        return booking;
    }

    @McpTool(name = "get_offer", description = "Get a flight offer returned by search_flights, by its flightId",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public Map<String, Object> getOffer(@McpToolParam(description = "flightId from search_flights, e.g. JOY1:JFK-EVN:2026-10-15") String flightId) {
        Map<String, Object> offer = offers.get(flightId);
        if (offer == null) {
            throw new IllegalArgumentException("Unknown flightId '" + flightId
                    + "'. Only flightIds returned by search_flights are valid; search again.");
        }
        return offer;
    }

    @McpTool(name = "get_booking", description = "Get a flight booking by its bookingId",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public Map<String, Object> getBooking(@McpToolParam(description = "bookingId returned by book_flight, e.g. FB-1A2B3C4D") String bookingId) {
        Map<String, Object> booking = bookings.get(bookingId);
        if (booking == null) {
            throw new IllegalArgumentException("Unknown bookingId '" + bookingId + "'");
        }
        return booking;
    }

    @McpTool(name = "get_recommendations", description = "Get general flight recommendations for a destination (not bookable offers)",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public Map<String, Object> getRecommendations(
            @McpToolParam(description = "Destination city or airport") String destination,
            @McpToolParam(description = "User preferences", required = false) String preferences) {

        List<Map<String, Object>> recommendations = flightProviders.stream()
                .flatMap(provider -> provider.getRecommendations(destination, preferences).stream())
                .toList();

        return Map.of(
                "recommendations", recommendations,
                "total", recommendations.size()
        );
    }

    @McpTool(name = "get_flight_status", description = "Get flight status information",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public Map<String, Object> getFlightStatus(
            @McpToolParam(description = "Flight number, e.g. JY101") String flightNumber,
            @McpToolParam(description = "Airline name or code") String airline) {

        return flightProviders.stream()
                .filter(provider -> provider.canHandleAirline(airline))
                .findFirst()
                .map(provider -> provider.getFlightStatus(flightNumber, airline))
                .orElse(Map.of("status", "unknown", "message", "Flight status not available"));
    }

    private List<Map<String, Object>> searchLeg(String from, String to, LocalDate date, int passengers,
                                                String cabin, String preferences) {
        return flightProviders.stream()
                .flatMap(provider -> provider.searchFlights(from, to, date.toString(), null, passengers, cabin, preferences).stream())
                .map(flight -> registerOffer(flight, date, passengers))
                .sorted(Comparator.comparing(offer -> (BigDecimal) offer.get("price")))
                .limit(MAX_RESULTS_PER_LEG)
                .toList();
    }

    private Map<String, Object> registerOffer(Map<String, Object> flight, LocalDate date, int passengers) {
        Map<String, Object> offer = new LinkedHashMap<>(flight);
        String flightId = flight.get("flightId") + ":" + date;
        BigDecimal price = money(((Number) flight.get("price")).doubleValue());
        offer.put("flightId", flightId);
        offer.put("date", date.toString());
        offer.put("price", price);
        offer.put("passengers", passengers);
        offer.put("totalPrice", price.multiply(BigDecimal.valueOf(passengers)));
        offers.put(flightId, offer);
        return offer;
    }

    private FlightProvider providerFor(String flightId) {
        return flightProviders.stream()
                .filter(provider -> provider.canHandleFlight(flightId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No provider found for flight: " + flightId));
    }

    private static List<String> cleanNames(List<String> names) {
        return names == null ? List.of() : names.stream().filter(name -> name != null && !name.isBlank()).map(String::trim).toList();
    }

    private static BigDecimal money(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static LocalDate parseDate(String value, String name) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException(name + " must be a date in YYYY-MM-DD format, got '" + value + "'");
        }
    }
}
