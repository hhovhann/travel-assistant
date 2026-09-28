package am.hhovhann.travel.ai.core.guardrail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.Ordered;

/**
 * Input guardrail: rejects an oversized message or a likely prompt injection before the model is called.
 * <p>
 * It runs first in the advisor chain, ahead of the chat memory advisor, so a rejected message is never stored in the
 * conversation history and cannot influence later turns. A rejection throws {@link GuardrailViolationException}
 * instead of answering, so callers can tell a blocked request from a model answer.
 */
public class InputGuardrailAdvisor implements CallAdvisor {

    private static final Logger LOGGER = LoggerFactory.getLogger(InputGuardrailAdvisor.class);

    private final int maxChars;

    public InputGuardrailAdvisor(int maxChars) {
        this.maxChars = maxChars;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        UserMessage userMessage = request.prompt().getUserMessage();
        check(userMessage == null ? null : userMessage.getText());
        return chain.nextCall(request);
    }

    void check(String text) {
        if (text == null) {
            return;
        }
        if (text.length() > maxChars) {
            LOGGER.warn("Rejected message of {} characters (limit {})", text.length(), maxChars);
            throw new GuardrailViolationException("INPUT_TOO_LONG",
                    "The message is too long (" + text.length() + " characters, the limit is " + maxChars + ").");
        }
        PromptInjectionDetector.detectInUserInput(text).ifPresent(rule -> {
            LOGGER.warn("Rejected message matching prompt-injection rule '{}'", rule);
            throw new GuardrailViolationException("PROMPT_INJECTION",
                    "The message was blocked because it looks like an attempt to change the assistant's instructions. "
                            + "Please rephrase your travel request.");
        });
    }

    @Override
    public String getName() {
        return "InputGuardrailAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
