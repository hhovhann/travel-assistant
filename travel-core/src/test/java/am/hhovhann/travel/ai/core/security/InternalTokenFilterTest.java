package am.hhovhann.travel.ai.core.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalTokenFilterTest {

    private final InternalTokenFilter filter = new InternalTokenFilter("secret-token", Set.of("/.well-known/agent-card.json"));

    @Test
    void acceptsTheBearerToken() throws Exception {
        MockHttpServletResponse response = run(post("Bearer secret-token"));

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsMissingOrWrongTokens() throws Exception {
        assertThat(run(post(null)).getStatus()).isEqualTo(401);
        assertThat(run(post("Bearer wrong")).getStatus()).isEqualTo(401);
        assertThat(run(post("secret-token")).getStatus()).isEqualTo(401);
    }

    @Test
    void agentCardStaysPublic() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/.well-known/agent-card.json");

        assertThat(run(request).getStatus()).isEqualTo(200);
    }

    @Test
    void refusesToStartWithoutAToken() {
        assertThatThrownBy(() -> new InternalTokenFilter(" ", Set.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_API_TOKEN");
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static MockHttpServletRequest post(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }
}
