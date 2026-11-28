package com.github.enerccio.joi.domain.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.BodyService;
import com.github.enerccio.joi.domain.service.NodeService;
import com.github.enerccio.joi.domain.service.StanceService;
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

/**
 * Her functions of her animations, offered to every turn of hers. {@code play_animation}: she chooses one of the
 * animations her body plays (all the user's, or the ones chosen in the body's dialog, {@link BodyService#animations}),
 * by its name, and her nodes play it once on top of what she is doing ({@code animation} frame). The function lists
 * them with their descriptions, so she picks by what they show; her prompt asks her to use it often.
 * {@code set_stance} and {@code end_stance}: she holds one of them, looped in place of her idle and talking animations,
 * until she ends it or takes another ({@link StanceService}, the {@code stance} frame): for what lasts, as dancing or
 * sitting. It is her choice: no consent is asked.
 */
public class AnimationTools implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AnimationTools.class);

    /** The name of the function. */
    public static final String PLAY_ANIMATION = "play_animation";
    /** The name of the function that takes a stance. */
    public static final String SET_STANCE = "set_stance";
    /** The name of the function that ends it. */
    public static final String END_STANCE = "end_stance";

    @Autowired
    private ChatService chatService;

    @Autowired
    private BodyService bodyService;

    @Autowired
    private NodeService nodeService;

    @Autowired
    private StanceService stanceService;

    private final ObjectMapper jackson = new ObjectMapper();
    private Registration registration;

    @Override
    public void afterPropertiesSet() {
        registration = chatService.addEventListener(TurnEvents.COLLECT_TOOLS, (event, chain) -> {
            try {
                Joi joi = event.getJoi();
                Body body = bodyService.active(joi, event.getInput() == null ? null : event.getInput().profile());
                List<AnimationPack> animations = body == null ? List.of() : bodyService.animations(body);
                // always offered, even with no animation: the model renders the tools before the history, so a list that comes and goes
                // makes the server's cache of the prompt start over (the function says so when she has none)
                event.setFunctions(event.getFunctions().and(FunctionSet.bind(List.of(playAnimation(joi, animations),
                        setStance(joi, animations), endStance(joi)), this)));
            } catch (Exception e) {
                // no function this turn: she can still talk
                log.warn("The animations of her body could not be offered: {}", e.getMessage(), e);
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

    private static Map<String, AnimationPack> byName(List<AnimationPack> animations) {
        Map<String, AnimationPack> byName = new LinkedHashMap<>();
        for (AnimationPack pack : animations) {
            byName.putIfAbsent(pack.getName(), pack);
        }
        return byName;
    }

    private FunctionDefinition playAnimation(Joi joi, List<AnimationPack> animations) {
        Map<String, AnimationPack> byName = byName(animations);
        ObjectNode schema = jackson.createObjectNode();
        schema.put("type", "object");
        var property = schema.putObject("properties").putObject("animation").put("type", "string")
                .put("description", "The animation to play.");
        // an empty enum is not a valid schema: with no animation the value is any string, and the call fails
        var names = byName.isEmpty() ? null : property.putArray("enum");
        StringBuilder list = new StringBuilder();
        byName.forEach((name, pack) -> {
            names.add(name);
            list.append("\n- ").append(name);
            if (StringUtils.isNotBlank(pack.getDescription())) {
                list.append(": ").append(pack.getDescription().strip().replaceAll("\\s*\\R\\s*", " "));
            }
        });
        schema.putArray("required").add("animation");
        return FunctionDefinition.dynamic(PLAY_ANIMATION, "Plays one of your animations now: a gesture or a movement of "
                + "your body that goes with what you say or feel (a wave, a nod, a shrug, a laugh). Use it often, in "
                + "most of your replies, before or while you speak; it plays once and you go back to what you were doing "
                + "(your stance, when you hold one). Not for something that lasts, as dancing, sitting or a pose you hold: "
                + "that is set_stance. Your animations:" + list, schema, (continuation, arguments) -> {
            Object value = arguments == null ? null : arguments.get("animation");
            AnimationPack pack = value instanceof String name ? byName.get(name) : null;
            if (pack == null) {
                continuation.fail(byName.isEmpty() ? "You have no animations now."
                        : "You have no such animation. Your animations: " + String.join(", ", byName.keySet()));
                return;
            }
            try {
                int nodes = nodeService.animate(joi, pack.getUuid(), pack.getName(), false);
                continuation.complete(nodes == 0 ? "You are not shown anywhere now, nobody saw it."
                        : "You played " + pack.getName() + ".");
            } catch (Exception e) {
                log.warn("She could not play {}: {}", pack.getName(), e.getMessage(), e);
                continuation.fail("It failed: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            }
        });
    }

    private FunctionDefinition setStance(Joi joi, List<AnimationPack> animations) {
        Map<String, AnimationPack> byName = byName(animations);
        ObjectNode schema = jackson.createObjectNode();
        schema.put("type", "object");
        var property = schema.putObject("properties").putObject("animation").put("type", "string")
                .put("description", "The animation to hold.");
        if (!byName.isEmpty()) {
            byName.keySet().forEach(property.putArray("enum")::add);
        }
        schema.putArray("required").add("animation");
        return FunctionDefinition.dynamic(SET_STANCE, "Takes a stance: one of your animations (those of play_animation) "
                + "loops and stays, in place of how you stand and talk, through your replies, until you take another one "
                + "or end it with end_stance. Use it for something that lasts: dancing, sitting, a pose you hold, a "
                + "workout; not play_animation. Your words, your face and your gestures go on over it. End it when you "
                + "decide to stop or when you are asked to stop.", schema, (continuation, arguments) -> {
            Object value = arguments == null ? null : arguments.get("animation");
            AnimationPack pack = value instanceof String name ? byName.get(name) : null;
            if (pack == null) {
                continuation.fail(byName.isEmpty() ? "You have no animations now."
                        : "You have no such animation. Your animations: " + String.join(", ", byName.keySet()));
                return;
            }
            try {
                boolean changed = stanceService.take(joi, pack);
                continuation.complete(!changed ? "You already hold the stance " + pack.getName() + "."
                        : "You hold the stance " + pack.getName() + " now, until you end it with end_stance.");
            } catch (Exception e) {
                log.warn("She could not take the stance {}: {}", pack.getName(), e.getMessage(), e);
                continuation.fail("It failed: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            }
        });
    }

    private FunctionDefinition endStance(Joi joi) {
        ObjectNode schema = jackson.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return FunctionDefinition.dynamic(END_STANCE, "Ends your stance (set_stance): you stand and talk as usual again. "
                + "Use it when you decide to stop or when you are asked to stop.", schema, (continuation, arguments) -> {
            try {
                AnimationPack ended = stanceService.end(joi, false);
                continuation.complete(ended == null ? "You hold no stance." : "Your stance " + ended.getName() + " ended.");
            } catch (Exception e) {
                log.warn("She could not end her stance: {}", e.getMessage(), e);
                continuation.fail("It failed: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            }
        });
    }
}
