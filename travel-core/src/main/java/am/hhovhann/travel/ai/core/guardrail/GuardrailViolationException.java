package am.hhovhann.travel.ai.core.guardrail;

/**
 * A message was rejected by a guardrail before it reached the LLM.
 */
public class GuardrailViolationException extends RuntimeException {

    private final String code;

    public GuardrailViolationException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * @return a stable machine-readable reason, e.g. {@code INPUT_TOO_LONG} or {@code PROMPT_INJECTION}
     */
    public String code() {
        return code;
    }
}
