package am.hhovhann.travel.ai.orchestrator.booking;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmationPhrasesTest {

    @ParameterizedTest
    @ValueSource(strings = {"Yes, I confirm", "yes", "Confirm!", "  Book it. ", "YES PLEASE", "Go ahead 👍"})
    void explicitConfirmations(String message) {
        assertThat(ConfirmationPhrases.isConfirmation(message)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Yes, but a cheaper hotel", "Book option 1", "no", "confirm the price first?", "yesterday", ""})
    void anythingElseGoesToTheAssistant(String message) {
        assertThat(ConfirmationPhrases.isConfirmation(message)).isFalse();
    }
}
