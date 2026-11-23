package com.github.enerccio.joi.domain.chat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.bodies.Outfit;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.BodyService;
import com.github.enerccio.marginalia.domain.service.function.FunctionDefinition;
import com.github.enerccio.marginalia.domain.service.function.FunctionSet;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The functions of her body that every turn of hers is offered, on a node or not: {@code change_body_part} puts a part
 * of her own body, one of the parts described apart (the ones her prompt lists as "Parts you can switch"), in another
 * state; {@code list_outfits} and {@code set_outfit} tell her outfits (presets of the toggles of the body, each with a
 * description) and put one on; {@code list_bodies} and {@code switch_body} tell her bodies and move her into another.
 * It is her choice: no consent is asked (that is for the user's requests), the change is kept on the body like the
 * user's, the nodes are told to read it again ({@code event body}), and she is told in the function's answer how it
 * looks now, not again as an event.
 */
public class BodyTools implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(BodyTools.class);

    /** The name of the function. */
    public static final String CHANGE_BODY_PART = "change_body_part";
    public static final String LIST_OUTFITS = "list_outfits";
    public static final String SET_OUTFIT = "set_outfit";
    public static final String LIST_BODIES = "list_bodies";
    public static final String SWITCH_BODY = "switch_body";

    @Autowired
    private ChatService chatService;

    @Autowired
    private BodyService bodyService;

    private final ObjectMapper jackson = new ObjectMapper();
    private Registration registration;

    @Override
    public void afterPropertiesSet() {
        registration = chatService.addEventListener(TurnEvents.COLLECT_TOOLS, (event, chain) -> {
            try {
                Joi joi = event.getJoi();
                String profile = event.getInput() == null ? null : event.getInput().profile();
                Body body = bodyService.active(joi, profile);
                List<BodyService.PartOption> options = body == null ? List.of() : bodyService.partOptions(body);
                // always offered, with or without parts, outfits or a second body: the model renders the tools before the history, so a
                // list that comes and goes makes the server's cache of the prompt start over (the functions say so when there is nothing)
                List<Body> bodies = bodyService.findForJoi(joi);
                List<FunctionDefinition> functions = new ArrayList<>();
                functions.add(changeBodyPart(joi, profile, options));
                functions.add(listOutfits(joi, profile));
                functions.add(setOutfit(joi, profile));
                functions.add(listBodies(joi, profile));
                functions.add(switchBody(joi, profile, bodies));
                event.setFunctions(event.getFunctions().and(FunctionSet.bind(functions, this)));
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

    private FunctionDefinition changeBodyPart(Joi joi, String profile, List<BodyService.PartOption> options) {
        ObjectNode schema = jackson.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        var partProperty = properties.putObject("part").put("type", "string")
                .put("description", "The part of your body, as in the parts you can switch.");
        // an empty enum is not a valid schema: with no part the value is any string, and the call fails
        var names = options.isEmpty() ? null : partProperty.putArray("enum");
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
                + "what it is like), the way you choose: use it when you feel like it, as often as it suits your mood and what is going on, and when the user asks and you agree. "
                + "The change is made at once and the user sees it.", schema, (continuation, arguments) -> {
            String part = text(arguments, "part");
            String state = text(arguments, "state");
            if (part == null || state == null) {
                continuation.fail("Give the part and the state");
                return;
            }
            try {
                Body body = bodyService.active(joi, profile);
                if (body == null) {
                    continuation.fail("You are in no body");
                    return;
                }
                String answer = bodyService.changePart(body, profile, part, state);
                continuation.complete(answer);
            } catch (IllegalArgumentException e) {
                continuation.fail(e.getMessage());
            } catch (Exception e) {
                log.warn("She could not change {} of her body: {}", part, e.getMessage(), e);
                continuation.fail("It failed: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            }
        });
    }

    private FunctionDefinition listOutfits(Joi joi, String profile) {
        return FunctionDefinition.dynamic(LIST_OUTFITS, "Lists the outfits of your body, each with what you look like in it, "
                + "and the one you wear now. Use it when you want to choose what to wear, or when you are asked what you have.",
                emptySchema(), (continuation, arguments) -> {
            try {
                // the body she is in when she asks: she may have moved into another one in this turn
                Body body = bodyService.active(joi, profile);
                if (body == null) {
                    continuation.fail("You are in no body");
                    return;
                }
                List<Outfit> outfits = bodyService.outfits(body);
                if (outfits.isEmpty()) {
                    continuation.complete("The body you are in has no outfits.");
                    return;
                }
                Outfit wearing = bodyService.currentOutfit(body, profile);
                StringBuilder text = new StringBuilder("Your outfits:");
                for (Outfit outfit : outfits) {
                    text.append("\n- ").append(outfit.name()).append(wearing != null && wearing.id().equals(outfit.id()) ? " (you wear it now)" : "")
                            .append(StringUtils.isBlank(outfit.description()) ? "" : ": " + outfit.description().strip());
                }
                if (wearing == null) {
                    text.append("\nYou wear none of them now.");
                }
                continuation.complete(text.toString());
            } catch (Exception e) {
                fail(continuation, "She could not list her outfits", e);
            }
        });
    }

    private FunctionDefinition setOutfit(Joi joi, String profile) {
        ObjectNode schema = jackson.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("outfit").put("type", "string").put("description",
                "The name of the outfit to put on: one of the outfits of the body you are in (list_outfits tells them).");
        schema.putArray("required").add("outfit");
        return FunctionDefinition.dynamic(SET_OUTFIT, "Puts one of the outfits of your body on, the way you choose: use it when you feel like it, "
                + "when it suits the time of day, the weather, your mood or what you are doing, and when the user asks and you agree. "
                + "The change is made at once and the user sees it.", schema, (continuation, arguments) -> {
            String outfit = text(arguments, "outfit");
            if (outfit == null) {
                continuation.fail("Give the outfit");
                return;
            }
            try {
                // the body she is in when she asks: she may have moved into another one in this turn
                Body body = bodyService.active(joi, profile);
                if (body == null) {
                    continuation.fail("You are in no body");
                    return;
                }
                String answer = bodyService.wearOutfit(body, profile, outfit);
                continuation.complete(answer);
            } catch (IllegalArgumentException e) {
                continuation.fail(e.getMessage());
            } catch (Exception e) {
                fail(continuation, "She could not put on " + outfit, e);
            }
        });
    }

    private FunctionDefinition listBodies(Joi joi, String profile) {
        return FunctionDefinition.dynamic(LIST_BODIES, "Lists your bodies, each with what it looks like and its outfits, and the one "
                + "you are in. Use it when you want to choose a body, or when you are asked what you have.", emptySchema(), (continuation, arguments) -> {
            try {
                StringBuilder text = new StringBuilder("Your bodies:");
                for (BodyService.BodyInfo info : bodyService.bodyInfos(joi, profile)) {
                    text.append("\n- ").append(info.name()).append(info.active() ? " (you are in it now)" : "")
                            .append(StringUtils.isBlank(info.description()) ? "" : ": " + info.description().strip());
                    if (!info.outfits().isEmpty()) {
                        text.append("\n  Outfits: ").append(String.join(", ", info.outfits().stream()
                                .map(outfit -> outfit.name() + (outfit.worn() ? " (worn now)" : "")).toList())).append('.');
                    }
                }
                continuation.complete(text.toString());
            } catch (Exception e) {
                fail(continuation, "She could not list her bodies", e);
            }
        });
    }

    private FunctionDefinition switchBody(Joi joi, String profile, List<Body> bodies) {
        ObjectNode schema = jackson.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        var bodyProperty = properties.putObject("body").put("type", "string").put("description", "The body to move into, as in your bodies.");
        // two bodies of one name are one value: a schema's enum lists each once (the first of them is the one moved into)
        if (!bodies.isEmpty()) {
            var names = bodyProperty.putArray("enum");
            bodies.stream().map(other -> StringUtils.defaultIfBlank(other.getName(), "unnamed")).distinct().forEach(names::add);
        }
        schema.putArray("required").add("body");
        return FunctionDefinition.dynamic(SWITCH_BODY, "Moves you into another of your bodies, the way you choose: use it when you feel "
                + "like it, and when the user asks and you agree. The change is made at once and the user sees it; you keep what you "
                + "remember.", schema, (continuation, arguments) -> {
            String target = text(arguments, "body");
            if (target == null) {
                continuation.fail("Give the body");
                return;
            }
            try {
                String answer = bodyService.switchBody(joi, profile, target);
                continuation.complete(answer);
            } catch (IllegalArgumentException e) {
                continuation.fail(e.getMessage());
            } catch (Exception e) {
                fail(continuation, "She could not move into " + target, e);
            }
        });
    }

    private ObjectNode emptySchema() {
        ObjectNode schema = jackson.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return schema;
    }

    private static void fail(com.github.enerccio.marginalia.domain.service.function.FunctionContinuation continuation, String what, Exception e) {
        log.warn("{}: {}", what, e.getMessage(), e);
        continuation.fail("It failed: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
    }

    private static String text(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        return value instanceof String string && !string.isBlank() ? string : null;
    }
}
