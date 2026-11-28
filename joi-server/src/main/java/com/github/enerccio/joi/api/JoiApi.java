package com.github.enerccio.joi.api;

import com.github.enerccio.joi.domain.animations.AnimationRoles;
import com.github.enerccio.joi.domain.bodies.BodyState;
import com.github.enerccio.joi.domain.bodies.DescribedPart;
import com.github.enerccio.joi.domain.bodies.ToggleTree;
import com.github.enerccio.joi.domain.bodies.ModelCapabilities;
import com.github.enerccio.joi.domain.bodies.joim.JoimMetadata;
import com.github.enerccio.joi.domain.chat.ChatService;
import com.github.enerccio.joi.domain.chat.ChatImages;
import com.github.enerccio.joi.domain.chat.ChatService.NoModelException;
import com.github.enerccio.joi.domain.chat.ChatService.NoVisionException;
import com.github.enerccio.joi.domain.chat.turn.TurnInput;
import com.github.enerccio.joi.domain.collections.ChatMessageType;
import com.github.enerccio.joi.domain.collections.JoiTaskStatus;
import com.github.enerccio.joi.domain.model.impl.*;
import com.github.enerccio.joi.domain.service.*;
import com.github.enerccio.joi.domain.tools.ApprovableTool;
import com.github.enerccio.joi.domain.web.TaskInfos;
import com.github.enerccio.joi.domain.service.AccessTokenService.Principal;
import com.github.enerccio.joi.protocol.JoiProtocol;
import com.github.enerccio.joi.protocol.http.*;
import com.github.enerccio.joi.protocol.joia.JoiaFile;
import com.github.enerccio.joi.protocol.joim.JoimFile;
import com.github.enerccio.joi.protocol.oauth.DeviceAuthorization;
import com.github.enerccio.joi.protocol.oauth.LocalToken;
import com.github.enerccio.joi.protocol.oauth.OAuthError;
import com.github.enerccio.joi.protocol.oauth.OAuthMetadata;
import com.github.enerccio.joi.protocol.oauth.TokenResponse;
import com.github.enerccio.joi.tts.audio.AudioKind;
import com.github.enerccio.joi.tts.model.Actor;
import com.github.enerccio.joi.tts.model.TtsModel;
import com.github.enerccio.marginalia.bound.ApiRequestAttributes;
import com.github.enerccio.marginalia.bound.SessionPoint;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.domain.model.impl.Resource;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.domain.security.service.UserService;
import com.github.enerccio.marginalia.domain.service.ResourceService;
import com.github.enerccio.marginalia.ui.components.ThreadCopyRequestAttributes.InRequestScope;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Joi's API ({@value JoiProtocol#API}, {@value JoiProtocol#OAUTH}, see {@code joi-protocol}): authenticates the call
 * (a token, an OAuth access token, or nobody when the configuration allows it), makes the request context of its user
 * ({@link ApiRequestAttributes}, no session of the container) and answers. The assistants, one of them, chat with
 * her, whole or streamed as server-sent events, and the body she is in with its files ({@code .joim}, {@code .joia}),
 * streamed from the disk. The administration ({@code /api/v1/admin}) is {@link JoiAdminApi}.
 */
