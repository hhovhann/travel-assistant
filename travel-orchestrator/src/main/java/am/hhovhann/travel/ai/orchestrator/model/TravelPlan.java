package am.hhovhann.travel.ai.orchestrator.model;

/**
 * @param tripId the conversation id; send it as {@code conversationId} to /chat to refine or book this plan
 */
public record TravelPlan(String tripId, String plan) {
}
