package am.hhovhann.travel.ai.core.a2a;

import java.util.Map;

/**
 * Handles structured A2A requests: a message whose part is a {@code DataPart} instead of text. They bypass the LLM,
 * for operations that must run exactly as requested (e.g. booking the offer the user confirmed).
 */
@FunctionalInterface
public interface StructuredRequestHandler {

    /**
     * @param request the DataPart content
     * @return the result, returned to the caller as a DataPart artifact
     * @throws IllegalArgumentException if the request is invalid or rejected; the task then fails with the message
     */
    Map<String, Object> handle(Map<String, Object> request);
}
