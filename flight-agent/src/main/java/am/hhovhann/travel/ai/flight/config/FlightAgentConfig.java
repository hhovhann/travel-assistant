package am.hhovhann.travel.ai.flight.config;

import am.hhovhann.travel.ai.core.a2a.ChatClientAgentExecutor;
import am.hhovhann.travel.ai.core.a2a.McpToolInvoker;
import io.a2a.server.agentexecution.AgentExecutor;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
public class FlightAgentConfig {

    /**
     * Tools callers may invoke directly with a structured (DataPart) request, bypassing the LLM. Booking is only
     * possible this way, with the exact offer the traveller confirmed.
     */
    private static final Set<String> STRUCTURED_TOOLS = Set.of("get_offer", "book_flight", "get_booking");

    private static final String SYSTEM_PROMPT = """
            You are the Flight Agent of a travel assistant. Today is {today}.
            Use the flight tools to search flights, look up offers and bookings, and check flight status.
            - Convert city names to IATA airport codes (e.g. New York -> JFK, Yerevan -> EVN) before searching.
            - Resolve relative dates to YYYY-MM-DD using today's date. For round trips pass the returnDate.
            - If origin, destination, departure date or passenger count is missing, ask for it instead of guessing.
            - Base your answer only on tool results. Show the cheapest and the best-value options per leg with the
              full flightId, flight number, times, stops, price per passenger and totalPrice as returned by the tool.
            - You cannot book. Bookings are made by the travel assistant after the traveller confirms.
            - If a tool returns an error, report it as it is.
            """;

    @Bean
    public ChatClient flightChatClient(ChatClient.Builder builder, ToolCallbackProvider mcpTools, ChatMemory chatMemory,
                                       @Value("${spring.ai.openai.timeout}") Duration llmTimeout) {
        // The LLM searches and explains; it cannot book (see STRUCTURED_TOOLS)
        ToolCallback[] llmTools = Arrays.stream(mcpTools.getToolCallbacks())
                .filter(tool -> !tool.getToolDefinition().name().contains("book_flight"))
                .toArray(ToolCallback[]::new);
        return builder
                // Spring AI 2.0.1 sends a 60s timeout with every request unless set on the chat options,
                // which overrides spring.ai.openai.timeout; pass the configured value through explicitly
                .defaultOptions(OpenAiChatOptions.builder().timeout(llmTimeout))
                .defaultSystem(SYSTEM_PROMPT)
                .defaultToolCallbacks(llmTools)
                // SimpleLoggerAdvisor logs each prompt and response at DEBUG (see docs/ONBOARDING.md)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build(), new SimpleLoggerAdvisor())
                .build();
    }

    @Bean
    public AgentExecutor flightAgentExecutor(ChatClient flightChatClient, List<McpSyncClient> mcpClients) {
        return new ChatClientAgentExecutor(flightChatClient, new McpToolInvoker(mcpClients, STRUCTURED_TOOLS));
    }
}
