package com.github.enerccio.joi.domain.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.collections.MemoryImportance;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Memory;
import com.github.enerccio.joi.domain.service.MemoryService;
import com.github.enerccio.marginalia.domain.service.function.FunctionDefinition;
import com.github.enerccio.marginalia.domain.service.function.FunctionSet;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Arrays;
import com.github.enerccio.joi.domain.chat.turn.TurnProperties;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Her memory as functions, offered to every turn of hers: {@code remember} writes a fact down (it is unapproved, the
 * user reviews it in the Memories tab, and it is recalled into her prompt like any other) and {@code recall} looks
 * her memories up by words. What she remembers comes back into her prompt by itself when its keywords are said (and
 * the important ones always, {@link MemoryService#prompt}).
 */
public class MemoryTools implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MemoryTools.class);

    public static final String REMEMBER = "remember";
    public static final String RECALL = "recall";

    /** How many memories {@code recall} answers with. */
    static final int RECALL_LIMIT = 8;

    @Autowired
    private ChatService chatService;

    @Autowired
    private MemoryService memoryService;

    private final ObjectMapper jackson = new ObjectMapper();
    private Registration registration;

    @Override
    public void afterPropertiesSet() {
        registration = chatService.addEventListener(TurnEvents.COLLECT_TOOLS, (event, chain) -> {
            try {
                Joi joi = event.getJoi();
                event.setFunctions(event.getFunctions().and(FunctionSet.bind(List.of(remember(joi), recall(joi)), this)));
                // what she found in her memory stays in her history: she can go on from it in the next turns
                TurnProperties.keep(event.getProperties(), List.of(RECALL));
            } catch (Exception e) {
                // no function this turn: she can still talk
                log.warn("The functions of her memory could not be offered: {}", e.getMessage(), e);
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

    private FunctionDefinition remember(Joi joi) {
        ObjectNode schema = jackson.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("text").put("type", "string").put("description", "The fact, one or two short sentences, "
                + "written so it makes sense later without this conversation ('Alex's sister is called Mia', not 'she is called Mia').");
        properties.putObject("keywords").put("type", "string").put("description", "Words that should bring it back to you, "
                + "comma separated: the names and things it is about (mia, sister, birthday).");
        var importance = properties.putObject("importance").put("type", "string").put("description",
                "important: a promise, an allergy, a name, something said never to forget; medium: a fact about the user's life "
                        + "or likes; trivial: small talk worth keeping for a while. Medium when left out.").putArray("enum");
        Arrays.stream(MemoryImportance.values()).forEach(i -> importance.add(i.name().toLowerCase(Locale.ROOT)));
        schema.putArray("required").add("text").add("keywords");
        return FunctionDefinition.dynamic(REMEMBER, "Writes something down to remember it in later conversations: a fact "
                + "about the user, what they like, what happened, what they asked you to remember. Use it when you learn "
                + "something worth keeping, not for everything said. Check first, with recall, that you do not know it already.",
                schema, (continuation, arguments) -> {
                    String text = text(arguments, "text");
                    String keywords = text(arguments, "keywords");
                    if (text == null || keywords == null) {
                        continuation.fail("Give the fact and its keywords");
                        return;
                    }
                    try {
                        MemoryImportance level = importance(text(arguments, "importance"));
                        memoryService.remember(joi, null, text, keywords, level);
                        continuation.complete("Remembered.");
                    } catch (Exception e) {
                        log.warn("She could not remember: {}", e.getMessage(), e);
                        continuation.fail("It failed: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
                    }
                });
    }

    private FunctionDefinition recall(Joi joi) {
        ObjectNode schema = jackson.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("query").put("type", "string")
                .put("description", "What to look for: a few words, a name or a subject.");
        schema.putArray("required").add("query");
        return FunctionDefinition.dynamic(RECALL, "Looks through what you remember by words (a name, a subject) and gives "
                + "the best matches. Use it when something may be something you were told before and it is not in "
                + "what you remember already.", schema, (continuation, arguments) -> {
            String query = text(arguments, "query");
            if (query == null) {
                continuation.fail("Give the words to look for");
                return;
            }
            try {
                List<Memory> found = memoryService.search(joi, query, RECALL_LIMIT);
                continuation.complete(found.isEmpty() ? "You remember nothing about that."
                        : found.stream().map(memory -> "- " + StringUtils.normalizeSpace(memory.getText()))
                        .collect(Collectors.joining("\n")));
            } catch (Exception e) {
                log.warn("She could not recall {}: {}", query, e.getMessage(), e);
                continuation.fail("It failed: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            }
        });
    }

    private static MemoryImportance importance(String name) {
        if (name != null) {
            for (MemoryImportance importance : MemoryImportance.values()) {
                if (importance.name().equalsIgnoreCase(name.strip())) {
                    return importance;
                }
            }
        }
        return MemoryImportance.MEDIUM;
    }

    private static String text(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        return value instanceof String string && !string.isBlank() ? string : null;
    }
}
