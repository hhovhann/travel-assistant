package am.hhovhann.travel.ai.core.a2a;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.a2a.server.ServerCallContext;
import io.a2a.server.auth.UnauthenticatedUser;
import io.a2a.spec.AgentCard;
import io.a2a.spec.CancelTaskRequest;
import io.a2a.spec.DeleteTaskPushNotificationConfigRequest;
import io.a2a.spec.GetAuthenticatedExtendedCardRequest;
import io.a2a.spec.GetTaskPushNotificationConfigRequest;
import io.a2a.spec.GetTaskRequest;
import io.a2a.spec.IdJsonMappingException;
import io.a2a.spec.InternalError;
import io.a2a.spec.InvalidParamsError;
import io.a2a.spec.InvalidParamsJsonMappingException;
import io.a2a.spec.InvalidRequestError;
import io.a2a.spec.JSONParseError;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.JSONRPCErrorResponse;
import io.a2a.spec.JSONRPCResponse;
import io.a2a.spec.ListTaskPushNotificationConfigRequest;
import io.a2a.spec.MethodNotFoundError;
import io.a2a.spec.MethodNotFoundJsonMappingException;
import io.a2a.spec.NonStreamingJSONRPCRequest;
import io.a2a.spec.SendMessageRequest;
import io.a2a.spec.SendStreamingMessageRequest;
import io.a2a.spec.SetTaskPushNotificationConfigRequest;
import io.a2a.spec.TaskResubscriptionRequest;
import io.a2a.spec.UnsupportedOperationError;
import io.a2a.transport.jsonrpc.handler.JSONRPCHandler;
import io.a2a.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * A2A JSON-RPC transport endpoint.
 * <p>
 * Parsing and serialization go through the SDK's own Jackson 2 {@link Utils#OBJECT_MAPPER}, because the A2A spec
 * types rely on its custom (de)serializers while Spring Boot 4 uses Jackson 3 for MVC. Protocol logic is delegated to
 * the SDK's {@link JSONRPCHandler}. Per JSON-RPC over HTTP, protocol errors are returned with HTTP 200.
 */
@RestController
public class A2AJsonRpcController {

    private static final Logger LOGGER = LoggerFactory.getLogger(A2AJsonRpcController.class);

    private final AgentCard agentCard;
    private final JSONRPCHandler jsonRpcHandler;

    public A2AJsonRpcController(AgentCard agentCard, JSONRPCHandler jsonRpcHandler) {
        this.agentCard = agentCard;
        this.jsonRpcHandler = jsonRpcHandler;
    }

    @GetMapping(path = "/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public String agentCard() throws JsonProcessingException {
        return Utils.OBJECT_MAPPER.writeValueAsString(agentCard);
    }

    @PostMapping(path = "/", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public String handle(@RequestBody String body) throws JsonProcessingException {
        return Utils.OBJECT_MAPPER.writeValueAsString(dispatch(body));
    }

    private JSONRPCResponse<?> dispatch(String body) {
        JsonNode root;
        try {
            root = Utils.OBJECT_MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            return new JSONRPCErrorResponse(null, new JSONParseError(e.getOriginalMessage()));
        }

        Object id = root.hasNonNull("id") ? Utils.OBJECT_MAPPER.convertValue(root.get("id"), Object.class) : null;
        String method = root.path("method").asText();
        if (SendStreamingMessageRequest.METHOD.equals(method) || TaskResubscriptionRequest.METHOD.equals(method)) {
            // The agent card advertises streaming=false, so clients should use message/send
            return new JSONRPCErrorResponse(id, new UnsupportedOperationError());
        }

        NonStreamingJSONRPCRequest<?> request;
        try {
            request = Utils.OBJECT_MAPPER.treeToValue(root, NonStreamingJSONRPCRequest.class);
        } catch (MethodNotFoundJsonMappingException e) {
            return new JSONRPCErrorResponse(e.getId(), new MethodNotFoundError());
        } catch (InvalidParamsJsonMappingException e) {
            return new JSONRPCErrorResponse(e.getId(), new InvalidParamsError(e.getOriginalMessage()));
        } catch (IdJsonMappingException e) {
            return new JSONRPCErrorResponse(e.getId(), new InvalidRequestError(e.getOriginalMessage()));
        } catch (JsonProcessingException e) {
            return new JSONRPCErrorResponse(id, new InvalidRequestError(e.getOriginalMessage()));
        }

        ServerCallContext context = new ServerCallContext(UnauthenticatedUser.INSTANCE, Map.of(), Set.of());
        try {
            return switch (request) {
                case SendMessageRequest req -> jsonRpcHandler.onMessageSend(req, context);
                case GetTaskRequest req -> jsonRpcHandler.onGetTask(req, context);
                case CancelTaskRequest req -> jsonRpcHandler.onCancelTask(req, context);
                case SetTaskPushNotificationConfigRequest req -> jsonRpcHandler.setPushNotificationConfig(req, context);
                case GetTaskPushNotificationConfigRequest req -> jsonRpcHandler.getPushNotificationConfig(req, context);
                case ListTaskPushNotificationConfigRequest req -> jsonRpcHandler.listPushNotificationConfig(req, context);
                case DeleteTaskPushNotificationConfigRequest req -> jsonRpcHandler.deletePushNotificationConfig(req, context);
                case GetAuthenticatedExtendedCardRequest req -> jsonRpcHandler.onGetAuthenticatedExtendedCardRequest(req, context);
                default -> new JSONRPCErrorResponse(request.getId(), new UnsupportedOperationError());
            };
        } catch (JSONRPCError e) {
            return new JSONRPCErrorResponse(request.getId(), e);
        } catch (RuntimeException e) {
            LOGGER.error("A2A request {} failed", method, e);
            return new JSONRPCErrorResponse(request.getId(), new InternalError(e.getMessage()));
        }
    }
}
