package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.chat.WebResearch;
import com.github.enerccio.joi.domain.chat.WorkspaceTools;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.collections.JoiTaskStatus;
import com.github.enerccio.joi.domain.collections.TaskMessageRole;
import com.github.enerccio.joi.domain.collections.TextLLMType;
import com.github.enerccio.joi.domain.events.JoiEvents;
import com.github.enerccio.joi.domain.jobs.JoiJobs;
import com.github.enerccio.marginalia.domain.service.job.JobCallType;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.JoiTask;
import com.github.enerccio.joi.domain.model.impl.JoiTaskMessage;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.model.impl.ToolMetadata;
import com.github.enerccio.joi.domain.service.ToolMetadataService;
import com.github.enerccio.joi.domain.tools.ToolScope;
import com.github.enerccio.joi.domain.persona.JoiPromptField;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.JoiTaskMessageService;
import com.github.enerccio.joi.domain.service.JoiTaskService;
import com.github.enerccio.joi.domain.service.NodeService;
import com.github.enerccio.joi.domain.web.TaskInfos;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.domain.service.QueuedEventService;
import com.github.enerccio.joi.domain.service.ToolPermissionService;
import com.github.enerccio.joi.domain.service.WebTaskService;
import com.github.enerccio.joi.domain.service.WebToolSettingsService;
import com.github.enerccio.joi.domain.service.WebToolSettingsService.Pages;
import com.github.enerccio.joi.domain.service.WebToolSettingsService.Search;
import com.github.enerccio.joi.domain.templates.TaskEventTemplateData;
import com.github.enerccio.joi.domain.templates.WebTaskTemplateData;
import com.github.enerccio.joi.domain.tools.ServerTool;
import com.github.enerccio.joi.domain.workspace.TaskWorkspace;
import com.github.enerccio.joi.domain.tools.ToolPermission;
import com.github.enerccio.joi.domain.web.SearchSource;
import com.github.enerccio.marginalia.bound.ApiRequestAttributes;
import com.github.enerccio.marginalia.bound.SessionPoint;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.domain.security.service.UserService;
import com.github.enerccio.marginalia.domain.service.AIService;
import com.github.enerccio.marginalia.domain.service.InferenceServices;
import com.github.enerccio.marginalia.ui.components.ThreadCopyRequestAttributes.InRequestScope;
import com.github.enerccio.marginalia.utils.AppClock;
import com.github.enerccio.joi.domain.chat.ChatService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class WebTaskServiceImpl implements WebTaskService, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(WebTaskServiceImpl.class);

    @Autowired
    private JoiTaskService taskService;
    @Autowired
    private JoiTaskMessageService messageService;
    @Autowired
    private ToolPermissionService permissionService;
    @Autowired
    private ToolMetadataService metadataService;
    @Autowired
    private WebToolSettingsService settingsService;
    @Autowired
    private ChatService chatService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private QueuedEventService queuedEventService;
    @Autowired
    private PersonaService personaService;
    @Autowired
    private JoiService joiService;
    @Autowired
    private UserService userService;
    @Autowired
    private User currentUser;
    @Autowired
    private AIService aiService;
    @Autowired
    private TaskWorkspace workspace;
    @Autowired
    private InferenceServices inferenceServices;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "joi-task");
        thread.setDaemon(true);
        return thread;
    });
    /** The assistants a task runs for now: one at a time each. */
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    /** The tasks that run now, and those that are asked to stop. */
    private final Set<Long> active = ConcurrentHashMap.newKeySet();
    private final Set<Long> stopping = ConcurrentHashMap.newKeySet();
    /** Who watches the tasks of an assistant (the Tasks tab), by the id of the assistant. */
    private final java.util.Map<Long, List<Runnable>> watchers = new ConcurrentHashMap<>();
    /** The status of each task as its nodes were last told, so a step of its transcript is not a frame. */
    private final java.util.Map<Long, JoiTaskStatus> told = new ConcurrentHashMap<>();
    private Registration registration;

    @Override
    public void afterPropertiesSet() {
        // every turn that ends may have left a task that was waiting for it
        registration = chatService.addEventObserver(TurnEvents.AFTER_CLEANUP, observation -> {
            try {
                if (observation.getJoi() != null) {
                    drain(observation.getJoi());
                }
            } catch (Exception e) {
                log.warn("The tasks of the assistant were not looked at: {}", e.getMessage(), e);
            }
        });
    }

    @Override
    public void destroy() {
        if (registration != null) {
            registration.unregister();
        }
        executor.shutdownNow();
    }

    // ----------------------------------------------------------------------------------------------------------------
    // what she and the user do
    // ----------------------------------------------------------------------------------------------------------------

    @Override
    public JoiTask submit(Joi joi, ServerTool tool, String question, String url, String toolCallId) throws Exception {
        ToolPermission permission = permissionService.effective(joi, tool);
        if (permission == ToolPermission.DENY) {
            throw new NotAllowedException("The use of " + tool.function() + " is not allowed");
        }
        JoiTask task = new JoiTask();
        task.setJoi(joi);
        task.setRole(TextLLMType.DEEP_REASONING);
        task.setTool(tool.function());
        task.setTitle(StringUtils.abbreviate(StringUtils.normalizeSpace(StringUtils.firstNonBlank(question, url, tool.function())), 90));
        task.setInstructions(StringUtils.defaultIfBlank(question, null));
        task.setUrl(StringUtils.defaultIfBlank(url, null));
        task.setToolCallId(toolCallId);
        task.setStatus(permission == ToolPermission.ALLOW ? JoiTaskStatus.QUEUED : JoiTaskStatus.WAITING);
        boolean standing = permission == ToolPermission.ASK && autoApproves(joi, tool);
        task.setAutoApproved(standing);
        JoiTask saved = saveTask(task);
        if (standing) {
            // the answer the user would give, given by what they allowed ahead of time
            return approve(saved);
        }
        if (permission == ToolPermission.ALLOW) {
            drain(joi);
        }
        return saved;
    }

    @Override
    public boolean autoApproves(Joi joi, ServerTool tool) throws Exception {
        ToolMetadata metadata = metadataService.find(ToolScope.JOI, joi, tool);
        return metadata != null && metadata.isAutoApprove();
    }

    @Override
    public void autoApprove(Joi joi, ServerTool tool, boolean on) throws Exception {
        requireOwn(joi);
        ToolMetadata metadata = metadataService.edit(ToolScope.JOI, joi, tool);
        if (!on && metadata.getId() == null) {
            return;
        }
        metadata.setAutoApprove(on ? tool.function() : null, tool.description());
        metadataService.save(metadata);
        note(joi, on ? "task.standing_approval" : "task.standing_approval_removed", on
                ? "The user approved " + tool.function() + " ahead of time: its tasks are approved without asking"
                : "The user takes back the approval of " + tool.function() + ": its tasks ask again");
        if (on) {
            for (JoiTask waiting : taskService.findByStatus(joi, List.of(JoiTaskStatus.WAITING))) {
                if (tool.function().equals(waiting.getTool())) {
                    waiting.setAutoApproved(true);
                    approve(saveTask(waiting));
                }
            }
        }
    }

    @Override
    public List<ServerTool> autoApproved(Joi joi) throws Exception {
        requireOwn(joi);
        List<ServerTool> tools = new ArrayList<>();
        for (ServerTool tool : ServerTool.values()) {
            if (autoApproves(joi, tool)) {
                tools.add(tool);
            }
        }
        return tools;
    }

    @Override
    public JoiTask approve(JoiTask task) throws Exception {
        JoiTask own = own(task);
        require(own, JoiTaskStatus.WAITING, "waits for an answer");
        own.setStatus(JoiTaskStatus.QUEUED);
        JoiTask saved = saveTask(own);
        note(joi(own), own.isAutoApproved() ? "task.auto_approved" : "task.approved", (own.isAutoApproved()
                ? "Approved automatically (the standing approval of " + own.getTool() + "): " : "Approved by the user: " + own.getTool() + " ")
                + quoted(own));
        drain(joi(own));
        return saved;
    }

    @Override
    public JoiTask approve(JoiTask task, boolean always) throws Exception {
        JoiTask approved = approve(task);
        if (always) {
            ServerTool tool = ServerTool.of(approved.getTool());
            if (tool != null) {
                // this one was the user's own answer; the standing approval takes the others that wait and the later ones
                autoApprove(joi(approved), tool, true);
            }
        }
        return approved;
    }

    @Override
    public JoiTask deny(JoiTask task, String reason) throws Exception {
        JoiTask own = own(task);
        require(own, JoiTaskStatus.WAITING, "waits for an answer");
        own.setStatus(JoiTaskStatus.CANCELLED);
        own.setError(StringUtils.defaultIfBlank(StringUtils.strip(reason), null));
        own.setFinishedAt(AppClock.now());
        JoiTask saved = saveTask(own);
        note(joi(own), "task.denied", "Denied by the user: " + own.getTool() + " " + quoted(own)
                + (own.getError() == null ? "" : " (" + own.getError() + ")"));
        report(joi(own), saved, "not allowed by the user", null, StringUtils.defaultIfBlank(StringUtils.strip(reason), null), "denied");
        return saved;
    }

    @Override
    public JoiTask cancel(JoiTask task) throws Exception {
        JoiTask own = own(task);
        if (own.getStatus().isFinished()) {
            throw new IllegalStateException("The task is over already");
        }
        if (own.getStatus() == JoiTaskStatus.RUNNING) {
            // the loop stops between its steps and ends the task
            stopping.add(own.getId());
            return own;
        }
        own.setStatus(JoiTaskStatus.CANCELLED);
        own.setFinishedAt(AppClock.now());
        JoiTask saved = saveTask(own);
        note(joi(own), "task.cancelled", "Cancelled by the user: " + own.getTool() + " " + quoted(own));
        report(joi(own), saved, "cancelled", null, null, "cancelled");
        return saved;
    }

    @Override
    public List<JoiTask> tasks(Joi joi) throws Exception {
        requireOwn(joi);
        List<JoiTask> all = new ArrayList<>(taskService.findByStatus(joi, EnumSet.allOf(JoiTaskStatus.class)));
        Collections.reverse(all);
        return all;
    }

    @Override
    public Registration watch(Joi joi, Runnable onChange) throws Exception {
        requireOwn(joi);
        List<Runnable> list = watchers.computeIfAbsent(joi.getId(), id -> new java.util.concurrent.CopyOnWriteArrayList<>());
        list.add(onChange);
        return () -> list.remove(onChange);
    }

    private static String quoted(JoiTask task) {
        return "\"" + StringUtils.defaultString(task.getTitle()) + "\"";
    }

    /** Her nodes are sent the task when it is made and each time its status changes ({@code task} frame): one that waits is theirs to ask about. */
    private void tellNodes(JoiTask task) {
        try {
            JoiTaskStatus status = task.getStatus();
            JoiTaskStatus before = status.isFinished() ? told.remove(task.getId()) : told.put(task.getId(), status);
            if (before != status) {
                nodeService.task(task.getJoi(), TaskInfos.of(task, null));
            }
        } catch (RuntimeException e) {
            log.warn("The nodes were not told of the task {}: {}", task.getUuid(), e.getMessage());
        }
    }

    /** Writes what happened into her conversation, not for the model ({@link ChatService#log}); a note that fails is not a failure of the task. */
    private void note(Joi joi, String kind, String text) {
        try {
            chatService.log(joi, kind, text);
        } catch (Exception e) {
            log.warn("The note {} was not written: {}", kind, e.getMessage());
        }
    }

    private JoiTask saveTask(JoiTask task) throws Exception {
        JoiTask saved = taskService.save(task);
        changed(saved);
        return saved;
    }

    /** Tells the watchers of the task's assistant that one of its tasks changed. */
    private void changed(JoiTask task) {
        tellNodes(task);
        List<Runnable> list = watchers.get(task.getJoi().getId());
        if (list != null) {
            for (Runnable watcher : list) {
                try {
                    watcher.run();
                } catch (RuntimeException e) {
                    log.warn("A watcher of the tasks failed: {}", e.getMessage());
                }
            }
        }
    }

    @Override
    public JoiTask find(String uuid) throws Exception {
        JoiTask task = taskService.findForUser(uuid);
        return task == null || task.isDeleted() ? null : task;
    }

    private JoiTask own(JoiTask task) throws Exception {
        JoiTask stored = task == null || task.getId() == null ? null : taskService.find(task.getId());
        if (stored == null || stored.isDeleted()) {
            throw new IllegalArgumentException("No such task");
        }
        requireOwn(stored.getJoi());
        return stored;
    }

    private Joi joi(JoiTask task) throws Exception {
        return joiService.find(task.getJoi().getId());
    }

    private void requireOwn(Joi joi) throws Exception {
        Joi stored = joi == null || joi.getId() == null ? null : joiService.find(joi.getId());
        if (stored == null || stored.isDeleted() || stored.getOwner() == null || !stored.getOwner().getId().equals(currentUser.getId())) {
            throw new SecurityException("Not an assistant of the user");
        }
    }

    private static void require(JoiTask task, JoiTaskStatus status, String what) {
        if (task.getStatus() != status) {
            throw new IllegalStateException("The task is " + task.getStatus().name().toLowerCase() + ", not one that " + what);
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // running
    // ----------------------------------------------------------------------------------------------------------------

    @Override
    public void drain(Joi joi) {
        Long joiId = joi.getId();
        User owner;
        try {
            // the user whose request this is: the turn's, or the one who approved
            owner = userService.find(currentUser.getId());
        } catch (Exception e) {
            log.warn("The tasks of the assistant were not looked at: {}", e.getMessage(), e);
            return;
        }
        // after the reply that is going on, and what waits behind it
        chatService.whenIdle(joi, () -> executor.submit(() -> inScope(owner, () -> next(joiId))));
    }

    private void inScope(User owner, Runnable work) {
        try (ApiRequestAttributes attributes = new ApiRequestAttributes(owner, new SessionPoint());
             InRequestScope _ = new InRequestScope(attributes)) {
            work.run();
        } catch (Throwable t) {
            log.error("A task could not run", t);
        }
    }

    /** Runs the oldest queued task of the assistant, then the next one, until none is left. */
    private void next(Long joiId) {
        if (!running.add(joiId)) {
            return;
        }
        try {
            Joi joi = joiService.find(joiId);
            if (joi == null || joi.isDeleted()) {
                return;
            }
            // a task that says it runs and is not one of ours is one the server was stopped in the middle of
            for (JoiTask stale : taskService.findByStatus(joi, List.of(JoiTaskStatus.RUNNING))) {
                if (!active.contains(stale.getId())) {
                    stale.setStatus(JoiTaskStatus.FAILED);
                    stale.setError("The server stopped while it ran");
                    stale.setFinishedAt(AppClock.now());
                    report(joi, saveTask(stale), "failed", null, stale.getError(), "failed");
                }
            }
            for (JoiTask task = first(joi); task != null; task = first(joi)) {
                JoiTask current = task;
                JoiJobs.run(JoiJobs.WEB_TASK, joiService.name(joi) + " · " + task.getTool(), JobCallType.LLM, () -> execute(joi, current));
            }
        } catch (Exception e) {
            log.warn("The tasks of the assistant failed: {}", e.getMessage(), e);
        } finally {
            running.remove(joiId);
        }
        // a task queued after the loop looked for the last time, while this one still ran, was left by its own drain
        try {
            Joi joi = joiService.find(joiId);
            if (joi != null && !joi.isDeleted() && first(joi) != null) {
                next(joiId);
            }
        } catch (Exception e) {
            log.warn("The tasks of the assistant were not looked at: {}", e.getMessage(), e);
        }
    }

    private JoiTask first(Joi joi) throws Exception {
        List<JoiTask> queued = taskService.findQueued(joi);
        return queued.isEmpty() ? null : queued.getFirst();
    }

    private void execute(Joi joi, JoiTask task) {
        Long id = task.getId();
        active.add(id);
        String status = "failed";
        String result = null;
        String error = null;
        try {
            task.setStatus(JoiTaskStatus.RUNNING);
            task.setStartedAt(AppClock.now());
            task.setError(null);
            saveTask(task);

            AI ai = model(joi);
            if (ai == null) {
                throw new IllegalStateException("There is no model to do it: choose a deep reasoning or chat model");
            }
            ServerTool tool = ServerTool.of(task.getTool());
            boolean withSearch = tool == ServerTool.WEB_SEARCH;
            Search search = settingsService.search(joi);
            Pages pages = settingsService.pages(joi);
            if (withSearch && search.config().usable().isEmpty()) {
                throw new IllegalStateException(search.config().sources().isEmpty() ? "No search source is set up (Administration or User Settings, Web tools)"
                        : "No search source may be used now (they are off, or only local ones are allowed)");
            }
            String instruction = personaService.render(joi, JoiPromptField.WEB_TASK_PROMPT, instruction(joi, task, withSearch, search));
            AtomicInteger ordinal = new AtomicInteger();
            WebResearch research = new WebResearch(inferenceServices.forAIOrThrow(ai), search, pages, withSearch, executor,
                    () -> stopping.contains(id), (role, content, reasoning, calls, toolCallId, usage) -> {
                try {
                    JoiTaskMessage message = new JoiTaskMessage();
                    message.setRole(role);
                    message.setOrdinal(ordinal.incrementAndGet());
                    message.setContent(content);
                    message.setReasoning(reasoning);
                    message.setToolCallId(toolCallId);
                    message.setModelUsed(role == TaskMessageRole.ASSISTANT ? ai.getName() : null);
                    if (calls != null) {
                        JsonArray array = new JsonArray();
                        calls.forEach(call -> {
                            JsonObject object = new JsonObject();
                            object.addProperty("id", call.getId());
                            object.addProperty("name", call.getName());
                            object.addProperty("arguments", call.getArguments());
                            array.add(object);
                        });
                        message.setToolCalls(array.toString());
                    }
                    if (usage != null) {
                        message.setPromptTokens(usage.promptTokens());
                        message.setTokenCount(usage.completionTokens());
                    }
                    messageService.append(task, message);
                    changed(task);
                } catch (Exception e) {
                    log.warn("The transcript of the task {} lost a step: {}", id, e.getMessage());
                }
            });
            research.withFiles((name, content) -> workspace.write(joi, name, content).name());
            result = research.run(ai, instruction);
            result = withFiles(joi, result, research.written());
            status = "finished";
        } catch (CancellationException e) {
            status = "cancelled";
        } catch (Exception e) {
            log.info("The task {} failed: {}", id, e.getMessage());
            error = StringUtils.abbreviate(StringUtils.defaultIfBlank(ExceptionUtils.getRootCauseMessage(e), e.toString()), 600);
        } finally {
            active.remove(id);
            stopping.remove(id);
        }
        try {
            JoiTask stored = taskService.find(id);
            stored.setStatus(switch (status) {
                case "finished" -> JoiTaskStatus.DONE;
                case "cancelled" -> JoiTaskStatus.CANCELLED;
                default -> JoiTaskStatus.FAILED;
            });
            stored.setResult(result);
            stored.setError(error);
            stored.setFinishedAt(AppClock.now());
            JoiTask ended = saveTask(stored);
            if ("cancelled".equals(status)) {
                note(joi, "task.cancelled", "Cancelled by the user: " + ended.getTool() + " " + quoted(ended));
            }
            report(joi, ended, status, result, error, status.equals("finished") ? "done" : status);
        } catch (Exception e) {
            log.error("The end of the task {} was not kept", id, e);
        }
    }

    /** What the task found, and the files it wrote (she hands them to the user with {@code offer_file_to_clipboard}). */
    private String withFiles(Joi joi, String result, List<String> written) {
        if (written.isEmpty()) {
            return result;
        }
        StringBuilder out = new StringBuilder(result).append("\n\nFiles in your workspace, written by this task (").append(WorkspaceTools.OFFER)
                .append(" offers one to the user's clipboard):");
        for (String name : written) {
            out.append("\n- ").append(name);
        }
        return out.toString();
    }

    private WebTaskTemplateData instruction(Joi joi, JoiTask task, boolean withSearch, Search search) throws Exception {
        WebTaskTemplateData data = new WebTaskTemplateData();
        data.names(joiService.name(joi), personaService.userName());
        data.setToday(LocalDate.now().toString());
        data.setQuestion(task.getInstructions());
        data.setUrl(task.getUrl());
        if (withSearch) {
            data.setSources(search.config().usable().stream().map(WebTaskServiceImpl::describe).collect(Collectors.joining("\n")));
        }
        data.setMaxSearches(String.valueOf(search.config().maxSearches()));
        data.setMaxPages(String.valueOf(search.config().maxPages()));
        return data;
    }

    private static String describe(SearchSource source) {
        String sites = source.sites().isEmpty() ? "" : ", only " + String.join(", ", source.sites());
        return "- " + source.name() + " (" + source.type().name().toLowerCase() + sites + ")";
    }

    /** The stronger model of the assistant: hers, else the user's, else the chat model. */
    private AI model(Joi joi) throws Exception {
        List<AI> ais = aiService.findAllForUser();
        for (Long id : new Long[]{joi.getReasoningModel(), personaService.getUserSetting().getReasoningModel()}) {
            AI ai = id == null ? null : ais.stream().filter(a -> id.equals(a.getId())).findFirst().orElse(null);
            if (ai != null) {
                return ai;
            }
        }
        return chatService.chatModel(joi);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // telling her
    // ----------------------------------------------------------------------------------------------------------------

    /** She is told, in one event: through a node that is connected, else when one connects. */
    private void report(Joi joi, JoiTask task, String statusWords, String result, String reason, String status) {
        try {
            TaskEventTemplateData data = new TaskEventTemplateData();
            data.names(joiService.name(joi), personaService.userName());
            data.setTitle(task.getTitle());
            data.setStatus(statusWords);
            data.setResult(result);
            // an error is what it says; a refusal carries the user's words
            if ("denied".equals(status)) {
                data.setReason(reason);
            } else {
                data.setError(reason);
            }
            String text = personaService.render(joi, JoiPromptField.TASK_EVENT, data).strip();
            if (!nodeService.tell(joi, text, NodeServiceImpl.TASK_ENDED)) {
                JsonObject payload = new JsonObject();
                payload.addProperty("task", task.getUuid());
                payload.addProperty("status", status);
                QueuedEvent event = new QueuedEvent();
                event.setJoi(joi);
                event.setKind(JoiEvents.TASK_ENDED);
                event.setOccurredAt(AppClock.now());
                event.setText(text);
                event.setPayload(payload.toString());
                queuedEventService.save(event);
            }
        } catch (Exception e) {
            log.warn("She was not told that the task {} ended: {}", task.getId(), e.getMessage(), e);
        }
    }
}
