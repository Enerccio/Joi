package com.github.enerccio.joi.domain.chat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
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
 * The functions of her body that every turn of hers is offered, on a node or not: {@code change_body_part} puts a part
 * of her own body, one of the parts described apart (the ones her prompt lists as "Parts you can switch"), in another
 * state. It is her choice: no consent is asked (that is for the user's requests), the change is kept on the body like
 * the user's, the nodes are told to read it again ({@code event body}), and she is told in the function's answer how it
 * looks now, not again as an event.
 */
public class BodyTools implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(BodyTools.class);

    /** The name of the function. */
    public static final String CHANGE_BODY_PART = "change_body_part";

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
                List<BodyService.PartOption> options = body == null ? List.of() : bodyService.partOptions(body);
                if (!options.isEmpty()) {
                    event.setFunctions(event.getFunctions().and(FunctionSet.bind(List.of(changeBodyPart(joi, body, options)), this)));
                }
            } catch (Exception e) {
                // no function this turn: she can still talk
                log.warn("The functions of her body could not be offered: {}", e.getMessage(), e);
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

    private FunctionDefinition changeBodyPart(Joi joi, Body body, List<BodyService.PartOption> options) {
        ObjectNode schema = jackson.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        var names = properties.putObject("part").put("type", "string")
                .put("description", "The part of your body, as in the parts you can switch.").putArray("enum");
        Map<String, List<String>> states = new LinkedHashMap<>();
        for (BodyService.PartOption option : options) {
            if (!states.containsKey(option.part())) {
                names.add(option.part());
            }
            states.putIfAbsent(option.part(), option.states());
        }
        StringBuilder text = new StringBuilder("The state to put it in. ");
        states.forEach((part, of) -> text.append(part).append(": ").append(String.join(", ", of)).append(". "));
        properties.putObject("state").put("type", "string").put("description", text.toString().strip());
        schema.putArray("required").add("part").add("state");
        return FunctionDefinition.dynamic(CHANGE_BODY_PART, "Changes a part of your own body (take it off, put it on, change "
                + "what it is like), the way you choose: use it when you decide to, or when the user asks and you agree. "
                + "The change is made at once and the user sees it.", schema, (continuation, arguments) -> {
            String part = text(arguments, "part");
            String state = text(arguments, "state");
            if (part == null || state == null) {
                continuation.fail("Give the part and the state");
                return;
            }
            try {
                String answer = bodyService.changePart(body, part, state);
                // the nodes show it: they read her body again
                nodeService.notify(joi, "body");
                continuation.complete(answer);
            } catch (IllegalArgumentException e) {
                continuation.fail(e.getMessage());
            } catch (Exception e) {
                log.warn("She could not change {} of her body: {}", part, e.getMessage(), e);
                continuation.fail("It failed: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            }
        });
    }

    private static String text(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        return value instanceof String string && !string.isBlank() ? string : null;
    }
}
