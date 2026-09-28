package am.hhovhann.travel.ai.flight;

import am.hhovhann.travel.ai.core.a2a.A2AServerConfiguration;
import am.hhovhann.travel.ai.core.security.InternalAuthConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({A2AServerConfiguration.class, InternalAuthConfiguration.class})
public class FlightAgentApplication {
    static void main(String[] args) {
        SpringApplication.run(FlightAgentApplication.class, args);
    }
}
