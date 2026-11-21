package com.github.enerccio.joi.domain.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.node.NodeSession;
import com.github.enerccio.joi.domain.service.NodeService;
import com.github.enerccio.joi.domain.workspace.TaskWorkspace;
import com.github.enerccio.joi.domain.workspace.TaskWorkspace.Entry;
import com.github.enerccio.joi.domain.workspace.TaskWorkspace.RejectedException;
import com.github.enerccio.marginalia.domain.service.function.FunctionContinuation;
import com.github.enerccio.marginalia.domain.service.function.FunctionDefinition;
import com.github.enerccio.marginalia.domain.service.function.FunctionSet;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Her way to what her tasks wrote in her temporary workspace ({@link TaskWorkspace}): {@code list_workspace_files} says what is
 * there, {@code offer_file_to_clipboard} hands a file to the user's clipboard on one of her nodes. The text does not pass through
 * her: the server reads the file and calls the node's {@code offer_to_clipboard} tool with it, where the user decides (the
 * node's harness: the tool is only there when the user allowed it on that machine, and each offer is asked about). She is
 * offered the functions only when there is something to offer them for: a file, and for the clipboard a node that has the tool.
 * Both only touch her own files, so they need no permission of the chain ({@code ServerTool}).
 */
public class WorkspaceTools implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceTools.class);

    public static final String LIST = "list_workspace_files";
    public static final String OFFER = "offer_file_to_clipboard";
    /** The tool of a node that puts text on the user's clipboard, when the user agrees ({@code joi-node}: {@code ClipboardOffers}). */
    public static final String NODE_TOOL = "offer_to_clipboard";

    @Autowired
    private ChatService chatService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private TaskWorkspace workspace;

    private final ObjectMapper jackson = new ObjectMapper();
    private Registration registration;

    @Override
    public void afterPropertiesSet() {
        registration = chatService.addEventListener(TurnEvents.COLLECT_TOOLS, (event, chain) -> {
            try {
                Joi joi = event.getJoi();
                List<Entry> files = workspace.list(joi);
                if (!files.isEmpty()) {
                    List<FunctionDefinition> offered = new ArrayList<>();
                    offered.add(list(joi));
                    if (!nodeService.sessionsWithTool(joi, NODE_TOOL).isEmpty()) {
                        offered.add(offer(joi));
                    }
                    event.setFunctions(event.getFunctions().and(FunctionSet.bind(offered, this)));
                }
            } catch (Exception e) {
                // no function this turn: she can still talk
                log.warn("The functions of the workspace could not be offered: {}", e.getMessage(), e);
            }
            chain.next();
        });
    }

    @Override
    public void destroy() {
        if (registration != null) {
            registration.unregister();
        }
    }

    private FunctionDefinition list(Joi joi) {
        ObjectNode schema = jackson.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return FunctionDefinition.dynamic(LIST, "Lists the files your tasks wrote in your temporary workspace (a day at most): name and "
                + "size. A task that was asked for a file, a script or a text tells you what it wrote.", schema, (continuation, arguments) -> {
            try {
                continuation.complete(describe(workspace.list(joi)));
            } catch (Exception e) {
                continuation.fail("The workspace could not be read: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            }
        });
    }

    private FunctionDefinition offer(Joi joi) {
        ObjectNode schema = jackson.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("file").put("type", "string").put("description", "The name of the file in your workspace.");
        properties.putObject("node").put("type", "string").put("description",
                "The name of the node whose clipboard it is for; the one you are talking through when left out.");
        schema.putArray("required").add("file");
        return FunctionDefinition.dynamic(OFFER, "Puts a file of your workspace on the user's clipboard on their machine (on some machines they see the "
                + "text first and decide whether it is copied), so say what you are offering and why. Use it for what they are to paste "
                + "somewhere (a script, a command, a text). It does not show them the text in the chat. For a short text of your own, use "
                + "the clipboard tool of the node directly.", schema,
                (continuation, arguments) -> offer(joi, text(arguments, "file"), text(arguments, "node"), continuation));
    }

    private void offer(Joi joi, String file, String node, FunctionContinuation<Object> continuation) {
        if (file == null) {
            continuation.fail("Give the name of the file");
            return;
        }
        try {
            List<NodeSession> sessions = nodeService.sessionsWithTool(joi, NODE_TOOL);
            NodeSession session = node == null ? sessions.stream().findFirst().orElse(null)
                    : sessions.stream().filter(s -> node.equalsIgnoreCase(s.name())).findFirst().orElse(null);
            if (session == null) {
                continuation.fail(node == null ? "No node of yours can take a clipboard offer now: the user has not allowed it on any."
                        : "The node " + node + " cannot take a clipboard offer: " + (sessions.isEmpty() ? "none can now"
                        : "these can: " + sessions.stream().map(NodeSession::name).collect(Collectors.joining(", "))));
                return;
            }
            String text;
            try {
                text = workspace.read(joi, file);
            } catch (RejectedException e) {
                continuation.fail(e.getMessage() + ". Files: " + describe(workspace.list(joi)));
                return;
            }
            JsonObject arguments = new JsonObject();
            arguments.addProperty("title", file);
            arguments.addProperty("text", text);
            nodeService.callTool(session, NODE_TOOL, arguments, continuation);
        } catch (Exception e) {
            log.warn("The offer of {} to the clipboard failed: {}", file, e.getMessage(), e);
            continuation.fail("It failed: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    private static String describe(List<Entry> files) {
        return files.isEmpty() ? "none" : files.stream().map(file -> file.name() + " (" + file.size() + " bytes)")
                .collect(Collectors.joining(", "));
    }

    private static String text(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        return value instanceof String string && !string.isBlank() ? string.strip() : null;
    }
}
