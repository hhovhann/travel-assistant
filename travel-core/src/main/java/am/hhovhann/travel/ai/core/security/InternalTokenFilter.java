package am.hhovhann.travel.ai.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Rejects requests without the internal bearer token with {@code 401}. Paths in {@code publicPaths} (e.g. the agent
 * card, which callers need to discover the agent and its auth scheme) stay open.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(InternalTokenFilter.class);

    private final String token;
    private final Set<String> publicPaths;

    public InternalTokenFilter(String token, Set<String> publicPaths) {
        this.token = InternalAuth.requireToken(token);
        this.publicPaths = publicPaths;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return publicPaths.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (InternalAuth.matches(request.getHeader(InternalAuth.AUTHORIZATION), token)) {
            chain.doFilter(request, response);
            return;
        }
        LOGGER.warn("Rejected unauthenticated {} {} from {}", request.getMethod(), request.getRequestURI(),
                request.getRemoteAddr());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"Missing or invalid internal token\"}");
    }
}
