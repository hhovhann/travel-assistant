package am.hhovhann.travel.ai.mcp.hotel.service;

import am.hhovhann.travel.ai.mcp.hotel.provider.HotelProvider;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exposes the aggregated hotel providers as MCP tools.
 * <p>
 * Every search result is registered as an offer under a dated hotelId (e.g. {@code HI1:Yerevan:2026-10-15:2026-10-20})
 * with a computed total price for the stay. Only registered offers can be booked, so a model cannot book a hotel,
 * price or stay that no search returned.
 */
@Service
public class HotelMcpService {

    private static final int MAX_RESULTS = 15;

    private final List<HotelProvider> hotelProviders;
    private final Map<String, Map<String, Object>> offers = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> bookings = new ConcurrentHashMap<>();

    public HotelMcpService(List<HotelProvider> hotelProviders) {
        this.hotelProviders = hotelProviders;
    }

    @McpTool(name = "search_hotels", description = """
            Search hotels from all providers, cheapest first. pricePerNight is per room, totalPrice is for all
            nights and rooms. Use the returned hotelId values for booking.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public Map<String, Object> searchHotels(
            @McpToolParam(description = "Destination city, e.g. Yerevan") String destination,
            @McpToolParam(description = "Check-in date in YYYY-MM-DD format") String checkIn,
            @McpToolParam(description = "Check-out date in YYYY-MM-DD format") String checkOut,
            @McpToolParam(description = "Number of guests") Integer guests,
            @McpToolParam(description = "Number of rooms", required = false) Integer rooms,
            @McpToolParam(description = "Additional preferences", required = false) String preferences) {

        if (destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("destination is required");
        }
        Stay stay = Stay.of(checkIn, checkOut, guests, rooms);
        List<Map<String, Object>> hotels = hotelProviders.stream()
                .flatMap(provider -> provider.searchHotels(destination, checkIn, checkOut, stay.guests(), stay.rooms(), preferences).stream())
                .map(hotel -> registerOffer(hotel, stay))
                .sorted(Comparator.comparing(offer -> (BigDecimal) offer.get("pricePerNight")))
                .limit(MAX_RESULTS)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkIn", checkIn);
        result.put("checkOut", checkOut);
        result.put("nights", stay.nights());
        result.put("hotels", hotels);
        result.put("providers", hotelProviders.stream().map(HotelProvider::getName).toList());
        return result;
    }

    @McpTool(name = "search_near_airport", description = """
            Find hotels near an airport. Pass checkIn, checkOut and guests to get bookable offers with prices.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public Map<String, Object> searchNearAirport(
            @McpToolParam(description = "IATA airport code, e.g. EVN") String airportCode,
            @McpToolParam(description = "Check-in date in YYYY-MM-DD format", required = false) String checkIn,
            @McpToolParam(description = "Check-out date in YYYY-MM-DD format", required = false) String checkOut,
            @McpToolParam(description = "Number of guests", required = false) Integer guests) {

        boolean bookable = checkIn != null && checkOut != null && guests != null;
        Stay stay = bookable ? Stay.of(checkIn, checkOut, guests, 1) : null;
        List<Map<String, Object>> hotels = hotelProviders.stream()
                .flatMap(provider -> provider.searchNearAirport(airportCode, checkIn, checkOut).stream())
                .map(hotel -> bookable ? registerOffer(hotel, stay) : hotel)
                .toList();

        return Map.of(
                "hotels", hotels,
                "airportCode", airportCode,
                "bookable", bookable
        );
    }

    @McpTool(name = "book_hotel", description = """
            Book a hotel. Only hotelId values returned by search_hotels (or search_near_airport with dates) are
            accepted, and the full name of at least the lead guest is required. Returns the booking with its
            bookingId.""")
    public Map<String, Object> bookHotel(
            @McpToolParam(description = "hotelId from search_hotels, e.g. HI1:Yerevan:2026-10-15:2026-10-20") String hotelId,
            @McpToolParam(description = "Full names of the guests, lead guest first, e.g. [\"Anna Petrosyan\"]") List<String> guestNames,
            @McpToolParam(description = "Contact email for the booking", required = false) String contactEmail) {

        Map<String, Object> offer = offers.get(hotelId);
        if (offer == null) {
            throw new IllegalArgumentException("Unknown hotelId '" + hotelId
                    + "'. Only hotelIds returned by search_hotels can be booked; search again.");
        }
        List<String> names = guestNames == null ? List.of()
                : guestNames.stream().filter(name -> name != null && !name.isBlank()).map(String::trim).toList();
        if (names.isEmpty()) {
            throw new IllegalArgumentException("guestNames is required: ask the traveller for the full name of the lead guest");
        }

        Map<String, Object> guestDetails = new LinkedHashMap<>();
        guestDetails.put("guestNames", names);
        if (contactEmail != null && !contactEmail.isBlank()) {
            guestDetails.put("contactEmail", contactEmail);
        }
        Map<String, Object> confirmation = hotelProviders.stream()
                .filter(provider -> provider.canHandleHotel(hotelId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No provider found for hotel: " + hotelId))
                .bookHotel(hotelId, guestDetails);

        Map<String, Object> booking = new LinkedHashMap<>();
        booking.put("bookingId", "HB-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        booking.put("status", "confirmed");
        booking.putAll(guestDetails);
        booking.put("hotel", offer);
        booking.put("confirmationNumber", confirmation.get("confirmationNumber"));
        booking.put("totalPrice", offer.get("totalPrice"));
        booking.put("currency", offer.get("currency"));
        bookings.put((String) booking.get("bookingId"), booking);
        return booking;
    }

    @McpTool(name = "get_offer", description = "Get a hotel offer returned by search_hotels, by its hotelId",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public Map<String, Object> getOffer(@McpToolParam(description = "hotelId from search_hotels, e.g. HI1:Yerevan:2026-10-15:2026-10-20") String hotelId) {
        Map<String, Object> offer = offers.get(hotelId);
        if (offer == null) {
            throw new IllegalArgumentException("Unknown hotelId '" + hotelId
                    + "'. Only hotelIds returned by search_hotels are valid; search again.");
        }
        return offer;
    }

    @McpTool(name = "get_booking", description = "Get a hotel booking by its bookingId",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public Map<String, Object> getBooking(@McpToolParam(description = "bookingId returned by book_hotel, e.g. HB-1A2B3C4D") String bookingId) {
        Map<String, Object> booking = bookings.get(bookingId);
        if (booking == null) {
            throw new IllegalArgumentException("Unknown bookingId '" + bookingId + "'");
        }
        return booking;
    }

    @McpTool(name = "get_recommendations", description = "Get general hotel recommendations for a destination (not bookable offers)",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public Map<String, Object> getRecommendations(
            @McpToolParam(description = "Destination city or area") String destination,
            @McpToolParam(description = "User preferences", required = false) String preferences) {

        List<Map<String, Object>> recommendations = hotelProviders.stream()
                .flatMap(provider -> provider.getRecommendations(destination, preferences).stream())
                .toList();

        return Map.of(
                "recommendations", recommendations,
                "total", recommendations.size()
        );
    }

    @McpTool(name = "get_hotel_details", description = "Get details such as amenities and policies for a hotel by hotelId",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public Map<String, Object> getHotelDetails(
            @McpToolParam(description = "Hotel identifier from search_hotels") String hotelId) {

        return hotelProviders.stream()
                .filter(provider -> provider.canHandleHotel(hotelId))
                .findFirst()
                .map(provider -> provider.getHotelDetails(hotelId))
                .orElse(Map.of("error", "Hotel not found", "hotelId", hotelId));
    }

    private Map<String, Object> registerOffer(Map<String, Object> hotel, Stay stay) {
        Map<String, Object> offer = new LinkedHashMap<>(hotel);
        String hotelId = hotel.get("hotelId") + ":" + stay.checkIn() + ":" + stay.checkOut();
        BigDecimal pricePerNight = BigDecimal.valueOf(((Number) hotel.get("pricePerNight")).doubleValue())
                .setScale(2, RoundingMode.HALF_UP);
        offer.put("hotelId", hotelId);
        offer.put("pricePerNight", pricePerNight);
        offer.put("checkIn", stay.checkIn().toString());
        offer.put("checkOut", stay.checkOut().toString());
        offer.put("nights", stay.nights());
        offer.put("guests", stay.guests());
        offer.put("rooms", stay.rooms());
        offer.put("totalPrice", pricePerNight.multiply(BigDecimal.valueOf(stay.nights() * stay.rooms())));
        offers.put(hotelId, offer);
        return offer;
    }

    private record Stay(LocalDate checkIn, LocalDate checkOut, int guests, int rooms) {

        static Stay of(String checkIn, String checkOut, Integer guests, Integer rooms) {
            LocalDate in = parseDate(checkIn, "checkIn");
            LocalDate out = parseDate(checkOut, "checkOut");
            if (!out.isAfter(in)) {
                throw new IllegalArgumentException("checkOut must be after checkIn");
            }
            if (guests == null || guests < 1) {
                throw new IllegalArgumentException("guests must be at least 1");
            }
            return new Stay(in, out, guests, rooms != null && rooms > 0 ? rooms : 1);
        }

        long nights() {
            return ChronoUnit.DAYS.between(checkIn, checkOut);
        }

        private static LocalDate parseDate(String value, String name) {
            try {
                return LocalDate.parse(value);
            } catch (DateTimeParseException | NullPointerException e) {
                throw new IllegalArgumentException(name + " must be a date in YYYY-MM-DD format, got '" + value + "'");
            }
        }
    }
}
