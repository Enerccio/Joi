package com.github.enerccio.joi.domain.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.collections.JoiTaskStatus;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.JoiTask;
import com.github.enerccio.joi.domain.service.ToolPermissionService;
import com.github.enerccio.joi.domain.service.WebTaskService;
import com.github.enerccio.joi.domain.service.WebToolSettingsService;
import com.github.enerccio.joi.domain.service.WebTaskService.NotAllowedException;
import com.github.enerccio.joi.domain.tools.ServerTool;
import com.github.enerccio.joi.domain.tools.ToolPermission;
import com.github.enerccio.marginalia.domain.service.function.FunctionDefinition;
import com.github.enerccio.marginalia.domain.service.function.FunctionSet;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Her way to the web: {@code web_search} and {@code fetch_page}. Neither searches or reads: each makes a task for the
 * stronger model ({@link WebTaskService}) and answers at once, so she can say she is on it; what the task finds comes
 * back as an event. Whether the task runs, waits for the user or is not allowed is the tool's permission along the chain
 * of the assistant, the user and the administrator, read when she calls. A tool that is denied is not offered, nor is a
 * search when no source may be used.
 */
public class WebTools implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(WebTools.class);

    @Autowired
    private ChatService chatService;

    @Autowired
    private WebTaskService taskService;

    @Autowired
    private ToolPermissionService permissionService;

    @Autowired
    private WebToolSettingsService settingsService;

    private final ObjectMapper jackson = new ObjectMapper();
    private Registration registration;

    @Override
    public void afterPropertiesSet() {
        registration = chatService.addEventListener(TurnEvents.COLLECT_TOOLS, (event, chain) -> {
            try {
                Joi joi = event.getJoi();
                List<FunctionDefinition> offered = new java.util.ArrayList<>();
                // a search with nowhere to search could only fail: it is offered when a source may be used
                if (permissionService.effective(joi, ServerTool.WEB_SEARCH) != ToolPermission.DENY
                        && !settingsService.search(joi).config().usable().isEmpty()) {
                    offered.add(webSearch(joi));
                }
                if (permissionService.effective(joi, ServerTool.FETCH_PAGE) != ToolPermission.DENY) {
                    offered.add(fetchPage(joi));
                }
                if (!offered.isEmpty()) {
                    event.setFunctions(event.getFunctions().and(FunctionSet.bind(offered, this)));
                }
            } catch (Exception e) {
                // no function this turn: she can still talk
                log.warn("The functions of the web could not be offered: {}", e.getMessage(), e);
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

    private FunctionDefinition webSearch(Joi joi) {
        ObjectNode schema = jackson.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("question").put("type", "string").put("description",
                "What you want to know, as a whole question with what you need from it ('what time does the Prague "
                        + "Airport train run on Sundays', not 'train'). One thing at a time.");
        schema.putArray("required").add("question");
        return FunctionDefinition.dynamic(ServerTool.WEB_SEARCH.function(), "Looks something up on the web. A stronger model does it "
                + "in the background after this reply and its answer comes back to you later as an event, so say you are looking into it "
                + "and do not guess the answer meanwhile. Use it for what changes or what you do not know (news, prices, schedules, "
                + "documentation), not for what you can answer yourself. It may need the user's approval first.", schema,
                (continuation, arguments) -> start(joi, ServerTool.WEB_SEARCH, text(arguments, "question"), null, continuation));
    }

    private FunctionDefinition fetchPage(Joi joi) {
        ObjectNode schema = jackson.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("url").put("type", "string").put("description", "The address of the page, as the user gave it.");
        properties.putObject("question").put("type", "string").put("description",
                "What to find in the page or how to sum it up; the gist of it when left out.");
        schema.putArray("required").add("url");
        return FunctionDefinition.dynamic(ServerTool.FETCH_PAGE.function(), "Reads a web page the user gave you (an address) and tells what "
                + "is in it. A stronger model does it in the background after this reply and its answer comes back to you later as an "
                + "event: say you are reading it and do not guess what it says. It may need the user's approval first.", schema,
                (continuation, arguments) -> start(joi, ServerTool.FETCH_PAGE, text(arguments, "question"), text(arguments, "url"), continuation));
    }

    private void start(Joi joi, ServerTool tool, String question, String url, com.github.enerccio.marginalia.domain.service.function.FunctionContinuation<Object> continuation) {
        if (tool == ServerTool.WEB_SEARCH && question == null) {
            continuation.fail("Give the question");
            return;
        }
        if (tool == ServerTool.FETCH_PAGE && url == null) {
            continuation.fail("Give the address of the page");
            return;
        }
        try {
            // the same thing asked again while it is on its way is not asked twice
            for (JoiTask task : taskService.tasks(joi)) {
                if (!task.getStatus().isFinished() && tool.function().equals(task.getTool())
                        && Objects.equals(StringUtils.defaultString(question), StringUtils.defaultString(task.getInstructions()))
                        && Objects.equals(url, task.getUrl())) {
                    continuation.complete(task.getStatus() == JoiTaskStatus.WAITING
                            ? "That is already asked and waits for the user's approval. You will be told when they answer."
                            : "That is already on its way. You will be told what it found.");
                    return;
                }
            }
            JoiTask task = taskService.submit(joi, tool, question, url, null);
            continuation.complete(task.getStatus() == JoiTaskStatus.WAITING
                    ? "Not started yet: the user has to approve it first (they were asked, in your dialog). Tell them you are waiting "
                    + "for their go-ahead; you will be told when they answer, and what it found."
                    : "Started. It runs after this reply; you will be told what it found. Tell the user you are looking into it.");
        } catch (NotAllowedException e) {
            continuation.fail("This is not allowed: the user or the administrator turned it off. Do not try again; say so if it matters.");
        } catch (Exception e) {
            log.warn("The task of {} could not be made: {}", tool.function(), e.getMessage(), e);
            continuation.fail("It failed: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    private static String text(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        return value instanceof String string && !string.isBlank() ? string.strip() : null;
    }
}
