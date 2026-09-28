package am.hhovhann.travel.ai.mcp.flight.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Accepts MCP calls only with {@code Authorization: Bearer <INTERNAL_API_TOKEN>}, so the booking tools cannot be
 * called directly, bypassing the traveller's confirmation in the orchestrator.
 * <p>
 * Kept in this module (like {@code InternalTokenFilter} in travel-core) so the MCP server stays a standalone MCP
 * server without the A2A dependencies. MCP clients such as MCP Inspector can send the same header.
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(InternalTokenFilter.class);
    private static final String BEARER = "Bearer ";

    private final byte[] token;

    public InternalTokenFilter(@Value("${internal.api-token:}") String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("INTERNAL_API_TOKEN is not set. Set it in .env or the environment "
                    + "(e.g. INTERNAL_API_TOKEN=$(openssl rand -hex 32)); all services must use the same value.");
        }
        this.token = token.strip().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)
                && MessageDigest.isEqual(header.substring(BEARER.length()).strip().getBytes(StandardCharsets.UTF_8), token)) {
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
