package am.hhovhann.travel.ai.core.guardrail;

import java.text.Normalizer;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristic detection of prompt-injection phrases.
 * <p>
 * This is one layer, not the defense: a determined attacker can rephrase around any pattern list. What actually keeps
 * bookings safe is that no LLM can book (see the README). The detector cheaply rejects the common attacks, keeps them
 * out of chat memory and logs them.
 * <p>
 * Text is normalized first (Unicode NFKC, zero-width characters removed, whitespace collapsed) so look-alike
 * characters and invisible separators do not slip past the patterns. Some rules only apply to untrusted content (tool
 * and agent results): a traveller asking to "book without asking me again" is not an attack, but a hotel description
 * saying so is.
 */
public final class PromptInjectionDetector {

    public static final String REDACTION = "[removed: suspected prompt injection]";

    private static final Pattern INVISIBLE = Pattern.compile("[\\u00AD\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\uFEFF]");

    private record Rule(String name, Pattern pattern, boolean userInput) {
    }

    private static final List<Rule> RULES = List.of(
            rule("override-instructions", true, """
                    \\b(ignore|disregard|forget|override|bypass)\\b.{0,30}\\b(previous|prior|above|earlier|preceding|all|your|system|original|initial)\\b\
                    .{0,20}\\b(instructions?|rules|prompts?|directives|guidelines)\\b"""),
            rule("reveal-prompt", true, """
                    \\b(reveal|show|print|repeat|output|display|dump|leak)\\b.{0,30}\\b(system|hidden|initial|original|developer)\\s+(prompt|instructions?|message)s?\\b\
                    |\\b(reveal|repeat|print|output|dump|leak)\\b.{0,15}\\byour\\s+(instructions|rules|prompt)\\b"""),
            rule("role-change", true, """
                    \\byou are (now|no longer) (a|an|the|my|in)\\b|\\b(pretend|roleplay|role-play)\\b.{0,20}\\b(you are|to be)\\b\
                    |\\bact as (an? )?(unrestricted|unfiltered|jailbroken|admin|administrator|developer|root|system)\\b\
                    |\\b(developer|god|jailbreak|debug|dan) mode\\b|\\bjailbreak"""),
            rule("fake-role-marker", true, """
                    <\\|?\\s*(im_start|im_end|system|endoftext)\\s*\\|?>|\\[/?(INST|SYS)\\]|<<\\s*/?SYS\\s*>>\
                    |(^|\\n)\\s*#{0,3}\\s*(system|assistant|developer)\\s*:"""),
            rule("fake-instructions", true, """
                    \\b(new|updated|additional|important|urgent) (system )?instructions?\\s*:|\\bsystem (override|prompt)\\s*:"""),
            rule("skip-confirmation", false, """
                    \\b(book|confirm|pay|purchase|reserve|cancel)\\b.{0,40}\\bwithout\\b.{0,20}\\b(asking|confirmation|confirming|approval|consent|the (user|traveller|traveler|customer))\\b"""),
            rule("hide-from-user", false, """
                    \\b(do not|don't|never)\\s+(tell|inform|mention|show|reveal)\\b.{0,20}\\b(user|traveller|traveler|customer|human)\\b"""),
            rule("markdown-image", false, """
                    !\\[[^\\]]*\\]\\(\\s*https?://""")
    );

    private PromptInjectionDetector() {
    }

    /**
     * @return the name of the first rule that matches a traveller's message
     */
    public static Optional<String> detectInUserInput(String text) {
        String normalized = normalize(text);
        return RULES.stream()
                .filter(Rule::userInput)
                .filter(rule -> rule.pattern().matcher(normalized).find())
                .map(Rule::name)
                .findFirst();
    }

    /**
     * Normalizes untrusted content and replaces every match of every rule with {@link #REDACTION}.
     */
    public static Redacted redact(String text) {
        String result = normalize(text);
        int count = 0;
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(result);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                count++;
                // Keep a line break the pattern consumed, so the surrounding text keeps its layout
                String prefix = matcher.group().startsWith("\n") ? "\n" : "";
                matcher.appendReplacement(out, Matcher.quoteReplacement(prefix + REDACTION));
            }
            matcher.appendTail(out);
            result = out.toString();
        }
        return new Redacted(result, count);
    }

    public record Redacted(String text, int count) {
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        normalized = INVISIBLE.matcher(normalized).replaceAll("");
        return normalized.replaceAll("[\\t\\x0B\\f\\r ]+", " ");
    }

    private static Rule rule(String name, boolean userInput, String regex) {
        return new Rule(name, Pattern.compile(regex.strip(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), userInput);
    }
}
