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
 * {@code play_animation}, offered to every turn of hers: she chooses one of the animations her body plays (all the
 * user's, or the ones chosen in the body's dialog, {@link BodyService#animations}), by its name, and her nodes play it
 * once on top of what she is doing ({@code animation} frame). The function lists them with their descriptions, so she
 * picks by what they show; her prompt asks her to use it often. It is her choice: no consent is asked.
 */
public class AnimationTools implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AnimationTools.class);

    /** The name of the function. */
    public static final String PLAY_ANIMATION = "play_animation";

    @Autowired
    private ChatService chatService;

    @Autowired
    private BodyService bodyService;

    @Autowired
    private NodeService nodeService;

    private final ObjectMapper jackson = new ObjectMapper();
    private Registration registration;

    @Override
    public void afterPropertiesSet() {
        registration = chatService.addEventListener(TurnEvents.COLLECT_TOOLS, (event, chain) -> {
            try {
                Joi joi = event.getJoi();
                Body body = bodyService.active(joi);
                List<AnimationPack> animations = body == null ? List.of() : bodyService.animations(body);
                if (!animations.isEmpty()) {
                    event.setFunctions(event.getFunctions().and(FunctionSet.bind(List.of(playAnimation(joi, animations)), this)));
                }
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

    private FunctionDefinition playAnimation(Joi joi, List<AnimationPack> animations) {
        Map<String, AnimationPack> byName = new LinkedHashMap<>();
        for (AnimationPack pack : animations) {
            byName.putIfAbsent(pack.getName(), pack);
        }
        ObjectNode schema = jackson.createObjectNode();
        schema.put("type", "object");
        var names = schema.putObject("properties").putObject("animation").put("type", "string")
                .put("description", "The animation to play.").putArray("enum");
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
                + "most of your replies, before or while you speak; it plays once and you go back to what you were doing. "
                + "Your animations:" + list, schema, (continuation, arguments) -> {
            Object value = arguments == null ? null : arguments.get("animation");
            AnimationPack pack = value instanceof String name ? byName.get(name) : null;
            if (pack == null) {
                continuation.fail("You have no such animation. Your animations: " + String.join(", ", byName.keySet()));
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
}
