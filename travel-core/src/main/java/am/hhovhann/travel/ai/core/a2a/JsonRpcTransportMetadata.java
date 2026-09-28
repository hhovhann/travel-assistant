package am.hhovhann.travel.ai.core.a2a;

import io.a2a.server.TransportMetadata;
import io.a2a.spec.TransportProtocol;

/**
 * Registers {@link A2AJsonRpcController} as the JSON-RPC transport (via META-INF/services), so the SDK's agent card
 * validation knows that JSON-RPC is served. The SDK's own transports register the same way.
 */
public class JsonRpcTransportMetadata implements TransportMetadata {

    @Override
    public String getTransportProtocol() {
        return TransportProtocol.JSONRPC.asString();
    }
}
