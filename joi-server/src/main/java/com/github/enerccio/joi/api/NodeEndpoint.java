package com.github.enerccio.joi.api;

import com.github.enerccio.joi.domain.node.NodeSession;
import com.github.enerccio.joi.domain.node.NodeTransport;
import com.github.enerccio.joi.domain.service.NodeService;
import com.github.enerccio.joi.protocol.JoiProtocol;
import jakarta.servlet.ServletContext;
import jakarta.websocket.CloseReason;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The node WebSocket ({@link JoiProtocol#NODE}): a Jakarta WebSocket endpoint that hands the frames to the
 * {@link NodeService}. The {@code Authorization} header of the upgrade is kept for the hello; registered on the
 * container's {@link ServerContainer} once the Spring context is up ({@link #register}).
 */
public class NodeEndpoint extends Endpoint {

    private static final Logger log = LoggerFactory.getLogger(NodeEndpoint.class);

    private static final String SERVICE = NodeService.class.getName();
    private static final String AUTHORIZATION = "joi.authorization";
    private static final String NODE_SESSION = "joi.node-session";
    /** The largest frame a node may send (a manifest with many tools, a long say). */
    static final int MAX_FRAME = 1024 * 1024;
    /** The longest reason a close frame takes, in bytes. */
    private static final int MAX_REASON = 123;

    /** Registers the endpoint; false when the container has no WebSocket support. */
    public static boolean register(ServletContext context, NodeService service) {
        Object container = context.getAttribute(ServerContainer.class.getName());
        if (!(container instanceof ServerContainer server)) {
            log.warn("The servlet container has no WebSocket: nodes can't connect on {}", JoiProtocol.NODE);
            return false;
        }
        ServerEndpointConfig config = ServerEndpointConfig.Builder.create(NodeEndpoint.class, JoiProtocol.NODE)
                .configurator(new ServerEndpointConfig.Configurator() {
                    @Override
                    public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
                        List<String> values = request.getHeaders().entrySet().stream()
                                .filter(e -> e.getKey().equalsIgnoreCase("Authorization"))
                                .flatMap(e -> e.getValue().stream()).toList();
                        sec.getUserProperties().put(AUTHORIZATION, values.isEmpty() ? null : values.getFirst());
                    }
                })
                .build();
        config.getUserProperties().put(SERVICE, service);
        try {
            server.addEndpoint(config);
            return true;
        } catch (DeploymentException e) {
            log.error("The node WebSocket could not be registered", e);
            return false;
        }
    }

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        Map<String, Object> properties = session.getUserProperties();
        NodeService service = (NodeService) properties.get(SERVICE);
        session.setMaxTextMessageBufferSize(MAX_FRAME);
        // the protocol has its own heartbeat
        session.setMaxIdleTimeout(0);
        NodeSession node = service.open(new Transport(session), (String) properties.get(AUTHORIZATION));
        properties.put(NODE_SESSION, node);
        session.addMessageHandler(String.class, (MessageHandler.Whole<String>) text -> service.receive(node, text));
    }

    @Override
    public void onClose(Session session, CloseReason closeReason) {
        closed(session);
    }

    @Override
    public void onError(Session session, Throwable error) {
        log.debug("A node's connection failed: {}", error.getMessage());
        closed(session);
    }

    private static void closed(Session session) {
        Object service = session.getUserProperties().get(SERVICE);
        Object node = session.getUserProperties().get(NODE_SESSION);
        if (service instanceof NodeService nodes && node instanceof NodeSession nodeSession) {
            nodes.closed(nodeSession);
        }
    }

    /** One frame at a time: the basic remote can't send two at once. */
    private record Transport(Session session) implements NodeTransport {

        @Override
        public void send(String text) {
            synchronized (session) {
                if (!session.isOpen()) {
                    return;
                }
                try {
                    session.getBasicRemote().sendText(text);
                } catch (IOException | IllegalStateException e) {
                    log.debug("A frame to a node was lost: {}", e.getMessage());
                }
            }
        }

        @Override
        public void close(int code, String reason) {
            byte[] bytes = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
            String shown = bytes.length <= MAX_REASON ? reason : new String(bytes, 0, MAX_REASON - 3, StandardCharsets.UTF_8) + "...";
            try {
                synchronized (session) {
                    if (session.isOpen()) {
                        session.close(new CloseReason(CloseReason.CloseCodes.getCloseCode(code), shown));
                    }
                }
            } catch (IOException e) {
                log.debug("A node's connection could not be closed: {}", e.getMessage());
            }
        }
    }
}
