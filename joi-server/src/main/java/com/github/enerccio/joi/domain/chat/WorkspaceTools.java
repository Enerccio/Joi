package com.github.enerccio.joi.domain.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.chat.turn.TurnProperties;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.node.NodeSession;
import com.github.enerccio.joi.domain.service.ChatImageService;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.service.JoiWorkspaceService;
import com.github.enerccio.joi.domain.service.NodeService;
import com.github.enerccio.marginalia.domain.service.agent.ShownImages;
import com.github.enerccio.marginalia.domain.service.agent.VirtualFileException;
import com.github.enerccio.marginalia.domain.service.agent.VirtualFileSystem;
import com.github.enerccio.marginalia.domain.service.function.FunctionContinuation;
import com.github.enerccio.marginalia.domain.service.function.FunctionDefinition;
import com.github.enerccio.marginalia.domain.service.function.FunctionSet;
import com.github.enerccio.marginalia.utils.AppClock;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Her files: her own workspace ({@link JoiWorkspaceService}), a folder of files she keeps between her turns and that her tasks write
 * into too. The platform's file functions (list, read, write, append, replace, grep, move, copy, unzip, merge, delete; paths are
 * virtual, {@code /notes/plot.md}) work on it, and {@code offer_file_to_clipboard} hands a file to the user's clipboard on one of her
 * nodes: the text does not pass through her, the server reads the file and calls the node's {@code offer_to_clipboard} tool with it,
 * where the user decides (the node's harness: the tool is only there when the user allowed it on that machine, and each offer is
 * asked about). All of it is offered in every turn, with or without a file or a node that has the clipboard tool: the model renders the
 * functions before the history, so one that comes and goes makes the server's cache of the prompt start over. They only touch her own
 * files, so they need no permission of the chain ({@code ServerTool}).
 */
