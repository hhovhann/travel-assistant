package am.hhovhann.travel.ai.orchestrator.model;

/**
 * @param conversationId optional; reuse the one from a previous response to continue that conversation
 */
public record ChatRequest(String message, String conversationId) {
}
