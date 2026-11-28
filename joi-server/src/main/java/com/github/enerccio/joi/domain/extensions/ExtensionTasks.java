package com.github.enerccio.joi.domain.extensions;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.enerccio.joi.domain.chat.ChatService;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.collections.JoiTaskStatus;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.JoiTask;
import com.github.enerccio.joi.domain.service.ToolPermissionService;
import com.github.enerccio.joi.domain.service.WebTaskService;
import com.github.enerccio.joi.domain.service.WebTaskService.NotAllowedException;
import com.github.enerccio.joi.domain.tools.ToolPermission;
import com.github.enerccio.joi.extensions.ExtensionTask;
import com.github.enerccio.marginalia.domain.service.function.FunctionContinuation;
import com.github.enerccio.marginalia.domain.service.function.FunctionDefinition;
import com.github.enerccio.marginalia.domain.service.function.FunctionSet;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Offers the functions of the extensions that run as tasks ({@link ExtensionTask}) to her turns, as {@code WebTools} offers
 * {@code fetch_page}: a function whose tool the chain denies is not offered; her call makes a task ({@link WebTaskService#submit}) and
 * answers at once, so she says it is on its way. The task runs later ({@link ExtensionTaskRunner}).
 */
public class ExtensionTasks implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ExtensionTasks.class);

    @Autowired
    private ChatService chatService;

    @Autowired
    private JoiExtensions extensions;

    @Autowired
    private WebTaskService taskService;

    @Autowired
    private ToolPermissionService permissionService;

    private Registration registration;

    @Override
    public void afterPropertiesSet() {
        registration = chatService.addEventListener(TurnEvents.COLLECT_TOOLS, this::collect);
    }

    @Override
    public void destroy() {
        if (registration != null) {
            registration.unregister();
        }
    }

    private void collect(TurnControllerEvent event, TurnControllerEvent.EventChain chain) {
        try {
            Joi joi = event.getJoi();
            FunctionSet functions = event.getFunctions();
            for (JoiExtensions.Task registered : extensions.tasks()) {
                ExtensionTask task = registered.task();
                String name = task.tool().function();
                try {
                    if (functions.contains(name) || permissionService.effective(joi, task.tool()) == ToolPermission.DENY || !task.offered(joi)) {
                        continue;
                    }
                    functions = functions.and(FunctionSet.bind(List.of(definition(joi, task)), task));
                } catch (Throwable t) {
                    log.warn("The task {} of the extension {} was not offered: {}", name, registered.owner(), t.getMessage(), t);
                }
            }
            event.setFunctions(functions);
        } finally {
            chain.next();
        }
    }

    private FunctionDefinition definition(Joi joi, ExtensionTask task) {
        ObjectNode schema = task.parameters();
        return FunctionDefinition.dynamic(task.tool().function(), StringUtils.strip(task.description()) + " A stronger model does it in the "
                + "background after this reply and its answer comes back to you later as an event: say you are on it and do not guess "
                + "the answer meanwhile. It may need the user's approval first.", schema == null ? null : schema.deepCopy(),
                (continuation, arguments) -> start(joi, task, arguments == null ? new LinkedHashMap<>() : new LinkedHashMap<>(arguments), continuation));
    }

    private void start(Joi joi, ExtensionTask task, Map<String, Object> arguments, FunctionContinuation<Object> continuation) {
        String problem = task.check(arguments);
        if (problem != null) {
            continuation.fail(problem);
            return;
        }
        try {
            String json = ExtensionTaskRunner.json(arguments);
            String function = task.tool().function();
            // the same thing asked again while it is on its way is not asked twice
            for (JoiTask existing : taskService.tasks(joi)) {
                if (!existing.getStatus().isFinished() && function.equals(existing.getTool()) && Objects.equals(json, existing.getArguments())) {
                    continuation.complete(existing.getStatus() == JoiTaskStatus.WAITING
                            ? "That is already asked and waits for the user's approval. You will be told when they answer."
                            : "That is already on its way. You will be told what it found.");
                    return;
                }
            }
            JoiTask made = taskService.submit(joi, task.tool(), task.question(arguments), null, task.title(arguments), json, null);
            continuation.complete(made.getStatus() == JoiTaskStatus.WAITING
                    ? "Not started yet: the user has to approve it first (they were asked, in your dialog). Tell them you are waiting "
                    + "for their go-ahead; you will be told when they answer, and what it found."
                    : "Started. It runs after this reply; you will be told what it found. Tell the user you are on it.");
        } catch (NotAllowedException e) {
            continuation.fail("This is not allowed: the user or the administrator turned it off. Do not try again; say so if it matters.");
        } catch (Exception e) {
            log.warn("The task of {} could not be made: {}", task.tool().function(), e.getMessage(), e);
            continuation.fail("It failed: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
        }
    }
}
