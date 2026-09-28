package am.hhovhann.travel.ai.core.a2a;

import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.server.tasks.TaskUpdater;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.TextPart;
import io.a2a.spec.TransportProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitWebConfig(A2AJsonRpcControllerTest.TestAgent.class)
class A2AJsonRpcControllerTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void servesAgentCardAtWellKnownPath() throws Exception {
        mockMvc.perform(get("/.well-known/agent-card.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Echo Agent"))
                .andExpect(jsonPath("$.preferredTransport").value("JSONRPC"));
    }

    @Test
    void messageSendReturnsCompletedTaskWithArtifact() throws Exception {
        mockMvc.perform(post("/").contentType(MediaType.APPLICATION_JSON).content("""
                        {"jsonrpc":"2.0","id":"1","method":"message/send","params":{"message":{"role":"user",
                         "kind":"message","messageId":"m1","contextId":"ctx-1",
                         "parts":[{"kind":"text","text":"hello"}]}}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("1"))
                .andExpect(jsonPath("$.result.contextId").value("ctx-1"))
                .andExpect(jsonPath("$.result.status.state").value("completed"))
                .andExpect(jsonPath("$.result.artifacts[0].parts[0].text").value("echo: hello"));
    }

    @Test
    void unknownMethodReturnsMethodNotFound() throws Exception {
        mockMvc.perform(post("/").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"foo/bar\",\"params\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.error.code").value(-32601));
    }

    @Test
    void streamingIsRejectedBecauseTheCardDoesNotAdvertiseIt() throws Exception {
        mockMvc.perform(post("/").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"message/stream\",\"params\":{}}"))
                .andExpect(jsonPath("$.error.code").value(-32004));
    }

    @Test
    void invalidJsonReturnsParseError() throws Exception {
        mockMvc.perform(post("/").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(jsonPath("$.error.code").value(-32700));
    }

    @Configuration
    @EnableWebMvc
    @Import(A2AServerConfiguration.class)
    static class TestAgent {

        @Bean
        AgentCard agentCard() {
            return new AgentCard.Builder()
                    .name("Echo Agent")
                    .description("Echoes the user input")
                    .url("http://localhost")
                    .preferredTransport(TransportProtocol.JSONRPC.asString())
                    .protocolVersion("0.3.0")
                    .version("1.0.0")
                    .capabilities(new AgentCapabilities.Builder().streaming(false).build())
                    .defaultInputModes(List.of("text/plain"))
                    .defaultOutputModes(List.of("text/plain"))
                    .skills(List.of())
                    .build();
        }

        @Bean
        AgentExecutor echoExecutor() {
            return new AgentExecutor() {
                @Override
                public void execute(RequestContext context, EventQueue eventQueue) {
                    TaskUpdater updater = new TaskUpdater(context, eventQueue);
                    updater.submit();
                    updater.startWork();
                    updater.addArtifact(List.of(new TextPart("echo: " + context.getUserInput(""))));
                    updater.complete();
                }

                @Override
                public void cancel(RequestContext context, EventQueue eventQueue) {
                    new TaskUpdater(context, eventQueue).cancel();
                }
            };
        }
    }
}
