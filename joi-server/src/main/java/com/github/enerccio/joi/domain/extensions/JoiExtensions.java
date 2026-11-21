package com.github.enerccio.joi.domain.extensions;

import com.github.enerccio.joi.domain.chat.ChatService;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent;
import com.github.enerccio.joi.domain.chat.turn.TurnEvent;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.extensions.ExtensionPart;
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
 * What Joi's extensions added, by extension (the symbolic name of its bundle): the functions offered to her turns, the tabs of the
 * workspace and the listeners of the turn events. {@link #unregisterAll} is called by the platform's {@code OsgiService} when an
 * extension is unloaded, so what the extension forgot goes with it. The functions are offered by one listener of {@code COLLECT_TOOLS} that
 * this bean keeps for as long as it lives.
 */
public class JoiExtensions implements ExtensionRegistrations, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(JoiExtensions.class);

    @Autowired
    private ChatService chatService;

    private final Map<String, List<ToolProvider>> tools = new ConcurrentHashMap<>();
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
        List<TurnControllerEvent.Registration> own = listeners.remove(owner);
        if (own != null) {
            own.forEach(TurnControllerEvent.Registration::unregister);
        }
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
