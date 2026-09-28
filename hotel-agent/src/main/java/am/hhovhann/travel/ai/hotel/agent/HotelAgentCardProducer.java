package am.hhovhann.travel.ai.hotel.agent;

import am.hhovhann.travel.ai.core.security.InternalAuth;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;
import io.a2a.spec.TransportProtocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class HotelAgentCardProducer {

    /**
     * @param publicUrl the URL other agents use to reach this agent; must be reachable from the caller
     *                  (e.g. http://hotel-agent:8082 inside docker compose)
     */
    @Bean
    public AgentCard hotelAgentCard(@Value("${a2a.agent.public-url}") String publicUrl) {
        return new AgentCard.Builder()
                .name("Hotel Agent")
                .description("Searches, recommends and books hotels across multiple hotel chains")
                .url(publicUrl)
                .preferredTransport(TransportProtocol.JSONRPC.asString())
                .protocolVersion("0.3.0")
                .version("0.1.0")
                .capabilities(new AgentCapabilities.Builder()
                        .streaming(false)
                        .pushNotifications(false)
                        .stateTransitionHistory(false)
                        .build())
                // Callers must send the internal bearer token (the card itself stays public)
                .securitySchemes(InternalAuth.securitySchemes())
                .security(InternalAuth.security())
                .defaultInputModes(List.of("text/plain", "application/json"))
                .defaultOutputModes(List.of("text/plain", "application/json"))
                .skills(List.of(
                        new AgentSkill.Builder()
                                .id("hotel_search")
                                .name("Hotel Search")
                                .description("Find and compare hotels in a city for given dates and guests")
                                .tags(List.of("hotels", "search"))
                                .examples(List.of("Find hotels in Yerevan from 2026-10-15 to 2026-10-20 for 2 guests"))
                                .build(),
                        new AgentSkill.Builder()
                                .id("hotel_booking")
                                .name("Hotel Booking (structured)")
                                .description("Executed exactly as sent, without the LLM. Send a DataPart "
                                        + "{\"tool\": \"get_offer\" | \"book_hotel\" | \"get_booking\", \"arguments\": {...}}. "
                                        + "Only offers returned by a search can be booked.")
                                .tags(List.of("hotels", "booking"))
                                .inputModes(List.of("application/json"))
                                .outputModes(List.of("application/json"))
                                .examples(List.of("{\"tool\":\"book_hotel\",\"arguments\":{\"hotelId\":\"HI1:Yerevan:2026-10-15:2026-10-20\",\"guestNames\":[\"Anna Petrosyan\"],\"contactEmail\":\"anna@example.com\"}}"))
                                .build()
                ))
                .build();
    }
}
