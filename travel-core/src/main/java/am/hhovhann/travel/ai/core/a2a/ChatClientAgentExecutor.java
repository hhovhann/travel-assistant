package am.hhovhann.travel.ai.core.a2a;

import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.server.tasks.TaskUpdater;
import io.a2a.spec.DataPart;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.Task;
import io.a2a.spec.TaskNotCancelableError;
import io.a2a.spec.TaskState;
import io.a2a.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs an A2A task through a Spring AI {@link ChatClient}, or, for structured requests (a {@link DataPart}),
 * through a {@link StructuredRequestHandler} without the LLM.
 * <p>
 * The ChatClient's default system prompt may reference {@code {today}}, which is filled in on every request.
 * <p>
 * The A2A {@code contextId} is used as the chat memory conversation id, so follow-up messages in the same context
 * keep their history. The answer is published as a task artifact; failures mark the task as {@code failed} with the
 * reason, instead of surfacing as a transport error.
 */
public class ChatClientAgentExecutor implements AgentExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatClientAgentExecutor.class);

    private final ChatClient chatClient;
    private final StructuredRequestHandler structuredRequestHandler;

    public ChatClientAgentExecutor(ChatClient chatClient) {
        this(chatClient, null);
    }

    public ChatClientAgentExecutor(ChatClient chatClient, StructuredRequestHandler structuredRequestHandler) {
        this.chatClient = chatClient;
        this.structuredRequestHandler = structuredRequestHandler;
    }

    @Override
    public void execute(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
        TaskUpdater updater = new TaskUpdater(context, eventQueue);
        if (context.getTask() == null) {
            updater.submit();
        }
        updater.startWork();

        Optional<Map<String, Object>> structuredRequest = context.getMessage().getParts().stream()
                .filter(DataPart.class::isInstance)
                .map(part -> ((DataPart) part).getData())
                .findFirst();
        if (structuredRequest.isPresent()) {
            handleStructured(structuredRequest.get(), updater, context);
            return;
        }

        try {
            String answer = chatClient.prompt()
                    .system(system -> system.param("today", LocalDate.now().toString()))
                    .user(context.getUserInput("\n"))
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, context.getContextId()))
                    .call()
                    .content();

            updater.addArtifact(List.of(new TextPart(answer)), null, "answer", null);
            updater.complete();
        } catch (RuntimeException e) {
            LOGGER.error("Task {} failed", context.getTaskId(), e);
            updater.fail(updater.newAgentMessage(List.of(new TextPart("Agent failed: " + e.getMessage())), null));
        }
    }

    private void handleStructured(Map<String, Object> request, TaskUpdater updater, RequestContext context) {
        if (structuredRequestHandler == null) {
            updater.reject(updater.newAgentMessage(List.of(new TextPart("Structured requests are not supported")), null));
            return;
        }
        try {
            Map<String, Object> result = structuredRequestHandler.handle(request);
            updater.addArtifact(List.of(new DataPart(result)), null, "result", null);
            updater.complete();
        } catch (RuntimeException e) {
            LOGGER.warn("Structured task {} failed: {}", context.getTaskId(), e.getMessage());
            updater.fail(updater.newAgentMessage(List.of(new TextPart(e.getMessage())), null));
        }
    }

    @Override
    public void cancel(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
        Task task = context.getTask();
        TaskState state = task.getStatus().state();
        if (state == TaskState.CANCELED || state == TaskState.COMPLETED || state == TaskState.FAILED) {
            throw new TaskNotCancelableError();
        }
        new TaskUpdater(context, eventQueue).cancel();
    }
}
