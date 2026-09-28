package am.hhovhann.travel.ai.core.a2a;

import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.TransportProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringJUnitWebConfig(StructuredRequestTest.TestAgent.class)
class StructuredRequestTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ChatClient chatClient;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void dataPartIsHandledWithoutTheLlmAndReturnedAsDataArtifact() throws Exception {
        mockMvc.perform(post("/").contentType(MediaType.APPLICATION_JSON).content(request("""
                        {"tool":"book","arguments":{"id":"JOY1"}}""")))
                .andExpect(jsonPath("$.result.status.state").value("completed"))
                .andExpect(jsonPath("$.result.artifacts[0].parts[0].kind").value("data"))
                .andExpect(jsonPath("$.result.artifacts[0].parts[0].data.booked").value("JOY1"));
        verifyNoInteractions(chatClient);
    }

    @Test
    void rejectedDataRequestFailsTheTaskWithTheReason() throws Exception {
        mockMvc.perform(post("/").contentType(MediaType.APPLICATION_JSON).content(request("""
                        {"tool":"book","arguments":{"id":"XY234"}}""")))
                .andExpect(jsonPath("$.result.status.state").value("failed"))
                .andExpect(jsonPath("$.result.status.message.parts[0].text").value("Unknown id 'XY234'"));
    }

    private static String request(String data) {
        return """
                {"jsonrpc":"2.0","id":"1","method":"message/send","params":{"message":{"role":"user",
                 "kind":"message","messageId":"m1","parts":[{"kind":"data","data":%s}]}}}""".formatted(data);
    }

    @Configuration
    @EnableWebMvc
    @Import(A2AServerConfiguration.class)
    static class TestAgent {

        @Bean
        ChatClient chatClient() {
            return mock(ChatClient.class);
        }

        @Bean
        AgentCard agentCard() {
            return new AgentCard.Builder()
                    .name("Booking Agent")
                    .description("Books by id")
                    .url("http://localhost")
                    .preferredTransport(TransportProtocol.JSONRPC.asString())
                    .protocolVersion("0.3.0")
                    .version("1.0.0")
                    .capabilities(new AgentCapabilities.Builder().streaming(false).build())
                    .defaultInputModes(List.of("application/json"))
                    .defaultOutputModes(List.of("application/json"))
                    .skills(List.of())
                    .build();
        }

        @Bean
        @SuppressWarnings("unchecked")
        AgentExecutor executor(ChatClient chatClient) {
            return new ChatClientAgentExecutor(chatClient, request -> {
                String id = (String) ((Map<String, Object>) request.get("arguments")).get("id");
                if (!id.startsWith("JOY")) {
                    throw new IllegalArgumentException("Unknown id '" + id + "'");
                }
                return Map.of("booked", id);
            });
        }
    }
}
