package am.hhovhann.travel.ai.core.security;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * Internal authentication for an agent: its A2A endpoint requires the internal token (the agent card stays public),
 * and its MCP client sends the token to the MCP server. Import it next to {@code A2AServerConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
public class InternalAuthConfiguration {

    public static final String AGENT_CARD_PATH = "/.well-known/agent-card.json";

    private final String token;

    public InternalAuthConfiguration(@Value("${internal.api-token:}") String token) {
        this.token = InternalAuth.requireToken(token);
    }

    @Bean
    public InternalTokenFilter internalTokenFilter() {
        return new InternalTokenFilter(token, Set.of(AGENT_CARD_PATH));
    }

    /**
     * Picked up by Spring AI's MCP client auto-configuration for every Streamable HTTP connection.
     */
    @Bean
    public McpSyncHttpClientRequestCustomizer internalTokenMcpRequestCustomizer() {
        return (builder, method, uri, body, context) -> builder.header(InternalAuth.AUTHORIZATION, InternalAuth.bearer(token));
    }
}
