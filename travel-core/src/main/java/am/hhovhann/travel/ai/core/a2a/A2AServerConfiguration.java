package am.hhovhann.travel.ai.core.a2a;

import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.config.A2AConfigProvider;
import io.a2a.server.config.DefaultValuesConfigProvider;
import io.a2a.server.events.InMemoryQueueManager;
import io.a2a.server.requesthandlers.DefaultRequestHandler;
import io.a2a.server.requesthandlers.RequestHandler;
import io.a2a.server.tasks.InMemoryPushNotificationConfigStore;
import io.a2a.server.tasks.InMemoryTaskStore;
import io.a2a.spec.AgentCard;
import io.a2a.transport.jsonrpc.handler.JSONRPCHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Wires the A2A Java SDK server components into Spring.
 * <p>
 * An agent application imports this configuration and provides an {@link AgentCard} and an {@link AgentExecutor} bean.
 * The agent is then served over JSON-RPC at {@code POST /} with its card at {@code GET /.well-known/agent-card.json}.
 */
@Configuration(proxyBeanMethods = false)
public class A2AServerConfiguration {

    // Kept out of the context on purpose: an Executor bean would replace Spring Boot's own task executor
    private final Executor a2aExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Bean
    public InMemoryTaskStore a2aTaskStore() {
        return new InMemoryTaskStore();
    }

    @Bean
    public DefaultValuesConfigProvider a2aDefaultValuesConfigProvider() {
        return new DefaultValuesConfigProvider();
    }

    /**
     * Lets the SDK settings (e.g. {@code a2a.blocking.agent.timeout.seconds}) be overridden from application.yml.
     */
    @Bean
    @Primary
    public A2AConfigProvider a2aConfigProvider(Environment environment, DefaultValuesConfigProvider defaults) {
        return new A2AConfigProvider() {
            @Override
            public String getValue(String name) {
                return environment.containsProperty(name) ? environment.getProperty(name) : defaults.getValue(name);
            }

            @Override
            public Optional<String> getOptionalValue(String name) {
                return environment.containsProperty(name)
                        ? Optional.ofNullable(environment.getProperty(name))
                        : defaults.getOptionalValue(name);
            }
        };
    }

    /**
     * Created with the constructor rather than {@code DefaultRequestHandler.create(...)}: the factory method hardcodes
     * a 5 second blocking timeout, while the constructor path lets Spring inject the {@link A2AConfigProvider} and run
     * the handler's {@code @PostConstruct} to read the configured timeouts.
     */
    @Bean
    public RequestHandler a2aRequestHandler(AgentExecutor agentExecutor, InMemoryTaskStore taskStore) {
        return new DefaultRequestHandler(agentExecutor, taskStore, new InMemoryQueueManager(taskStore),
                new InMemoryPushNotificationConfigStore(), task -> { }, a2aExecutor);
    }

    @Bean
    public A2AJsonRpcController a2aJsonRpcController(AgentCard agentCard, RequestHandler requestHandler) {
        return new A2AJsonRpcController(agentCard, new JSONRPCHandler(agentCard, requestHandler, a2aExecutor));
    }
}