public class WorkspaceTools implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceTools.class);

    public static final String OFFER = "offer_file_to_clipboard";
    /** The tool of a node that puts text on the user's clipboard, when the user agrees ({@code joi-node}: {@code ClipboardOffers}). */
    public static final String NODE_TOOL = "offer_to_clipboard";
    public static final String LIST_IMAGES = "list_chat_images";
    public static final String SAVE_IMAGE = "save_chat_image";
    /** The function of the platform that shows her an image of her workspace; offered when her chat model sees images. */
    public static final String VIEW_IMAGE = "view_image";
    /** The file functions that look and change nothing (see {@link ToolEffects}). */
    public static final Set<String> READ_ONLY = Set.of("list_files", "read_file", "grep_files", LIST_IMAGES, VIEW_IMAGE);

    /** How many of the latest messages are searched for images, and how many images are listed to her. */
    private static final int SEARCHED_MESSAGES = 200;
    private static final int LISTED_IMAGES = 12;

    @Autowired
    private ChatService chatService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private JoiWorkspaceService workspaceService;
    @Autowired
    private ChatImageService chatImageService;
    @Autowired
    private ChatMessageService chatMessageService;

    private final ObjectMapper jackson = new ObjectMapper();
    private Registration registration;

    @Override
    public void afterPropertiesSet() {
        registration = chatService.addEventListener(TurnEvents.COLLECT_TOOLS, (event, chain) -> {
            try {
                Joi joi = event.getJoi();
                List<String> now = event.getInput() == null ? List.of() : event.getInput().images();
                FunctionSet functions = event.getFunctions().and(workspaceService.fileTools(joi))
                        .and(FunctionSet.bind(List.of(offer(joi), listImages(joi, now), saveImage(joi, now)), this));
                if (event.getAi() != null && event.getAi().isMultimodal()) {
                    // a model that does not see images is not offered the function, the model is known: the pictures come in a message after
                    // the results of the round (CallToolsStep)
                    ShownImages shown = new ShownImages();
                    event.getProperties().put(TurnProperties.SHOWN_IMAGES, shown);
                    functions = functions.and(workspaceService.imageTools(joi, shown));
                }
                event.setFunctions(functions);
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

    private FunctionDefinition offer(Joi joi) {
        ObjectNode schema = jackson.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("path").put("type", "string").put("description", "The path of the file in your workspace, for example /notes/command.sh.");
        properties.putObject("node").put("type", "string").put("description",
                "The name of the node whose clipboard it is for; the one you are talking through when left out.");
        schema.putArray("required").add("path");
        return FunctionDefinition.dynamic(OFFER, "Puts a text file of your workspace on the user's clipboard on their machine (on some machines they see the "
                + "text first and decide whether it is copied), so say what you are offering and why. Use it for what they are to paste "
                + "somewhere (a script, a command, a text). It does not show them the text in the chat. For a short text of your own, use "
                + "the clipboard tool of the node directly.", schema,
                (continuation, arguments) -> offer(joi, text(arguments, "path"), text(arguments, "node"), continuation));
    }

    /** An image the user sent: the words with it, when, and what was written about it for a model that does not see. */
    private record SentImage(String uuid, Date sentAt, String words, String note) {
    }

    private FunctionDefinition listImages(Joi joi, List<String> now) {
        ObjectNode schema = jackson.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return FunctionDefinition.dynamic(LIST_IMAGES, "Lists the images the user sent you in the chat, the newest first, each with a number, when "
                + "it was sent and the words it came with. Use it to find an image they sent earlier; save_chat_image puts one of them in your "
                + "workspace.", schema, (continuation, arguments) -> {
            try {
                List<SentImage> images = sentImages(joi, now);
                if (images.isEmpty()) {
                    continuation.complete("The user has sent you no images.");
                    return;
                }
                StringBuilder text = new StringBuilder("The images the user sent you, newest first:");
                for (int i = 0; i < images.size(); i++) {
                    SentImage image = images.get(i);
                    text.append('\n').append(i + 1).append(". ").append(image.sentAt() == null ? "in this message" : "sent " + STAMP.format(
                            image.sentAt().toInstant().atZone(AppClock.INSTANCE.getZone())));
                    if (StringUtils.isNotBlank(image.words())) {
                        text.append(", with the words \"").append(StringUtils.abbreviate(image.words().strip(), 120)).append('"');
                    }
                    if (StringUtils.isNotBlank(image.note())) {
                        text.append(", showing: ").append(StringUtils.abbreviate(image.note().strip(), 200));
                    }
                }
                continuation.complete(text.toString());
            } catch (Exception e) {
                fail(continuation, "She could not list the images of the user", e);
            }
        });
    }

    private FunctionDefinition saveImage(Joi joi, List<String> now) {
        ObjectNode schema = jackson.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("path").put("type", "string").put("description",
                "Where to save it in your workspace, a JPEG file name, for example /images/cat.jpg.");
        properties.putObject("image").put("type", "integer").put("description",
                "The number of the image in list_chat_images; the newest one when left out.");
        schema.putArray("required").add("path");
        return FunctionDefinition.dynamic(SAVE_IMAGE, "Saves an image the user sent you in the chat (the one they just sent, or an earlier one, "
                + "see list_chat_images) as a file in your workspace, so you keep it. It is a JPEG, scaled down if it was large.", schema, (continuation, arguments) -> {
            String path = text(arguments, "path");
            if (path == null) {
                continuation.fail("Give the path of the file");
                return;
            }
            try {
                int number = arguments != null && arguments.get("image") instanceof Number n ? n.intValue() : 1;
                List<SentImage> images = sentImages(joi, now);
                if (images.isEmpty()) {
                    continuation.fail("The user has sent you no images");
                    return;
                }
                if (number < 1 || number > images.size()) {
                    continuation.fail("There is no image " + number + ": the numbers are 1 to " + images.size() + " (list_chat_images)");
                    return;
                }
                String lower = path.toLowerCase(java.util.Locale.ROOT);
                if (lower.indexOf('.', lower.lastIndexOf('/') + 1) < 0) {
                    path += ".jpg";
                } else if (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) {
                    continuation.fail("The image is a JPEG: name the file .jpg");
                    return;
                }
                File file = chatImageService.file(images.get(number - 1).uuid());
                if (file == null) {
                    continuation.fail("That image is gone");
                    return;
                }
                VirtualFileSystem files = workspaceService.open(joi);
                var target = files.parse(path);
                files.writeImage(target, Files.readAllBytes(file.toPath()));
                continuation.complete("Saved the image as " + target + " (" + file.length() + " bytes).");
            } catch (VirtualFileException e) {
                continuation.fail(e.getMessage());
            } catch (Exception e) {
                fail(continuation, "She could not save an image of the user", e);
            }
        });
    }

    /** The images of the user, the newest first: those of the message being answered, then those of the messages before; none that are gone. */
    private List<SentImage> sentImages(Joi joi, List<String> now) throws Exception {
        List<SentImage> found = new ArrayList<>();
        java.util.Set<String> seen = new HashSet<>();
        for (String uuid : now) {
            if (seen.add(uuid) && chatImageService.file(uuid) != null) {
                found.add(new SentImage(uuid, null, null, null));
            }
        }
        List<ChatMessage> messages = new ArrayList<>(chatMessageService.findLatest(joi, SEARCHED_MESSAGES));
        Collections.reverse(messages);
        for (ChatMessage message : messages) {
            List<String> ids = message.imageUuids();
            List<String> notes = message.imageNoteTexts();
            for (int i = 0; i < ids.size() && found.size() < LISTED_IMAGES; i++) {
                if (seen.add(ids.get(i)) && chatImageService.file(ids.get(i)) != null) {
                    found.add(new SentImage(ids.get(i), message.getSentAt(), message.getInput(), i < notes.size() ? notes.get(i) : null));
                }
            }
        }
        return found.size() > LISTED_IMAGES ? found.subList(0, LISTED_IMAGES) : found;
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static void fail(FunctionContinuation<?> continuation, String what, Exception e) {
        log.warn("{}: {}", what, e.getMessage(), e);
        continuation.fail("It failed: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
    }

    private void offer(Joi joi, String path, String node, FunctionContinuation<Object> continuation) {
        if (path == null) {
            continuation.fail("Give the path of the file");
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
                VirtualFileSystem files = workspaceService.open(joi);
                text = files.readText(files.parse(path));
            } catch (VirtualFileException e) {
                continuation.fail(e.getMessage() + ". Look at your files with list_files");
                return;
            }
            JsonObject arguments = new JsonObject();
            arguments.addProperty("title", path.substring(path.lastIndexOf('/') + 1));
            arguments.addProperty("text", text);
            nodeService.callTool(session, NODE_TOOL, arguments, continuation);
        } catch (Exception e) {
            log.warn("The offer of {} to the clipboard failed: {}", path, e.getMessage(), e);
            continuation.fail("It failed: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    private static String text(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        return value instanceof String string && !string.isBlank() ? string.strip() : null;
    }
}
