package com.github.enerccio.joi.domain.extensions;

import com.github.enerccio.joi.domain.chat.ChatService;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent;
import com.github.enerccio.joi.domain.chat.turn.TurnEvent;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.service.ToolPermissionService;
import com.github.enerccio.joi.domain.tools.ServerTool;
import com.github.enerccio.joi.domain.tools.ToolPermission;
import com.github.enerccio.joi.extensions.ExtensionPart;
import com.github.enerccio.joi.extensions.ExtensionSettings;
import com.github.enerccio.joi.extensions.ExtensionTask;
import com.github.enerccio.joi.extensions.ExtensionTool;
import com.github.enerccio.joi.extensions.ToolProvider;
import com.github.enerccio.marginalia.domain.service.function.FunctionDefinition;
import com.github.enerccio.marginalia.domain.service.function.FunctionSet;
import com.github.enerccio.marginalia.extensions.ExtensionRegistrations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What Joi's extensions added, by extension (the symbolic name of its bundle): the functions offered to her turns, the tools under the
 * chain of permissions, the functions that run as tasks (offered by {@link ExtensionTasks}), the settings they ask for, the tabs of the
 * workspace and the listeners of the turn events. {@link #unregisterAll} is called by the platform's {@code OsgiService} when an
 * extension is unloaded, so what the extension forgot goes with it. The functions are offered by one listener of {@code COLLECT_TOOLS} that
 * this bean keeps for as long as it lives.
 */
public class JoiExtensions implements ExtensionRegistrations, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(JoiExtensions.class);

    @Autowired
    private ChatService chatService;

    @Autowired
    private ToolPermissionService permissionService;

    private final Map<String, List<ToolProvider>> tools = new ConcurrentHashMap<>();
    /** The tools under the chain of permissions, by function name (unique over all extensions). */
    private final Map<String, Declared> declared = new ConcurrentHashMap<>();
    /** The functions that run as tasks, by function name. */
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final Map<String, ExtensionSettings> settings = new ConcurrentHashMap<>();
    private final Map<String, List<ExtensionPart>> parts = new ConcurrentHashMap<>();
    private final Map<String, List<TurnControllerEvent.Registration>> listeners = new ConcurrentHashMap<>();
    private TurnControllerEvent.Registration collecting;

    @Override
    public void afterPropertiesSet() {
        collecting = chatService.addEventListener(TurnEvents.COLLECT_TOOLS, this::collect);
    }

    @Override
    public void destroy() {
        if (collecting != null) {
            collecting.unregister();
        }
        listeners.values().forEach(list -> list.forEach(TurnControllerEvent.Registration::unregister));
    }

    public void offerTools(String owner, ToolProvider provider) {
        tools.computeIfAbsent(owner, _ -> new CopyOnWriteArrayList<>()).add(provider);
    }

    /** A tool of an extension under the chain of permissions. */
    public record Declared(String owner, ExtensionTool tool) {
    }

    /** A function of an extension that runs as a task. */
    public record Task(String owner, ExtensionTask task) {
    }

    public synchronized void declareTool(String owner, ExtensionTool tool) {
        if (ServerTool.of(tool.function()) != null || declared.containsKey(tool.function())) {
            throw new IllegalArgumentException("The tool " + tool.function() + " is declared already");
        }
        declared.put(tool.function(), new Declared(owner, tool));
    }

    public synchronized void offerTask(String owner, ExtensionTask task) {
        declareTool(owner, task.tool());
        tasks.put(task.tool().function(), new Task(owner, task));
    }

    public void declareSettings(String owner, ExtensionSettings extensionSettings) {
        settings.put(owner, extensionSettings);
    }

    /** The settings the extension asks for, null for none. */
    public ExtensionSettings settings(String owner) {
        return settings.get(owner);
    }

    /** The extensions that ask for settings, by name, sorted by their caption. */
    public List<Map.Entry<String, ExtensionSettings>> allSettings() {
        return settings.entrySet().stream().sorted(Map.Entry.comparingByValue(java.util.Comparator.comparing(ExtensionSettings::caption))).toList();
    }

    /** The tools of the extensions under the chain of permissions, by label. */
    public List<Declared> declaredTools() {
        return declared.values().stream().sorted(java.util.Comparator.comparing(d -> d.tool().label())).toList();
    }

    /** The tool of an extension of that function name, null for none (or its extension is not loaded). */
    public ExtensionTool tool(String function) {
        Declared found = function == null ? null : declared.get(function);
        return found == null ? null : found.tool();
    }

    /** The function of that name that runs as a task, null for none. */
    public Task task(String function) {
        return function == null ? null : tasks.get(function);
    }

    /** The functions that run as tasks. */
    public List<Task> tasks() {
        return List.copyOf(tasks.values());
    }

    public void addPart(String owner, ExtensionPart part) {
        parts.computeIfAbsent(owner, _ -> new CopyOnWriteArrayList<>()).add(part);
    }

    public void onTurnEvent(String owner, TurnEvents event, TurnEvent listener) {
        listeners.computeIfAbsent(owner, _ -> new CopyOnWriteArrayList<>()).add(chatService.addEventListener(event, listener));
    }

    /** The tabs the loaded extensions add to the workspace, in the order they were added. */
    public List<Part> parts() {
        List<Part> all = new ArrayList<>();
        parts.forEach((owner, list) -> list.forEach(part -> all.add(new Part(owner, part))));
        return all;
    }

    /** A tab of an extension. */
    public record Part(String owner, ExtensionPart part) {
        /** The key in the stored state of the workspace: stable, and unique among the extensions. */
        public String key() {
            return "ext:" + owner + ":" + part.key();
        }
    }

    @Override
    public void unregisterAll(String owner) {
        tools.remove(owner);
        parts.remove(owner);
        declared.values().removeIf(d -> d.owner().equals(owner));
        tasks.values().removeIf(t -> t.owner().equals(owner));
        settings.remove(owner);
        List<TurnControllerEvent.Registration> own = listeners.remove(owner);
        if (own != null) {
            own.forEach(TurnControllerEvent.Registration::unregister);
        }
    }

    /**
     * Whether a function she calls in her turn may be offered: one that is not declared always is; a declared one only when the chain
     * allows it (ask leaves it out: nobody can be asked in the middle of a turn).
     */
    private boolean allowed(com.github.enerccio.joi.domain.model.impl.Joi joi, String function) throws Exception {
        Declared tool = declared.get(function);
        return tool == null || permissionService.effective(joi, tool.tool()) == ToolPermission.ALLOW;
    }

    /** At the start of a turn: the functions of every extension that offers some; one that fails is left out of the turn. */
    private void collect(TurnControllerEvent event, TurnControllerEvent.EventChain chain) {
        try {
            FunctionSet functions = event.getFunctions();
            for (Map.Entry<String, List<ToolProvider>> entry : tools.entrySet()) {
                for (ToolProvider provider : entry.getValue()) {
                    try {
                        List<FunctionDefinition> offered = new ArrayList<>();
                        for (FunctionDefinition definition : provider.tools(event.getJoi())) {
                            if (functions.contains(definition.getName())) {
                                log.warn("The extension {} offers {}, which is taken already: not offered", entry.getKey(), definition.getName());
                            } else if (!allowed(event.getJoi(), definition.getName())) {
                                log.debug("The chain of permissions leaves {} out of the turn", definition.getName());
                            } else {
                                offered.add(definition);
                            }
                        }
                        if (!offered.isEmpty()) {
                            functions = functions.and(FunctionSet.bind(offered, provider));
                        }
                    } catch (Throwable t) {
                        log.warn("The extension {} could not offer its functions: {}", entry.getKey(), t.getMessage(), t);
                    }
                }
            }
            event.setFunctions(functions);
        } finally {
            chain.next();
        }
    }
}
