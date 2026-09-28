package am.hhovhann.travel.ai.hotel.config;

import am.hhovhann.travel.ai.core.a2a.ChatClientAgentExecutor;
import am.hhovhann.travel.ai.core.a2a.McpToolInvoker;
import io.a2a.server.agentexecution.AgentExecutor;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
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
public class HotelAgentConfig {

    /**
     * Tools callers may invoke directly with a structured (DataPart) request, bypassing the LLM. Booking is only
     * possible this way, with the exact offer the traveller confirmed.
     */
    private static final Set<String> STRUCTURED_TOOLS = Set.of("get_offer", "book_hotel", "get_booking");

    private static final String SYSTEM_PROMPT = """
            You are the Hotel Agent of a travel assistant. Today is {today}.
            Use the hotel tools to search hotels, look up offers, bookings and hotel details.
            - Resolve relative dates to YYYY-MM-DD using today's date.
            - If destination, check-in, check-out or guest count is missing, ask for it instead of guessing.
            - Base your answer only on tool results. Show a budget, a mid-range and a premium option when available,
              with the full hotelId, name, rating, pricePerNight and totalPrice for the stay as returned by the tool.
            - For "cheaper" requests search again and show the lowest-priced options.
            - You cannot book. Bookings are made by the travel assistant after the traveller confirms.
            - If a tool returns an error, report it as it is.
            """;

    @Bean
    public ChatClient hotelChatClient(ChatClient.Builder builder, ToolCallbackProvider mcpTools, ChatMemory chatMemory,
                                      @Value("${spring.ai.openai.timeout}") Duration llmTimeout) {
        // The LLM searches and explains; it cannot book (see STRUCTURED_TOOLS)
        ToolCallback[] llmTools = Arrays.stream(mcpTools.getToolCallbacks())
                .filter(tool -> !tool.getToolDefinition().name().contains("book_hotel"))
                .toArray(ToolCallback[]::new);
        return builder
                // Spring AI 2.0.1 sends a 60s timeout with every request unless set on the chat options,
                // which overrides spring.ai.openai.timeout; pass the configured value through explicitly
                .defaultOptions(OpenAiChatOptions.builder().timeout(llmTimeout))
                .defaultSystem(SYSTEM_PROMPT)
                .defaultToolCallbacks(llmTools)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    @Bean
    public AgentExecutor hotelAgentExecutor(ChatClient hotelChatClient, List<McpSyncClient> mcpClients) {
        return new ChatClientAgentExecutor(hotelChatClient, new McpToolInvoker(mcpClients, STRUCTURED_TOOLS));
    }
}
