package am.hhovhann.travel.ai.orchestrator.service;

import am.hhovhann.travel.ai.orchestrator.agent.RemoteAgent;
import am.hhovhann.travel.ai.orchestrator.agent.TravelAgentTools;
import am.hhovhann.travel.ai.orchestrator.model.TravelPlan;
import am.hhovhann.travel.ai.orchestrator.model.TripRequest;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TravelOrchestratorService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final List<RemoteAgent> agents;

    public TravelOrchestratorService(ChatClient orchestratorChatClient, ChatMemory chatMemory, List<RemoteAgent> agents) {
        this.chatClient = orchestratorChatClient;
        this.chatMemory = chatMemory;
        this.agents = agents;
    }

    /**
     * Whether the conversation is still in memory. Memory is in-process, so it is gone after a restart; continuing
     * such a conversation would let the model answer "Book option 1" without knowing what option 1 was.
     */
    public boolean hasConversation(String conversationId) {
        return !chatMemory.get(conversationId).isEmpty();
    }

    public String chat(String conversationId, String message) {
        String answer = chatClient.prompt()
                .system(system -> system.param("today", LocalDate.now().toString()))
                .user(message)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .toolContext(Map.of(TravelAgentTools.CONVERSATION_ID, conversationId))
                .call()
                .content();
        return answer == null ? "" : answer.strip();
    }

    public TravelPlan planTrip(TripRequest trip) {
        String tripId = UUID.randomUUID().toString();
        String request = """
                Plan a trip with flights and a hotel:
                - From: %s
                - To: %s
                - Departure date: %s
                - Return date: %s
                - Travellers: %d
                - Preferences: %s
                """.formatted(trip.from(), trip.to(), trip.departureDate(),
                trip.returnDate() != null ? trip.returnDate() : "one way",
                trip.passengers() != null ? trip.passengers() : 1,
                trip.preferences() != null ? trip.preferences() : "none");
        return new TravelPlan(tripId, chat(tripId, request));
    }

    public Map<String, Object> agentsStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        for (RemoteAgent agent : agents) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("url", agent.baseUrl());
            try {
                AgentCard card = agent.fetchCard();
                entry.put("available", true);
                entry.put("name", card.name());
                entry.put("version", card.version());
                entry.put("protocolVersion", card.protocolVersion());
                entry.put("endpoint", card.url());
                entry.put("skills", card.skills().stream().map(AgentSkill::id).toList());
            } catch (Exception e) {
                entry.put("available", false);
                entry.put("error", e.getMessage());
            }
            status.put(agent.name(), entry);
        }
        return status;
    }
}
