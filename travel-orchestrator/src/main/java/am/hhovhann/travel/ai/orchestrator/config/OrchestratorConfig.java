package am.hhovhann.travel.ai.orchestrator.config;

import am.hhovhann.travel.ai.core.guardrail.GuardedToolCallback;
import am.hhovhann.travel.ai.core.guardrail.InputGuardrailAdvisor;
import am.hhovhann.travel.ai.core.guardrail.UntrustedContent;
import am.hhovhann.travel.ai.core.security.InternalAuth;
import am.hhovhann.travel.ai.orchestrator.agent.RemoteAgent;
import am.hhovhann.travel.ai.orchestrator.agent.TravelAgentTools;
import am.hhovhann.travel.ai.orchestrator.booking.BookingService;
import am.hhovhann.travel.ai.orchestrator.booking.BookingTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class OrchestratorConfig {

    private static final String SYSTEM_PROMPT = """
            You are a travel planning assistant. Today is {today}.
            You coordinate two specialist agents through tools: askFlightAgent and askHotelAgent.

            Planning
            - Collect origin, destination, travel dates, number of travellers and budget or preferences.
              If an essential detail is missing, ask one concise question instead of guessing.
            - Turn relative dates ("mid October", "for 5 days") into exact YYYY-MM-DD dates before calling an agent.
            - For a full trip ask both agents, each with a complete, self-contained request. For a round trip give the
              Flight Agent the return date too, so both legs are searched.
            - Present at most 3 numbered options. Each option lists the full flightId of every leg, the hotelId, the
              prices and the total, exactly as the agents returned them.

            Accuracy
            - Use only what the agents returned in this conversation. Never invent or change flights, hotels, IDs,
              dates, names or prices, and never do your own price arithmetic; use the totals the agents return.
            - If the user refers to an option, ID or booking that is not in this conversation, say so and offer to
              search again.
            - If an agent reports an error or a timeout, tell the user the search did not complete. Never conclude
              from an error or timeout that no flights or hotels exist.
            - To refine a result ("make the hotel cheaper", "a later flight"), ask the relevant agent again with the
              changed criteria and keep all other trip details unchanged.

            Booking
            1. When the traveller picks an option, get the full name of every traveller and a contact email, unless
               already given. Never invent names.
            2. Call proposeBooking with the exact flightIds (every leg) and hotelId of that option and the names.
            3. The app then shows the exact summary with Confirm and Cancel buttons. Tell the traveller to review it
               and press Confirm or reply "yes". You cannot book yourself: never say a booking is made or confirmed
               unless getBookings lists it as confirmed.
            4. If proposeBooking reports an error, explain it and fix the input (ask for names, or search again).
            5. For any question about what is booked or pending, call getBookings and answer from its result only;
               confirmed bookings are identified by their bookingIds, not by the proposal id.
            """;

    @Bean
    public RemoteAgent flightAgent(@Value("${agents.flight.url}") String url,
                                   @Value("${agents.timeout:300s}") Duration timeout,
                                   @Value("${internal.api-token:}") String internalToken) {
        return new RemoteAgent("Flight Agent", url, timeout, InternalAuth.requireToken(internalToken));
    }

    @Bean
    public RemoteAgent hotelAgent(@Value("${agents.hotel.url}") String url,
                                  @Value("${agents.timeout:300s}") Duration timeout,
                                  @Value("${internal.api-token:}") String internalToken) {
        return new RemoteAgent("Hotel Agent", url, timeout, InternalAuth.requireToken(internalToken));
    }

    @Bean
    public ChatClient orchestratorChatClient(ChatClient.Builder builder, ChatMemory chatMemory,
                                             RemoteAgent flightAgent, RemoteAgent hotelAgent, BookingService bookingService,
                                             @Value("${spring.ai.openai.timeout}") Duration llmTimeout,
                                             @Value("${guardrails.max-input-chars}") int maxInputChars) {
        return builder
                // Spring AI 2.0.1 sends a 60s timeout with every request unless set on the chat options,
                // which overrides spring.ai.openai.timeout; pass the configured value through explicitly
                .defaultOptions(OpenAiChatOptions.builder().timeout(llmTimeout))
                .defaultSystem(SYSTEM_PROMPT + UntrustedContent.SYSTEM_PROMPT_RULE)
                // Agent answers and booking results reach the LLM marked as untrusted data, with suspected
                // injections redacted: an agent's answer may quote supplier text
                .defaultToolCallbacks(GuardedToolCallback.guard(ToolCallbacks.from(
                        new TravelAgentTools(flightAgent, hotelAgent), new BookingTools(bookingService))))
                // InputGuardrailAdvisor runs first, so a rejected message never reaches the model or the memory;
                // SimpleLoggerAdvisor logs each prompt and response at DEBUG (see docs/ONBOARDING.md)
                .defaultAdvisors(new InputGuardrailAdvisor(maxInputChars), MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new SimpleLoggerAdvisor())
                .build();
    }
}
