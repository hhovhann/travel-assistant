package am.hhovhann.travel.ai.orchestrator.agent;

import io.a2a.A2A;
import io.a2a.client.Client;
import io.a2a.client.ClientEvent;
import io.a2a.client.MessageEvent;
import io.a2a.client.TaskEvent;
import io.a2a.client.TaskUpdateEvent;
import io.a2a.client.config.ClientConfig;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfig;
import io.a2a.spec.A2AClientException;
import io.a2a.spec.AgentCard;
import io.a2a.spec.Artifact;
import io.a2a.spec.DataPart;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TextPart;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * A2A client for one remote agent. The agent card is resolved lazily, so the orchestrator can start before the agents
 * and recovers once they come up.
 */
public class RemoteAgent {

    // Waits a bit longer than the agent's own blocking timeout, so the agent's answer (or its timeout) arrives first
    private static final Duration GRACE = Duration.ofSeconds(30);

    private final String name;
    private final String baseUrl;
    private final Duration timeout;
    private volatile Client client;

    public RemoteAgent(String name, String baseUrl, Duration timeout) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.timeout = timeout;
    }

    public String name() {
        return name;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /**
     * Fetches the agent card from {@code <baseUrl>/.well-known/agent-card.json}.
     */
    public AgentCard fetchCard() throws A2AClientException {
        try {
            return A2A.getAgentCard(baseUrl);
        } catch (Exception e) {
            throw new A2AClientException("Cannot fetch agent card of " + name + " at " + baseUrl, e);
        }
    }

    /**
     * Sends a natural-language request, answered by the agent's LLM.
     *
     * @param contextId the A2A context that groups the messages of one conversation, so the agent keeps its history
     * @return the answer, or a description of why the task did not complete
     */
    public String send(String text, String contextId) {
        return switch (exchange(A2A.createUserTextMessage(text, contextId, null))) {
            case Message message -> text(message.getParts());
            case Task task -> describe(task);
            default -> "";
        };
    }

    /**
     * Sends a structured request that the agent executes exactly, without its LLM.
     *
     * @return the result data
     * @throws IllegalStateException with the agent's reason if the request was rejected or failed
     */
    public Map<String, Object> invoke(String tool, Map<String, Object> arguments) {
        Message request = new Message.Builder()
                .role(Message.Role.USER)
                .messageId(UUID.randomUUID().toString())
                .parts(List.of(new DataPart(Map.of("tool", tool, "arguments", arguments))))
                .build();
        Object result = exchange(request);
        if (result instanceof Task task && task.getStatus().state() == TaskState.COMPLETED && task.getArtifacts() != null) {
            return task.getArtifacts().stream()
                    .flatMap(artifact -> artifact.parts().stream())
                    .filter(DataPart.class::isInstance)
                    .map(part -> ((DataPart) part).getData())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(name + " returned no data for " + tool));
        }
        if (result instanceof Task task) {
            throw new IllegalStateException(statusText(task).isBlank()
                    ? name + " could not run " + tool + " (task " + task.getStatus().state().asString() + ")"
                    : statusText(task));
        }
        throw new IllegalStateException(name + " answered " + tool + " with an unexpected message");
    }

    /**
     * @return the final {@link Task} or {@link Message} the agent answered with
     */
    private Object exchange(Message request) {
        CompletableFuture<Object> result = new CompletableFuture<>();
        BiConsumer<ClientEvent, AgentCard> consumer = (event, card) -> {
            switch (event) {
                case MessageEvent messageEvent -> result.complete(messageEvent.getMessage());
                case TaskEvent taskEvent -> result.complete(taskEvent.getTask());
                case TaskUpdateEvent updateEvent when updateEvent.getTask().getStatus().state().isFinal() ->
                        result.complete(updateEvent.getTask());
                default -> { }
            }
        };

        try {
            client().sendMessage(request, List.of(consumer), result::completeExceptionally, null);
            return result.get(timeout.plus(GRACE).toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException(name + " did not answer within " + timeout.toSeconds() + "s", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(name + " call failed: " + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(name + " call interrupted", e);
        } catch (A2AClientException e) {
            client = null; // re-resolve the card on the next call, e.g. after the agent restarted
            throw new IllegalStateException(name + " is unavailable: " + e.getMessage(), e);
        }
    }

    private Client client() throws A2AClientException {
        Client current = client;
        if (current == null) {
            synchronized (this) {
                if (client == null) {
                    client = Client.builder(fetchCard())
                            .clientConfig(new ClientConfig.Builder()
                                    .setStreaming(false)
                                    .setAcceptedOutputModes(List.of("text/plain", "application/json"))
                                    .build())
                            .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig())
                            .build();
                }
                current = client;
            }
        }
        return current;
    }

    private String describe(Task task) {
        TaskState state = task.getStatus().state();
        return switch (state) {
            case COMPLETED -> task.getArtifacts() == null ? "" : task.getArtifacts().stream()
                    .map(Artifact::parts)
                    .map(this::text)
                    .collect(Collectors.joining("\n"));
            case INPUT_REQUIRED -> statusText(task);
            case SUBMITTED, WORKING -> "ERROR: " + name + " did not finish within " + timeout.toSeconds()
                    + "s (task " + task.getId() + " is still '" + state.asString() + "'). The search timed out;"
                    + " this does not mean there are no results.";
            default -> name + " task " + task.getId() + " ended in state '" + state.asString() + "': " + statusText(task);
        };
    }

    private String statusText(Task task) {
        Message message = task.getStatus().message();
        return message == null ? "" : text(message.getParts());
    }

    private String text(List<Part<?>> parts) {
        return parts.stream()
                .filter(TextPart.class::isInstance)
                .map(part -> ((TextPart) part).getText())
                .collect(Collectors.joining());
    }
}
