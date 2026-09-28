package am.hhovhann.travel.ai.mcp.hotel.service;

import am.hhovhann.travel.ai.mcp.hotel.provider.AccorProvider;
import am.hhovhann.travel.ai.mcp.hotel.provider.HolidayInnProvider;
import am.hhovhann.travel.ai.mcp.hotel.provider.MarriottProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HotelMcpServiceTest {

    private final HotelMcpService service = new HotelMcpService(
            List.of(new MarriottProvider(), new HolidayInnProvider(), new AccorProvider()));

    @Test
    void searchReturnsDatedIdsAndTotalForTheStay() {
        Map<String, Object> result = service.searchHotels("Yerevan", "2026-10-15", "2026-10-20", 2, 1, null);

        assertThat(result.get("nights")).isEqualTo(5L);
        List<Map<String, Object>> hotels = hotels(result);
        assertThat(hotels).isNotEmpty().allSatisfy(hotel -> {
            assertThat((String) hotel.get("hotelId")).endsWith(":Yerevan:2026-10-15:2026-10-20");
            assertThat(hotel.get("totalPrice"))
                    .isEqualTo(((BigDecimal) hotel.get("pricePerNight")).multiply(BigDecimal.valueOf(5)));
        });
        assertThat(hotels).extracting(hotel -> (BigDecimal) hotel.get("pricePerNight")).isSorted();
    }

    @Test
    void booksSearchedHotelAndCanRetrieveTheBooking() {
        Map<String, Object> hotel = hotels(service.searchHotels("Yerevan", "2026-10-15", "2026-10-20", 2, 1, null)).getFirst();

        Map<String, Object> booking = service.bookHotel((String) hotel.get("hotelId"), List.of("Anna Petrosyan"), null);

        assertThat(booking.get("status")).isEqualTo("confirmed");
        assertThat(booking.get("totalPrice")).isEqualTo(hotel.get("totalPrice"));
        assertThat(service.getBooking((String) booking.get("bookingId"))).isEqualTo(booking);
    }

    @Test
    void returnsSearchedOffersById() {
        Map<String, Object> offer = hotels(service.searchHotels("Yerevan", "2026-10-15", "2026-10-20", 2, 1, null)).getFirst();

        assertThat(service.getOffer((String) offer.get("hotelId"))).isEqualTo(offer);
        assertThatThrownBy(() -> service.getOffer("H789")).hasMessageContaining("Unknown hotelId 'H789'");
    }

    @Test
    void rejectsHotelIdsThatNoSearchReturned() {
        assertThatThrownBy(() -> service.bookHotel("H789", List.of("Anna Petrosyan"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown hotelId 'H789'");
    }

    @Test
    void requiresTheLeadGuestName() {
        String hotelId = (String) hotels(service.searchHotels("Yerevan", "2026-10-15", "2026-10-20", 2, 1, null))
                .getFirst().get("hotelId");

        assertThatThrownBy(() -> service.bookHotel(hotelId, List.of(" "), null))
                .hasMessageContaining("guestNames is required");
    }

    @Test
    void rejectsInvalidStay() {
        assertThatThrownBy(() -> service.searchHotels("Yerevan", "2026-10-20", "2026-10-15", 2, 1, null))
                .hasMessageContaining("checkOut must be after checkIn");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> hotels(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("hotels");
    }
}
