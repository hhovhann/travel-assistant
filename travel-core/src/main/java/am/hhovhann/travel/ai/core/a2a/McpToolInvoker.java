package am.hhovhann.travel.ai.core.a2a;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.a2a.util.Utils;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Executes structured requests of the form {@code {"tool": "book_flight", "arguments": {...}}} directly against the
 * agent's MCP server. Only the allowed tools can be called.
 */
public class McpToolInvoker implements StructuredRequestHandler {

    private final List<McpSyncClient> mcpClients;
    private final Set<String> allowedTools;

    public McpToolInvoker(List<McpSyncClient> mcpClients, Set<String> allowedTools) {
        this.mcpClients = mcpClients;
        this.allowedTools = allowedTools;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> handle(Map<String, Object> request) {
        if (!(request.get("tool") instanceof String tool) || !allowedTools.contains(tool)) {
            throw new IllegalArgumentException("Unsupported tool '" + request.get("tool") + "', allowed: " + allowedTools);
        }
        Map<String, Object> arguments = request.get("arguments") instanceof Map<?, ?> args
                ? (Map<String, Object>) args
                : Map.of();

        McpSchema.CallToolResult result = clientFor(tool).callTool(new McpSchema.CallToolRequest(tool, arguments, null));
        String text = result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(content -> ((McpSchema.TextContent) content).text())
                .distinct() // error results can repeat the same message
                .collect(Collectors.joining("\n"));
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalArgumentException(text);
        }
        try {
            return Utils.OBJECT_MAPPER.readValue(text, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of("result", text);
        }
    }

    private McpSyncClient clientFor(String tool) {
        return mcpClients.stream()
                .filter(client -> client.listTools().tools().stream().anyMatch(t -> t.name().equals(tool)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No MCP server provides the tool '" + tool + "'"));
    }
}
