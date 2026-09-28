package am.hhovhann.travel.ai.core.guardrail;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Arrays;

/**
 * Wraps a tool so its result reaches the LLM as {@link UntrustedContent}. The tool itself is unchanged.
 */
public class GuardedToolCallback implements ToolCallback {

    /** Tool results are capped at this length before they reach the model. */
    public static final int MAX_RESULT_CHARS = 20_000;

    private final ToolCallback delegate;

    public GuardedToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    public static ToolCallback[] guard(ToolCallback... tools) {
        return Arrays.stream(tools).map(GuardedToolCallback::new).toArray(ToolCallback[]::new);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return wrap(delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return wrap(delegate.call(toolInput, toolContext));
    }

    private String wrap(String result) {
        return UntrustedContent.wrap(getToolDefinition().name(), result, MAX_RESULT_CHARS);
    }
}
