package com.github.enerccio.joi.domain.extensions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.enerccio.joi.domain.chat.TaskLoop;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.JoiTask;
import com.github.enerccio.joi.domain.persona.JoiPromptField;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.domain.templates.ExtensionTaskTemplateData;
import com.github.enerccio.joi.extensions.ExtensionTask;
import com.github.enerccio.joi.extensions.ExtensionTool;
import com.github.enerccio.marginalia.domain.service.InferenceService;
import com.github.enerccio.marginalia.domain.service.function.FunctionSet;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * Does the work of a task of an extension ({@link ExtensionTask}) for {@code WebTaskService}, which keeps the task, its approval, its
 * transcript and telling her: the extension gets its work ready from the arguments kept with the task, and the stronger model runs the
 * loop ({@link TaskLoop}) with the template {@link JoiPromptField#EXTENSION_TASK_PROMPT} and the extension's functions.
 */
public class ExtensionTaskRunner {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private PersonaService personaService;

    @Autowired
    private JoiService joiService;

    /** Runs the task on this thread (the user's request scope) and gives the answer of the model. */
    public String run(Joi joi, JoiTask task, JoiExtensions.Task registered, InferenceService service, Executor executor,
                      BooleanSupplier cancelled, TaskLoop.Transcript transcript) throws Exception {
        ExtensionTask extensionTask = registered.task();
        ExtensionTask.Work work = extensionTask.prepare(joi, arguments(task.getArguments()));
        if (work == null) {
            throw new IllegalStateException("The extension gave the task nothing to do");
        }
        ExtensionTool tool = extensionTask.tool();
        ExtensionTaskTemplateData data = new ExtensionTaskTemplateData();
        data.names(joiService.name(joi), personaService.userName());
        data.setToday(LocalDate.now().toString());
        data.setQuestion(task.getInstructions());
        data.setTool(tool.function() + (tool.description().isEmpty() ? "" : " (" + tool.description() + ")"));
        data.setGuidance(StringUtils.defaultIfBlank(StringUtils.strip(work.guidance()), null));
        String instruction = personaService.render(joi, JoiPromptField.EXTENSION_TASK_PROMPT, data);
        FunctionSet functions = work.functions().isEmpty() ? FunctionSet.empty() : FunctionSet.bind(work.functions(), extensionTask);
        // a round for each call it may make, and the last one to answer
        return new TaskLoop(service, executor, cancelled, transcript).run(instruction, functions, work.maxCalls() + 1, null);
    }

    /** The arguments of her call as they were kept with the task; empty for none. */
    public static Map<String, Object> arguments(String json) throws Exception {
        if (StringUtils.isBlank(json)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> parsed = JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
        });
        return parsed == null ? new LinkedHashMap<>() : parsed;
    }

    /** The arguments of her call as they are kept with the task. */
    public static String json(Map<String, Object> arguments) throws Exception {
        return JSON.writeValueAsString(arguments == null ? Map.of() : arguments);
    }
}
