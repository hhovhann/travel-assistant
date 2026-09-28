package am.hhovhann.travel.ai.core.security;

import io.a2a.spec.HTTPAuthSecurityScheme;
import io.a2a.spec.SecurityScheme;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * Service-to-service authentication with a shared bearer token ({@code INTERNAL_API_TOKEN}).
 * <p>
 * Only the orchestrator is meant to be reached by users. The agents and MCP servers accept calls only with
 * {@code Authorization: Bearer <token>}, so nobody can call {@code book_flight} or an agent directly and skip the
 * traveller's confirmation. Agents declare the scheme in their agent card, as the A2A spec describes, and the
 * orchestrator's A2A client adds the header for it.
 */
public final class InternalAuth {

    /** Name of the security scheme in the agent card. */
    public static final String SCHEME = "internalToken";

    public static final String AUTHORIZATION = "Authorization";

    private static final String BEARER = "Bearer ";

    private InternalAuth() {
    }

    public static Map<String, SecurityScheme> securitySchemes() {
        return Map.of(SCHEME, new HTTPAuthSecurityScheme.Builder()
                .scheme("bearer")
                .description("Shared service token (INTERNAL_API_TOKEN)")
                .build());
    }

    public static List<Map<String, List<String>>> security() {
        return List.of(Map.of(SCHEME, List.of()));
    }

    /**
     * @throws IllegalStateException if the token is not configured, so a service never starts unprotected
     */
    public static String requireToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("INTERNAL_API_TOKEN is not set. Set it in .env or the environment "
                    + "(e.g. INTERNAL_API_TOKEN=$(openssl rand -hex 32)); all services must use the same value.");
        }
        return token.strip();
    }

    public static String bearer(String token) {
        return BEARER + token;
    }

    /**
     * Constant-time comparison of an {@code Authorization} header with the expected token.
     */
    public static boolean matches(String authorizationHeader, String token) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER)) {
            return false;
        }
        byte[] presented = authorizationHeader.substring(BEARER.length()).strip().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(presented, token.getBytes(StandardCharsets.UTF_8));
    }
}
