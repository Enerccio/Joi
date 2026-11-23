package com.github.enerccio.joi.domain.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.persona.Mood;
import com.github.enerccio.joi.domain.service.PersonaService;
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
 * {@code set_mood}, offered to every turn of hers: she changes her own mood now (it stays until the next day's roll), by its
 * name, when the conversation really moves her. The prompt takes the new mood from her next turn, her nodes show it on her
 * face at once ({@link ChatService#changeMood}), and a note in the conversation says what happened. It is her own choice:
 * no consent is asked, and no event, as she knows what she did.
 */
public class MoodTools implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MoodTools.class);

    /** The name of the function. */
    public static final String SET_MOOD = "set_mood";

    @Autowired
    private ChatService chatService;

    @Autowired
    private PersonaService personaService;

    private final ObjectMapper jackson = new ObjectMapper();
    private Registration registration;

    @Override
    public void afterPropertiesSet() {
        registration = chatService.addEventListener(TurnEvents.COLLECT_TOOLS, (event, chain) -> {
            try {
                Joi joi = event.getJoi();
                List<Mood> moods = personaService.moods();
                // always offered (see AnimationTools): the tools come before the history in the prompt
                event.setFunctions(event.getFunctions().and(FunctionSet.bind(List.of(setMood(joi, moods)), this)));
            } catch (Exception e) {
                // no function this turn: she can still talk
                log.warn("Her moods could not be offered: {}", e.getMessage(), e);
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

    private FunctionDefinition setMood(Joi joi, List<Mood> moods) {
        Map<String, Mood> byName = new LinkedHashMap<>();
        for (Mood mood : moods) {
            byName.putIfAbsent(mood.name(), mood);
        }
        ObjectNode schema = jackson.createObjectNode();
        schema.put("type", "object");
        var property = schema.putObject("properties").putObject("mood").put("type", "string")
                .put("description", "The mood to be in now.");
        if (!byName.isEmpty()) {
            byName.keySet().forEach(property.putArray("enum")::add);
        }
        schema.putArray("required").add("mood");
        return FunctionDefinition.dynamic(SET_MOOD, "Changes your mood now. Your mood is how you are today: it shapes how you talk "
                + "and it shows on your face. It was rolled for the day, and it stays until you change it or the next day. Change it "
                + "when the conversation really moves you (a joke makes you playful, bad news makes you melancholic), not in every "
                + "reply. Your moods: " + String.join(", ", byName.keySet()) + ".", schema, (continuation, arguments) -> {
            Object value = arguments == null ? null : arguments.get("mood");
            Mood mood = value instanceof String name ? byName.get(name) : null;
            if (mood == null) {
                continuation.fail("You have no such mood. Your moods: " + String.join(", ", byName.keySet()));
                return;
            }
            try {
                chatService.changeMood(joi, mood.key(), false);
                continuation.complete("You are " + mood.name() + " now.");
            } catch (Exception e) {
                log.warn("She could not change her mood to {}: {}", mood.name(), e.getMessage(), e);
                continuation.fail("It failed: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            }
        });
    }
}
