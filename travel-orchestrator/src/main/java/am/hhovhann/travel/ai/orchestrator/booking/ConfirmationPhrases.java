package am.hhovhann.travel.ai.orchestrator.booking;

import java.util.Locale;
import java.util.Set;

/**
 * Recognizes a chat message that is nothing but an explicit confirmation, e.g. "Yes, I confirm". Only exact phrases
 * count: "yes, but a cheaper hotel" is not a confirmation and goes to the assistant.
 */
public final class ConfirmationPhrases {

    private static final Set<String> PHRASES = Set.of(
            "yes", "y", "yes please", "yes i confirm", "i confirm", "confirm", "confirmed", "confirm booking",
            "confirm the booking", "yes confirm", "yes book it", "book it", "go ahead", "yes go ahead", "please book",
            "please book it", "ok book it", "ok confirm", "yes do it", "do it");

    private ConfirmationPhrases() {
    }

    public static boolean isConfirmation(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N} ]", " ")
                .replaceAll("\\s+", " ")
                .strip();
        return PHRASES.contains(normalized);
    }
}
