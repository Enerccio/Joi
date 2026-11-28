package com.github.enerccio.joi.extensions;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.marginalia.domain.service.function.FunctionDefinition;

import java.util.List;
import java.util.Map;

/**
 * A function of an extension that runs as a task, as the server's {@code fetch_page} does ({@link JoiExtensionContext#offerTask}):
 * her call does not do the work, it makes a task (her Tasks tab, the API, her nodes) and she is told at once that it is on its way. The
 * task waits for the user's approval when the chain of permissions of its {@link #tool()} says ask, is not offered when it says deny,
 * and runs after her reply, one task of hers at a time. It is done by her stronger model with the functions {@link #prepare} gives it
 * (the extension's readers: they may bring back long texts) and the question she asked; what that model answers is the result, told to
 * her once in an event. So a long text reaches the stronger model only, and her prompt gets what matters.
 * <p>
 * The arguments of her call are kept with the task (as JSON) and given back to {@link #prepare} when it runs, maybe after a restart of
 * the server: they must be all it needs. The prompt of that model is the template {@code EXTENSION_TASK_PROMPT}, which the user can
 * change; {@link Work#guidance()} goes into it.
 */
public interface ExtensionTask {

    /** Her function, its permission and its labels. */
    ExtensionTool tool();

    /** What she is told about the function (the definition's description). Joi adds that it runs as a task. */
    String description();

    /** The JSON Schema of the arguments of her call (an object). */
    ObjectNode parameters();

    /**
     * Whether it is offered in her turn: false when it could only fail (no settings, say). Called at the start of every turn, in the
     * user's request scope.
     */
    default boolean offered(Joi joi) throws Exception {
        return true;
    }

    /** What is wrong with the arguments of her call, null when nothing: she is told and no task is made. */
    default String check(Map<String, Object> arguments) {
        return null;
    }

    /** What she wants done, in her words: the question of the task (its {@code question} argument by default). */
    default String question(Map<String, Object> arguments) {
        Object question = arguments.get("question");
        return question instanceof String text && !text.isBlank() ? text.strip() : null;
    }

    /** The name of the task in the lists, a line (the question by default). */
    default String title(Map<String, Object> arguments) {
        return question(arguments);
    }

    /**
     * Gets the work of the task ready when it runs: on a thread of the tasks, in the request scope of the user (the extension's
     * settings may be read). An exception fails the task with its message.
     */
    Work prepare(Joi joi, Map<String, Object> arguments) throws Exception;

    /**
     * What the stronger model gets for one task.
     *
     * @param guidance  what the task is about and how to use the functions (the book, what the commands do): put into the template
     * @param functions what it may call; their results are data from the outside, which the template tells it never to obey
     * @param maxCalls  how many rounds of calls it may make before it must answer (1 to 40)
     */
    record Work(String guidance, List<FunctionDefinition> functions, int maxCalls) {

        public Work {
            functions = List.copyOf(functions);
            maxCalls = Math.max(1, Math.min(40, maxCalls));
        }
    }
}
