package am.hhovhann.travel.ai.orchestrator.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limits the requests that call the LLM ({@code /chat} and {@code /plan}) per client IP, in fixed one-minute windows.
 * Each request can trigger several paid LLM calls, so this caps the cost one client can cause ("denial of wallet").
 * <p>
 * In memory and per instance, like the rest of the state; behind a proxy, the proxy's address is what counts.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final Set<String> LIMITED_PATHS = Set.of("/api/v1/travel/chat", "/api/v1/travel/plan");
    private static final long WINDOW_MILLIS = 60_000;
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private record Window(long start, int count) {
    }

    private final int requestsPerMinute;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public RateLimitFilter(@Value("${guardrails.requests-per-minute}") int requestsPerMinute) {
        this(requestsPerMinute, Clock.systemUTC());
    }

    RateLimitFilter(int requestsPerMinute, Clock clock) {
        this.requestsPerMinute = requestsPerMinute;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !LIMITED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = clock.millis();
        if (windows.size() > MAX_TRACKED_CLIENTS) {
            windows.values().removeIf(window -> now - window.start() >= WINDOW_MILLIS);
        }
        Window window = windows.merge(request.getRemoteAddr(), new Window(now, 1), (current, fresh) ->
                now - current.start() >= WINDOW_MILLIS ? fresh : new Window(current.start(), current.count() + 1));
        if (window.count() <= requestsPerMinute) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfterSeconds = Math.max(1, (window.start() + WINDOW_MILLIS - now + 999) / 1000);
        LOGGER.warn("Rate limit exceeded by {} ({} requests per minute)", request.getRemoteAddr(), requestsPerMinute);
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"error\":\"Too many requests. Please try again in "
                + retryAfterSeconds + " seconds.\"}");
    }
}