public class JoiApi implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(JoiApi.class);

    static final int MAX_TEXT = 20_000;
    static final int DEFAULT_HISTORY = 500;
    static final int MAX_HISTORY = 1000;
    static final long KEEP_ALIVE_SECONDS = 15;
    static final String JSON = "application/json;charset=UTF-8";

    @Autowired
    private com.github.enerccio.joi.domain.service.SummarizerService summarizerService;

    @Autowired
    private ApiConfiguration configuration;

    @Autowired
    private ApiAccessService apiAccessService;

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private DeviceAuthorizationService deviceAuthorizationService;

    @Autowired
    private UserService userService;

    @Autowired
    private JoiService joiService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatImageService chatImageService;

    @Autowired
    private WebTaskService webTaskService;

    @Autowired
    private JoiTaskMessageService joiTaskMessageService;

    @Autowired
    private BodyService bodyService;

    @Autowired
    private ProfileService profileService;

    @Autowired
    private ModelService modelService;

    @Autowired
    private AnimationPackService packService;

    @Autowired
    private JoimService joimService;

    @Autowired
    private JoiaService joiaService;

    @Autowired
    private ResourceService resourceService;

    @Autowired
    private JoiAdminApi adminApi;

    /** Nulls written out: a client sees every field of the schema. */
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    private final ScheduledExecutorService keepAlive = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "joi-api-keep-alive");
        thread.setDaemon(true);
        return thread;
    });

    /** A call that ends in an error answer. */
    static final class ApiException extends Exception {
        final int status;
        final String code;

        ApiException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    /** Whether the path is the image upload ({@code /api/v1/jois/{id}/images}): its body is streamed to the disk, not read whole. */
    static boolean isImageUpload(String path) {
        return path != null && path.matches("/api/v1/jois/[^/]+/images/?");
    }

    public void handle(ApiCall call, ApiSink sink) throws IOException {
        // nobody yet: what a service asks for the user of meanwhile must not open a session of the container
        try (ApiRequestAttributes anonymous = new ApiRequestAttributes(new User(), new SessionPoint());
             InRequestScope _ = new InRequestScope(anonymous)) {
            String path = StringUtils.removeEnd(call.path(), "/");
            if (path.equals(JoiProtocol.OAUTH_METADATA)) {
                metadata(call, sink);
            } else if (path.equals(JoiProtocol.LOCAL)) {
                local(call, sink);
            } else if (path.startsWith(JoiProtocol.OAUTH + "/")) {
                oauth(call, path, sink);
            } else if (path.equals(JoiProtocol.API)) {
                if (!"GET".equalsIgnoreCase(call.method())) {
                    error(sink, 405, "bad_request", "Use GET");
                } else {
                    json(sink, 200, serverInfo(call));
                }
            } else if (path.startsWith(JoiProtocol.API + "/")) {
                api(call, path.substring(JoiProtocol.API.length() + 1), sink);
            } else {
                error(sink, 404, "not_found", "Not found: " + call.path());
            }
        } catch (ApiException e) {
            error(sink, e.status, e.code, e.getMessage());
        } catch (Exception e) {
            log.error("API: {} {} failed", call.method(), call.path(), e);
            error(sink, 500, "internal", "Internal error");
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // the calls of a user
    // ----------------------------------------------------------------------------------------------------------------

    private void api(ApiCall call, String path, ApiSink sink) throws Exception {
        Principal principal = authenticate(call);
        if (principal == null) {
            sink.respond(401, JSON, body(new ApiError("unauthorized",
                            "Valid credentials are needed: Authorization: Bearer <token>, see GET " + JoiProtocol.API)),
                    Map.of("WWW-Authenticate", "Bearer realm=\"joi\""));
            return;
        }
        List<String> segments = List.of(path.split("/"));
        try (ApiRequestAttributes attributes = new ApiRequestAttributes(principal.user(), new SessionPoint());
             InRequestScope _ = new InRequestScope(attributes)) {
            try {
                route(call, segments, principal, sink);
            } catch (ApiException e) {
                error(sink, e.status, e.code, e.getMessage());
            } catch (NoModelException e) {
                error(sink, 409, "no_model", e.getMessage());
            } catch (NoVisionException e) {
                error(sink, 409, "no_vision", e.getMessage());
            } catch (ChatImages.BadImageException e) {
                error(sink, e.tooLarge() ? 413 : 400, "bad_image", e.getMessage());
            } catch (IllegalStateException e) {
                error(sink, 409, "conflict", StringUtils.defaultIfBlank(e.getMessage(), "Conflict"));
            } catch (IllegalArgumentException | JsonParseException e) {
                error(sink, 400, "bad_request", StringUtils.defaultIfBlank(e.getMessage(), "Bad request"));
            } catch (SecurityException e) {
                error(sink, 403, "forbidden", StringUtils.defaultIfBlank(e.getMessage(), "Forbidden"));
            }
        }
    }

    private void route(ApiCall call, List<String> path, Principal principal, ApiSink sink) throws Exception {
        boolean get = "GET".equalsIgnoreCase(call.method());
        boolean post = "POST".equalsIgnoreCase(call.method());
        if (get && path.equals(List.of("me"))) {
            User user = principal.user();
            json(sink, 200, new Me(user.getLogin(), StringUtils.defaultIfBlank(user.getFullName(), user.getLogin()), principal.method(),
                    principal.scopes().stream().sorted().toList()));
        } else if (path.getFirst().equals("admin")) {
            adminApi.handle(call, path, principal, sink);
        } else if (get && path.equals(List.of("jois"))) {
            json(sink, 200, jois());
        } else if (get && path.size() == 2 && path.getFirst().equals("jois")) {
            json(sink, 200, info(joi(path.get(1))));
        } else if (get && path.size() == 3 && path.getFirst().equals("jois") && path.get(2).equals("chat")) {
            json(sink, 200, history(call, joi(path.get(1))));
        } else if (post && path.size() == 3 && path.getFirst().equals("jois") && path.get(2).equals("chat")) {
            chat(call, joi(path.get(1)), sink);
        } else if (post && path.size() == 3 && path.getFirst().equals("jois") && path.get(2).equals("images")) {
            joi(path.get(1));
            json(sink, 200, upload(call));
        } else if (get && path.size() == 2 && path.getFirst().equals("images")) {
            image(call, path.get(1), sink);
        } else if (get && path.size() == 3 && path.getFirst().equals("jois") && path.get(2).equals("body")) {
            json(sink, 200, bodyInfo(call, joi(path.get(1))));
        } else if (get && path.size() == 3 && path.getFirst().equals("jois") && path.get(2).equals("auto-approved")) {
            json(sink, 200, autoApproved(joi(path.get(1))));
        } else if (("DELETE".equalsIgnoreCase(call.method()) || "PUT".equalsIgnoreCase(call.method())) && path.size() == 4
                && path.getFirst().equals("jois") && path.get(2).equals("auto-approved")) {
            Joi joi = joi(path.get(1));
            ApprovableTool tool = webTaskService.tool(path.get(3));
            if (tool == null) {
                throw new ApiException(404, "not_found", "No tool " + path.get(3));
            }
            boolean on = "PUT".equalsIgnoreCase(call.method());
            if (on != webTaskService.autoApproves(joi, tool)) {
                webTaskService.autoApprove(joi, tool, on);
            }
            json(sink, 200, autoApproved(joi));
        } else if (get && path.size() == 3 && path.getFirst().equals("jois") && path.get(2).equals("tasks")) {
            json(sink, 200, tasks(call, joi(path.get(1))));
        } else if (get && path.size() == 4 && path.getFirst().equals("jois") && path.get(2).equals("tasks")) {
            json(sink, 200, taskInfo(task(joi(path.get(1)), path.get(3)), true));
        } else if (post && path.size() == 5 && path.getFirst().equals("jois") && path.get(2).equals("tasks")) {
            json(sink, 200, taskCall(call, task(joi(path.get(1)), path.get(3)), path.get(4)));
        } else if (get && path.size() == 3 && path.getFirst().equals("models") && path.get(2).equals("joim")) {
            joim(call, path.get(1), sink);
        } else if (get && path.size() == 3 && path.getFirst().equals("bodies") && path.get(2).equals("joia")) {
            joia(call, path.get(1), sink);
        } else {
            throw new ApiException(404, "not_found", "Not found: " + call.method() + " " + call.path());
        }
    }

    private JoiList jois() throws Exception {
        List<Joi> jois = joiService.findAllByName();
        Map<Long, Date> last = chatMessageService.findLastSentAt(jois);
        List<JoiSummary> list = new ArrayList<>();
        for (Joi joi : jois) {
            list.add(new JoiSummary(joi.getUuid(), joiService.name(joi), iso(last.get(joi.getId()))));
        }
        return new JoiList(list);
    }

    private JoiInfo info(Joi joi) throws Exception {
        JoiService.Persona persona = joiService.persona(joi);
        String name = persona.assistantName();
        Date last = chatMessageService.findLastSentAt(List.of(joi)).get(joi.getId());
        AI model = chatService.chatModel(joi);
        TtsModel tts = chatService.ttsModel(joi);
        Actor actor = chatService.voiceActor(joi);
        return new JoiInfo(joi.getUuid(), name, persona.userName(),
                persona.personalityKey() == null ? null : new Personality(persona.personalityKey(), persona.personalityName()),
                joi.getMood(), StringUtils.defaultIfBlank(joi.getWakeWord(), "Hey " + name), iso(last),
                model == null ? null : model.getName(), tts == null || actor == null ? null : new Voice(tts.getName(), actor.getName(),
                        StringUtils.defaultIfBlank(actor.getDescription(), null)), animations(joi), model != null && chatImageService.canSee(model));
    }

    /**
     * Her latest exchanges ({@code limit}, at most {@value #MAX_HISTORY}), oldest first, or the ones before the message
     * {@code before}.
     */
    private ChatHistory history(ApiCall call, Joi joi) throws Exception {
        int limit = DEFAULT_HISTORY;
        String asked = call.query().get("limit");
        if (StringUtils.isNotBlank(asked)) {
            try {
                limit = Integer.parseInt(asked.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("limit is not a number: " + asked);
            }
            if (limit < 1 || limit > MAX_HISTORY) {
                throw new IllegalArgumentException("limit is 1 to " + MAX_HISTORY);
            }
        }
        String before = call.query().get("before");
        List<ChatMessage> messages;
        if (StringUtils.isNotBlank(before)) {
            Long id = chatMessageService.find(before.strip());
            ChatMessage first = id == null ? null : chatMessageService.find(id);
            if (first == null || first.isDeleted() || !first.getJoi().getId().equals(joi.getId())) {
                throw new ApiException(404, "not_found", "No message " + before);
            }
            messages = chatMessageService.findBefore(joi, first, limit + 1);
        } else {
            messages = chatMessageService.findLatest(joi, limit + 1);
        }
        boolean more = messages.size() > limit;
        // what a summary covers is no longer given to her as messages (her prompt's history starts there)
        Date covered = summarizerService.coveredUpTo(joi);
        List<ChatEntry> entries = new ArrayList<>();
        for (ChatMessage message : more ? messages.subList(1, messages.size()) : messages) {
            boolean event = message.getType() == ChatMessageType.EVENT;
            boolean note = message.getType() == ChatMessageType.LOG;
            entries.add(new ChatEntry(message.getUuid(), iso(message.getSentAt()), note ? "log" : event ? "event" : "user",
                    event ? null : message.getInput(), event || note ? message.getEventKind() : null,
                    StringUtils.defaultIfBlank(message.getNode(), null), StringUtils.defaultIfBlank(message.getResponse(), null),
                    message.imageUuids().isEmpty() ? null : message.imageUuids(),
                    message.toolImageUuids().isEmpty() ? null : message.toolImageUuids(),
                    message.getSentAt().before(covered) ? Boolean.TRUE : null));
        }
        return new ChatHistory(entries, more);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // tasks
    // ----------------------------------------------------------------------------------------------------------------

    private TaskList tasks(ApiCall call, Joi joi) throws Exception {
        String asked = call.query().get("status");
        JoiTaskStatus status = null;
        if (StringUtils.isNotBlank(asked)) {
            try {
                status = JoiTaskStatus.valueOf(asked.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("status is not one of queued, running, waiting, done, failed, cancelled: " + asked);
            }
        }
        List<TaskInfo> list = new ArrayList<>();
        for (JoiTask task : webTaskService.tasks(joi)) {
            if (status == null || task.getStatus() == status) {
                list.add(taskInfo(task, false));
            }
        }
        return new TaskList(list);
    }

    private AutoApprovalList autoApproved(Joi joi) throws Exception {
        List<AutoApproval> tools = new ArrayList<>();
        for (ApprovableTool tool : webTaskService.autoApproved(joi)) {
            tools.add(new AutoApproval(tool.function(), tool.function(), tool.description()));
        }
        return new AutoApprovalList(tools);
    }

    /** The task of the assistant; a task of another assistant or user is not found. */
    private JoiTask task(Joi joi, String uuid) throws Exception {
        JoiTask task = webTaskService.find(uuid);
        if (task == null || !task.getJoi().getId().equals(joi.getId())) {
            throw new ApiException(404, "not_found", "No task " + uuid);
        }
        return task;
    }

    private TaskInfo taskCall(ApiCall call, JoiTask task, String action) throws Exception {
        JoiTask done = switch (action) {
            case "approve" -> {
                TaskApproval approval = call.body().length == 0 ? null : gson.fromJson(call.text(), TaskApproval.class);
                yield webTaskService.approve(task, approval != null && Boolean.TRUE.equals(approval.always()));
            }
            case "deny" -> {
                TaskDenial denial = call.body().length == 0 ? null : gson.fromJson(call.text(), TaskDenial.class);
                yield webTaskService.deny(task, denial == null ? null : StringUtils.trimToNull(denial.reason()));
            }
            case "cancel" -> webTaskService.cancel(task);
            default -> throw new ApiException(404, "not_found", "Not found: " + call.method() + " " + call.path());
        };
        return taskInfo(done, false);
    }

    private TaskInfo taskInfo(JoiTask task, boolean steps) throws Exception {
        List<TaskStep> transcript = null;
        if (steps) {
            transcript = new ArrayList<>();
            for (JoiTaskMessage message : joiTaskMessageService.findByTask(task)) {
                transcript.add(new TaskStep(message.getRole().name().toLowerCase(Locale.ROOT),
                        StringUtils.defaultIfEmpty(message.getContent(), null), StringUtils.defaultIfEmpty(message.getToolCalls(), null)));
            }
        }
        return TaskInfos.of(task, transcript);
    }

    /** The animations she plays in her states, the ones still in the user's library. */
    private JoiAnimations animations(Joi joi) throws Exception {
        List<String> library = packService.findAllByName().stream().map(AnimationPack::getUuid).toList();
        AnimationRoles roles = joi.animationRoleSet().existing(library);
        return new JoiAnimations(roles.sleeping(), roles.fallingAsleep(), roles.wakingUp(), roles.idle(), roles.talking());
    }

    // ----------------------------------------------------------------------------------------------------------------
    // her body
    // ----------------------------------------------------------------------------------------------------------------

    private BodyInfo bodyInfo(ApiCall call, Joi joi) throws Exception {
        // the body of the profile the node (its stable id, ?node=) is in
        String node = call.query().get("node");
        String profile = StringUtils.isBlank(node) ? null : profileService.profileOf(joi, node);
        Body body = bodyService.active(joi, profile);
        if (body == null) {
            throw new ApiException(404, "no_body", joiService.name(joi) + " is in no body");
        }
        String base = baseUrl(call) + JoiProtocol.API;
        Model model = model(body.getModel());
        List<BodyPart> parts = new ArrayList<>();
        List<BodyToggle> toggles = new ArrayList<>();
        ModelCapabilities capabilities = model == null ? null : ModelCapabilities.fromJson(model.getCapabilities());
        if (capabilities != null) {
            Map<String, String> states = bodyService.partStates(body, profile);
            ToggleTree tree = ToggleTree.of(model, capabilities);
            for (DescribedPart part : DescribedPart.of(model, capabilities)) {
                DescribedPart.State in = BodyState.stateOf(model, part, states);
                List<BodyPartState> partStates = new ArrayList<>();
                for (DescribedPart.State state : part.states()) {
                    List<BodyToggle> set = part.toggles().stream().map(toggle -> new BodyToggle(toggle.mesh(), toggle.key(), toggle.hides(),
                            state.shown().getOrDefault(toggle.id(), model.shownByDefault(toggle)), tree.parent(toggle.id()))).toList();
                    partStates.add(new BodyPartState(state.id(), state.name(), part.isDefault(state), set));
                }
                parts.add(new BodyPart(part.id(), part.name(), in == null ? null : in.id(), in == null ? null : in.name(),
                        in != null && part.isDefault(in), partStates));
            }
            // every toggle as the body has it: the parts as they are switched, the rest as by default
            Map<String, Boolean> shown = BodyState.toggles(model, states);
            for (ModelCapabilities.Toggle toggle : capabilities.toggles()) {
                toggles.add(new BodyToggle(toggle.mesh(), toggle.key(), toggle.hides(), shown.getOrDefault(toggle.id(), model.shownByDefault(toggle)),
                        tree.parent(toggle.id())));
            }
        }
        List<BodyAnimation> animations = new ArrayList<>();
        for (AnimationPack pack : bodyService.animations(body)) {
            animations.add(new BodyAnimation(pack.getUuid(), pack.getName(), StringUtils.defaultIfBlank(pack.getDescription(), null), pack.loops()));
        }
        Download joia = download(base + "/bodies/" + body.getUuid() + "/joia", bodyService.animationsFile(body),
                body.getAnimationsResourceId(), body.getAnimationsExportedAt(), !joiaService.needsBodyExport(body));
        return new BodyInfo(body.getUuid(), StringUtils.defaultIfBlank(body.getName(), null), bodyModel(base, model),
                bodyModel(base, model(body.getLookingGlassModel())), parts, toggles, StringUtils.defaultIfBlank(bodyService.appearance(body, profile), null),
                new BodyAnimations(body.animationUuids() == null, animations, joia), outfits(body, profile));
    }

    /** The outfits of the body, the one she wears in the profile marked. */
    private List<com.github.enerccio.joi.protocol.http.BodyOutfit> outfits(Body body, String profile) throws Exception {
        com.github.enerccio.joi.domain.bodies.Outfit wearing = bodyService.currentOutfit(body, profile);
        List<com.github.enerccio.joi.protocol.http.BodyOutfit> outfits = new ArrayList<>();
        for (com.github.enerccio.joi.domain.bodies.Outfit outfit : bodyService.outfits(body)) {
            outfits.add(new com.github.enerccio.joi.protocol.http.BodyOutfit(outfit.id(), outfit.name(),
                    StringUtils.defaultIfBlank(outfit.description(), null), wearing != null && wearing.id().equals(outfit.id())));
        }
        return outfits;
    }

    /** A model of a body (a lazy reference), read by its id; null for none or a deleted one. */
    private Model model(Model reference) throws Exception {
        if (reference == null) {
            return null;
        }
        Model model = modelService.find(reference.getId());
        return model == null || model.isDeleted() ? null : model;
    }

    private BodyModel bodyModel(String base, Model model) throws Exception {
        if (model == null) {
            return null;
        }
        Download joim = download(base + "/models/" + model.getUuid() + "/joim", modelService.exportFile(model),
                model.getExportResourceId(), model.getExportedAt(), !joimService.needsExport(model));
        return new BodyModel(model.getUuid(), StringUtils.defaultIfBlank(model.getName(), null), joim);
    }

    /** A stored file to download, null when there is none. */
    private Download download(String url, File file, Long resourceId, Date writtenAt, boolean current) throws Exception {
        String hash = hash(resourceId);
        if (file == null || !file.isFile() || hash == null) {
            return null;
        }
        return new Download(url, hash, file.length(), iso(writtenAt != null ? writtenAt : new Date(file.lastModified())), current);
    }

    private String hash(Long resourceId) throws Exception {
        Resource resource = resourceId == null ? null : resourceService.find(resourceId);
        return resource == null || resource.isDeleted() ? null : resource.getHash();
    }

    private void joim(ApiCall call, String id, ApiSink sink) throws Exception {
        Model model = modelService.findForUser(id);
        if (model == null || model.isDeleted()) {
            throw new ApiException(404, "not_found", "No model " + id);
        }
        File file = modelService.exportFile(model);
        String hash = hash(model.getExportResourceId());
        if (file == null || !file.isFile() || hash == null) {
            throw new ApiException(404, "not_found", "The model has no .joim yet: it is written when its dialog is exited");
        }
        file(call, sink, file, hash, JoimFile.CONTENT_TYPE, JoimMetadata.fileName(model.getName()));
    }

    private void joia(ApiCall call, String id, ApiSink sink) throws Exception {
        Body body = bodyService.findForUser(id);
        if (body == null || body.isDeleted()) {
            throw new ApiException(404, "not_found", "No body " + id);
        }
        File file = bodyService.animationsFile(body);
        String hash = hash(body.getAnimationsResourceId());
        if (file == null || !file.isFile() || hash == null) {
            throw new ApiException(404, "not_found", "The body has no .joia: it is written when its dialog is exited, when it has animations");
        }
        file(call, sink, file, hash, JoiaFile.CONTENT_TYPE, JoiaFile.fileName(body.getName()));
    }

    /** The file with its hash as the ETag; 304 when the client has it already. */
    private void file(ApiCall call, ApiSink sink, File file, String hash, String contentType, String name) throws IOException {
        String etag = "\"" + hash + "\"";
        String ascii = name.replaceAll("[^\\x20-\\x7e]|\"", "_");
        Map<String, String> headers = Map.of("ETag", etag, "Cache-Control", "private, no-cache",
                "Content-Disposition", "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''"
                        + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"));
        String match = call.header("If-None-Match");
        if (match != null && (match.strip().equals("*") || Arrays.stream(match.split(",")).map(String::strip)
                .map(tag -> StringUtils.removeStart(tag, "W/")).anyMatch(etag::equals))) {
            sink.respond(304, contentType, new byte[0], headers);
            return;
        }
        sink.file(200, contentType, file, headers);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // images
    // ----------------------------------------------------------------------------------------------------------------

    /** Streams the body into a file of the user's, keeps it as the scaled JPEG it is made into and answers its id. */
    private ImageUpload upload(ApiCall call) throws Exception {
        File file = chatImageService.newUploadFile();
        try (InputStream in = call.bodyStream(); OutputStream out = Files.newOutputStream(file.toPath())) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > ChatImages.MAX_BYTES) {
                    throw new ApiException(413, "bad_image", "The image is larger than " + (ChatImages.MAX_BYTES / 1024 / 1024) + " MB");
                }
                out.write(buffer, 0, n);
            }
        } catch (IOException | RuntimeException | ApiException e) {
            Files.deleteIfExists(file.toPath());
            throw e;
        }
        ChatImageService.Image image = chatImageService.store(file);
        return new ImageUpload(image.id(), image.mimeType(), image.size(), image.width(), image.height());
    }

    /** The image of the user as it is kept; it never changes, so its id is its ETag. */
    private void image(ApiCall call, String id, ApiSink sink) throws Exception {
        File file = chatImageService.file(id);
        if (file == null) {
            throw new ApiException(404, "not_found", "No image " + id);
        }
        file(call, sink, file, id, "image/jpeg", "image-" + id + ".jpg");
    }

    // ----------------------------------------------------------------------------------------------------------------
    // chat
    // ----------------------------------------------------------------------------------------------------------------

    private void chat(ApiCall call, Joi joi, ApiSink sink) throws Exception {
        ChatRequest request = gson.fromJson(call.text(), ChatRequest.class);
        if (request == null || (StringUtils.isBlank(request.text()) && request.imagesOrEmpty().isEmpty())) {
            throw new IllegalArgumentException("The text is missing");
        }
        if (request.text() != null && request.text().length() > MAX_TEXT) {
            throw new IllegalArgumentException("The text is longer than " + MAX_TEXT + " characters");
        }
        String accept = StringUtils.defaultString(call.header("Accept"));
        boolean stream = request.streamOrDefault() || accept.contains(JoiProtocol.EVENT_STREAM);
        if (chatService.chatModel(joi) == null) {
            throw new NoModelException(joiService.name(joi) + " has no chat model: choose one in her About tab or in User Settings");
        }
        // 409 no_vision before anything is streamed
        List<String> images = chatService.checkImages(joi, request.imagesOrEmpty());
        if (stream) {
            stream(joi, request, images, sink.events());
            return;
        }
        ChatService.Result result;
        try {
            result = await(chatService.chat(joi, new TurnInput(StringUtils.defaultString(request.text()), request.node(), request.speakOrDefault(), false,
                    null, images), null));
        } catch (NoModelException | NoVisionException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("API: the chat of {} failed", joi.getUuid(), e);
            throw new ApiException(502, "model_failed", reason(e));
        }
        Audio audio = result.audio() == null ? null : new Audio(AudioKind.of(result.audio()).mime(), base64(result.audio()));
        json(sink, 200, reply(result.message(), request.reasoningOrDefault(), audio, result.speechError(), result.speechMs()));
    }

    private void stream(Joi joi, ChatRequest request, List<String> images, ApiSink.EventStream events) {
        boolean reasoning = request.reasoningOrDefault();
        ScheduledFuture<?> alive = keepAlive.scheduleAtFixedRate(() -> {
            try {
                events.comment("keep-alive");
            } catch (IOException e) {
                // the client is gone; the turn goes on and is stored
            }
        }, KEEP_ALIVE_SECONDS, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
        try {
            ChatService.Result result = await(chatService.chat(joi,
                    new TurnInput(StringUtils.defaultString(request.text()), request.node(), request.speakOrDefault(), true, null, images),
                    new ChatService.Listener() {
                        @Override
                        public void onReasoning(String delta) throws Exception {
                            if (reasoning) {
                                send(events, StreamEvent.REASONING, new TextDelta(delta));
                            }
                        }

                        @Override
                        public void onText(String delta) throws Exception {
                            send(events, StreamEvent.TEXT, new TextDelta(delta));
                        }

                        @Override
                        public void onReply(ChatMessage message) throws Exception {
                            send(events, StreamEvent.REPLY, reply(message, reasoning, null, null, -1));
                        }

                        @Override
                        public void onAudio(int index, String text, byte[] audio) throws Exception {
                            send(events, StreamEvent.AUDIO, new AudioChunk(index, text, AudioKind.of(audio).mime(), base64(audio)));
                        }
                    }));
            send(events, StreamEvent.DONE, new Done(result.message().getUuid(), result.speechError()));
        } catch (Exception e) {
            log.warn("API: the streamed chat of {} failed", joi.getUuid(), e);
            try {
                String code = e instanceof NoModelException ? "no_model" : e instanceof NoVisionException ? "no_vision"
                        : e instanceof IllegalArgumentException ? "bad_request" : "model_failed";
                send(events, StreamEvent.ERROR, new ApiError(code, reason(e)));
            } catch (IOException gone) {
                // nobody to tell
            }
        } finally {
            alive.cancel(false);
        }
    }

    /** Waits for the turn: the call holds the request scope the turn runs in. Its failure is thrown as it is. */
    private static ChatService.Result await(CompletableFuture<ChatService.Result> turn) throws Exception {
        try {
            return turn.get();
        } catch (InterruptedException e) {
            turn.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    /** Her reply as the API gives it (the node WebSocket too). */
    public static ChatReply reply(ChatMessage message, boolean withReasoning, Audio audio, String speechError, long speechMs) {
        long request = message.getRequest() == null ? 0 : message.getRequest().getTime();
        long first = message.getTtft() == null ? -1 : message.getTtft().getTime() - request;
        long text = message.getResponseEnd() == null ? 0 : message.getResponseEnd().getTime() - request;
        Usage usage = new Usage(message.getPromptTokens() == null ? 0 : message.getPromptTokens(),
                message.getTokenCount() == null ? 0 : message.getTokenCount(), first, text, speechMs);
        return new ChatReply(message.getUuid(), message.getResponse(), withReasoning ? message.getResponseReasoning() : null,
                message.getMood(), message.getModelUsed(), audio, speechError, usage);
    }

    private Joi joi(String uuid) throws Exception {
        Joi joi = joiService.findForUser(uuid);
        if (joi == null || joi.isDeleted()) {
            throw new ApiException(404, "not_found", "No assistant " + uuid);
        }
        return joi;
    }

    /** Who the call acts for, null when nobody: its bearer credential, else the configured user of no auth. */
    Principal authenticate(ApiCall call) throws Exception {
        return authenticate(call.header("Authorization"));
    }

    /**
     * Whom a call with this {@code Authorization} header (null for none) acts for, as for every call of the API (the
     * node WebSocket uses it too); null when the credentials are missing or not valid.
     */
    public Principal authenticate(String authorization) throws Exception {
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            // a credential that is sent must be valid, also when no auth is on
            return accessTokenService.authenticate(authorization.substring(7).strip());
        }
        if (authorization != null || !configuration.isNoAuth()) {
            return null;
        }
        User user = noAuthUser();
        return user == null ? null : new Principal(user, null, AuthMethod.NONE);
    }

    private User noAuthUser() throws Exception {
        if (configuration.getNoAuthUser() != null) {
            User user = userService.findByName(configuration.getNoAuthUser());
            if (user == null || user.isDeleted()) {
                log.warn("API: the user of no auth ({}) does not exist", configuration.getNoAuthUser());
                return null;
            }
            return user;
        }
        return userService.findAll().stream().filter(u -> u.isAdmin() && !u.isDeleted()).min(Comparator.comparing(User::getId)).orElse(null);
    }

    private ServerInfo serverInfo(ApiCall call) throws Exception {
        List<AuthMethod> methods = apiAccessService.methods();
        String base = baseUrl(call);
        OAuthEndpoints oauth = methods.contains(AuthMethod.OAUTH)
                ? new OAuthEndpoints(base + JoiProtocol.DEVICE_AUTHORIZATION, base + JoiProtocol.TOKEN, base + "/?device")
                : null;
        return new ServerInfo(JoiProtocol.VERSION, "joi-server", version(), new AuthInfo(methods, oauth));
    }

    // ----------------------------------------------------------------------------------------------------------------
    // OAuth
    // ----------------------------------------------------------------------------------------------------------------

    private void metadata(ApiCall call, ApiSink sink) throws Exception {
        if (!apiAccessService.get().oauth()) {
            error(sink, 404, "not_found", "OAuth is not offered");
            return;
        }
        String base = baseUrl(call);
        json(sink, 200, new OAuthMetadata(base, base + JoiProtocol.DEVICE_AUTHORIZATION, base + JoiProtocol.TOKEN,
                List.of(JoiProtocol.DEVICE_CODE_GRANT, "refresh_token"), List.of("none")));
    }

    /**
     * {@code POST /oauth/local}: the token of a node on the server's own machine. Only a call that arrived on the local socket is
     * answered; for any other it is a path that does not exist.
     */
    private void local(ApiCall call, ApiSink sink) throws Exception {
        if (!call.local()) {
            oauthError(sink, 404, "invalid_request", "Not found: " + JoiProtocol.LOCAL);
            return;
        }
        if (!"POST".equalsIgnoreCase(call.method())) {
            oauthError(sink, 405, "invalid_request", "Use POST");
            return;
        }
        User user = noAuthUser();
        if (user == null) {
            oauthError(sink, 409, "no_user", "Nobody has an account yet: open Joi in the browser and make the first one");
            return;
        }
        Map<String, String> form = call.form();
        String clientId = StringUtils.defaultIfBlank(form.get("client_id"), "joi-node");
        try (ApiRequestAttributes attributes = new ApiRequestAttributes(user, new SessionPoint());
             InRequestScope _ = new InRequestScope(attributes)) {
            AccessTokenService.Issued issued = accessTokenService.issueLocal(StringUtils.defaultIfBlank(form.get("client_name"), clientId), clientId);
            json(sink, 200, new LocalToken(issued.accessToken(), "Bearer", issued.expiresInSeconds()));
        } catch (IllegalArgumentException e) {
            oauthError(sink, 400, "invalid_request", e.getMessage());
        }
    }

    private void oauth(ApiCall call, String path, ApiSink sink) throws Exception {
        if (!"POST".equalsIgnoreCase(call.method())) {
            oauthError(sink, 405, "invalid_request", "Use POST");
            return;
        }
        if (!apiAccessService.get().oauth()) {
            oauthError(sink, 503, "unavailable", "OAuth is turned off on this server");
            return;
        }
        Map<String, String> form = call.form();
        if (path.equals(JoiProtocol.DEVICE_AUTHORIZATION)) {
            if (StringUtils.isBlank(form.get("client_id"))) {
                oauthError(sink, 400, "invalid_request", "client_id is missing");
                return;
            }
            DeviceAuthorization codes;
            try {
                codes = deviceAuthorizationService.start(form.get("client_id"), form.get("client_name"), baseUrl(call), form.get("pairing_offer"));
            } catch (IllegalArgumentException e) {
                oauthError(sink, 400, "invalid_request", e.getMessage());
                return;
            }
            json(sink, 200, codes);
        } else if (path.equals(JoiProtocol.TOKEN)) {
            token(form, sink);
        } else {
            oauthError(sink, 404, "invalid_request", "Not found: " + path);
        }
    }

    private void token(Map<String, String> form, ApiSink sink) throws Exception {
        String grant = StringUtils.defaultString(form.get("grant_type"));
        if (grant.equals(JoiProtocol.DEVICE_CODE_GRANT)) {
            DeviceAuthorizationService.Poll poll = deviceAuthorizationService.poll(form.get("device_code"));
            if (poll.issued() == null) {
                oauthError(sink, 400, poll.error(), null);
                return;
            }
            json(sink, 200, tokens(poll.issued()));
        } else if (grant.equals("refresh_token")) {
            AccessTokenService.Issued issued = accessTokenService.refresh(form.get("refresh_token"));
            if (issued == null) {
                oauthError(sink, 400, "invalid_grant", "The refresh token is not valid: approve the device again");
                return;
            }
            json(sink, 200, tokens(issued));
        } else {
            oauthError(sink, 400, "unsupported_grant_type", "Supported: " + JoiProtocol.DEVICE_CODE_GRANT + ", refresh_token");
        }
    }

    private static TokenResponse tokens(AccessTokenService.Issued issued) {
        return new TokenResponse(issued.accessToken(), "Bearer", issued.expiresInSeconds(), issued.refreshToken());
    }

    private void oauthError(ApiSink sink, int status, String error, String description) throws IOException {
        sink.respond(status, JSON, body(new OAuthError(error, description)), Map.of());
    }

    // ----------------------------------------------------------------------------------------------------------------
    // writing
    // ----------------------------------------------------------------------------------------------------------------

    private String baseUrl(ApiCall call) {
        return configuration.getPublicUrl() != null ? configuration.getPublicUrl() : call.baseUrl();
    }

    private void send(ApiSink.EventStream events, StreamEvent event, Record data) throws IOException {
        events.send(event.eventName(), gson.toJson(data));
    }

    private void json(ApiSink sink, int status, Object value) throws IOException {
        sink.respond(status, JSON, body(value), Map.of());
    }

    private byte[] body(Object value) {
        return gson.toJson(value).getBytes(StandardCharsets.UTF_8);
    }

    /** An {@link ApiError} answer. */
    static void error(ApiSink sink, int status, String code, String message) throws IOException {
        sink.respond(status, JSON, new Gson().toJson(new ApiError(code, message)).getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private static String iso(Date date) {
        return date == null ? null : date.toInstant().toString();
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String reason(Exception e) {
        Throwable root = ExceptionUtils.getRootCause(e);
        return StringUtils.defaultIfBlank((root == null ? e : root).getMessage(), e.getClass().getSimpleName());
    }

    /** The version of joi-server. */
    public static String version() {
        String version = JoiApi.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    @Override
    public void destroy() {
        keepAlive.shutdownNow();
    }
}
