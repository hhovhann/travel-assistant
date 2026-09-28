package am.hhovhann.travel.ai.flight.agent;

import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;
import io.a2a.spec.TransportProtocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class FlightAgentCardProducer {

    /**
     * @param publicUrl the URL other agents use to reach this agent; must be reachable from the caller
     *                  (e.g. http://flight-agent:8080 inside docker compose)
     */
    @Bean
    public AgentCard flightAgentCard(@Value("${a2a.agent.public-url}") String publicUrl) {
        return new AgentCard.Builder()
                .name("Flight Agent")
                .description("Searches, recommends and books flights across multiple airline providers")
                .url(publicUrl)
                .preferredTransport(TransportProtocol.JSONRPC.asString())
                .protocolVersion("0.3.0")
                .version("0.1.0")
                .capabilities(new AgentCapabilities.Builder()
                        .streaming(false)
                        .pushNotifications(false)
                        .stateTransitionHistory(false)
                        .build())
                .defaultInputModes(List.of("text/plain", "application/json"))
                .defaultOutputModes(List.of("text/plain", "application/json"))
                .skills(List.of(
                        new AgentSkill.Builder()
                                .id("flight_search")
                                .name("Flight Search")
                                .description("Find and compare flights between two cities for given dates and passengers")
                                .tags(List.of("flights", "search"))
                                .examples(List.of("Find flights from New York to Yerevan on 2026-10-15 for 2 passengers"))
                                .build(),
                        new AgentSkill.Builder()
                                .id("flight_booking")
                                .name("Flight Booking (structured)")
                                .description("Executed exactly as sent, without the LLM. Send a DataPart "
                                        + "{\"tool\": \"get_offer\" | \"book_flight\" | \"get_booking\", \"arguments\": {...}}. "
                                        + "Only offers returned by a search can be booked.")
                                .tags(List.of("flights", "booking"))
                                .inputModes(List.of("application/json"))
                                .outputModes(List.of("application/json"))
                                .examples(List.of("{\"tool\":\"book_flight\",\"arguments\":{\"flightIds\":[\"JOY1:JFK-EVN:2026-10-15\"],\"passengerNames\":[\"Anna Petrosyan\"],\"contactEmail\":\"anna@example.com\"}}"))
                                .build()
                ))
                .build();
    }
}
