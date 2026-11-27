package com.github.enerccio.joi.domain.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.BodyService;
import com.github.enerccio.joi.domain.service.NodeService;
import com.github.enerccio.joi.protocol.node.ShowExpression;
import com.github.enerccio.marginalia.domain.service.function.FunctionDefinition;
import com.github.enerccio.marginalia.domain.service.function.FunctionSet;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * {@code set_expression}, offered to every turn of hers: she shows an expression of her body's model on her face for a few
 * seconds, over the face her mood gives her ({@code expression} frame to her nodes). The expressions are the model's
 * ({@link BodyService#expressions}): its mood presets but neutral, and its own ones. It is her choice: no consent is asked,
 * and nothing is stored: the face goes back to her mood's by itself.
 */
public class ExpressionTools implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ExpressionTools.class);

    /** The name of the function. */
    public static final String SET_EXPRESSION = "set_expression";

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
                Body body = bodyService.active(joi, event.getInput() == null ? null : event.getInput().profile());
                List<String> expressions = body == null ? List.of() : bodyService.expressions(body);
                // always offered (see AnimationTools): a list that comes and goes makes the prompt's cache start over
                event.setFunctions(event.getFunctions().and(FunctionSet.bind(List.of(setExpression(joi, expressions)), this)));
            } catch (Exception e) {
                // no function this turn: she can still talk
                log.warn("The expressions of her body could not be offered: {}", e.getMessage(), e);
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

    private FunctionDefinition setExpression(Joi joi, List<String> expressions) {
        ObjectNode schema = jackson.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        var expression = properties.putObject("expression").put("type", "string")
                .put("description", "The expression to show.");
        // an empty enum is not a valid schema: with none the value is any string, and the call fails
        if (!expressions.isEmpty()) {
            expressions.forEach(expression.putArray("enum")::add);
        }
        properties.putObject("strength").put("type", "number").put("minimum", 0.1).put("maximum", 1)
                .put("description", "How strong, 0.1 (a hint) to 1 (full); 0.8 when left out.");
        properties.putObject("seconds").put("type", "number").put("minimum", 1).put("maximum", ShowExpression.MAX_SECONDS)
                .put("description", "How long you hold it, 1 to 60 seconds; 4 when left out.");
        schema.putArray("required").add("expression");
        return FunctionDefinition.dynamic(SET_EXPRESSION, "Shows an expression on your face now, for a few seconds: a "
                + "feeling of the moment (a smile at a joke, surprise at news, a frown). Your face then goes back to the one "
                + "your mood gives you. Your expressions: " + (expressions.isEmpty() ? "none now." : String.join(", ", expressions) + "."),
                schema, (continuation, arguments) -> {
            Object value = arguments == null ? null : arguments.get("expression");
            String name = value instanceof String text ? find(expressions, text) : null;
            if (name == null) {
                continuation.fail(expressions.isEmpty() ? "Your body has no expressions now."
                        : "You have no such expression. Your expressions: " + String.join(", ", expressions));
                return;
            }
            Double weight = number(arguments.get("strength"), 0.1, 1);
            Double seconds = number(arguments.get("seconds"), 1, ShowExpression.MAX_SECONDS);
            try {
                int nodes = nodeService.express(joi, new ShowExpression(name, weight, seconds));
                continuation.complete(nodes == 0 ? "You are not shown anywhere now, nobody saw it." : "You show " + name + ".");
            } catch (Exception e) {
                log.warn("She could not show {}: {}", name, e.getMessage(), e);
                continuation.fail("It failed: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            }
        });
    }

    /** The expression by its name, ignoring case (a model may say "Happy"), null for none. */
    private static String find(List<String> expressions, String name) {
        String wanted = StringUtils.strip(name);
        return expressions.stream().filter(candidate -> candidate.equalsIgnoreCase(wanted)).findFirst().orElse(null);
    }

    /** A number within the bounds, null when it is not one (the node's default). */
    private static Double number(Object value, double min, double max) {
        double number;
        if (value instanceof Number given) {
            number = given.doubleValue();
        } else if (value instanceof String text && StringUtils.isNotBlank(text)) {
            try {
                number = Double.parseDouble(text.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        return Double.isFinite(number) ? Math.max(min, Math.min(max, number)) : null;
    }
}
