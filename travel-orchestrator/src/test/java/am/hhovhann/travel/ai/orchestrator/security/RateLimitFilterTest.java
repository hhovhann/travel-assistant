package am.hhovhann.travel.ai.orchestrator.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private Clock clock = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC);
    private final RateLimitFilter filter = new RateLimitFilter(2, new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return clock.instant();
        }
    });

    @Test
    void limitsChatRequestsPerClientPerMinute() throws Exception {
        assertThat(chat("10.0.0.1").getStatus()).isEqualTo(200);
        assertThat(chat("10.0.0.1").getStatus()).isEqualTo(200);

        MockHttpServletResponse limited = chat("10.0.0.1");
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("60");
        assertThat(limited.getContentAsString()).contains("RATE_LIMITED");

        assertThat(chat("10.0.0.2").getStatus()).isEqualTo(200);
    }

    @Test
    void resetsAfterTheWindow() throws Exception {
        chat("10.0.0.1");
        chat("10.0.0.1");
        assertThat(chat("10.0.0.1").getStatus()).isEqualTo(429);

        clock = Clock.offset(clock, Duration.ofSeconds(61));
        assertThat(chat("10.0.0.1").getStatus()).isEqualTo(200);
    }

    @Test
    void doesNotLimitOtherEndpoints() throws Exception {
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/travel/agents/status");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    private MockHttpServletResponse chat(String clientIp) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/travel/chat");
        request.setRemoteAddr(clientIp);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
