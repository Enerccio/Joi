package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.JoiAppSettingsService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.JoiWorkspaceService;
import com.github.enerccio.marginalia.Configuration;
import com.github.enerccio.marginalia.domain.service.InferenceServices;
import com.github.enerccio.marginalia.domain.service.agent.AgentFileTools;
import com.github.enerccio.marginalia.domain.service.agent.AgentFileToolsImpl;
import com.github.enerccio.marginalia.domain.service.agent.ImageFiles;
import com.github.enerccio.marginalia.domain.service.agent.ImageTools;
import com.github.enerccio.marginalia.domain.service.agent.ImageToolsImpl;
import com.github.enerccio.marginalia.domain.service.agent.ShownImages;
import com.github.enerccio.marginalia.domain.service.agent.VirtualFileException;
import com.github.enerccio.marginalia.domain.service.agent.VirtualFileSystem;
import com.github.enerccio.marginalia.domain.service.agent.VirtualPath;
import com.github.enerccio.marginalia.domain.service.agent.WorkspaceLimits;
import com.github.enerccio.marginalia.domain.service.function.FunctionDefinition;
import com.github.enerccio.marginalia.domain.service.function.FunctionSet;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMChatMessage;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMRole;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/** {@link JoiWorkspaceService}: {@code <data>/joi-workspaces/<uuid of the assistant>}, the limits from the administrator's settings. */
public class JoiWorkspaceServiceImpl implements JoiWorkspaceService {

    /** The folder of the workspaces in the data folder. */
    public static final String FOLDER = "joi-workspaces";

    /** The file functions that need the results of commands, which an assistant has none of. */
    private static final Set<String> NOT_OFFERED = Set.of("list_command_results", "save_command_result");

    @Autowired
    private Configuration configuration;

    @Autowired
    private JoiAppSettingsService appSettingsService;

    @Autowired
    private JoiService joiService;

    @Autowired
    private InferenceServices inferenceServices;

    /** One lock for each workspace: her turn and her tasks change the same files. */
    private final ConcurrentMap<String, Lock> locks = new ConcurrentHashMap<>();

    @Override
    @CommonTxReadOnly
    public VirtualFileSystem open(Joi joi) throws Exception {
        Joi stored = joi == null || joi.getUuid() == null ? null : joiService.findForUser(joi.getUuid());
        if (stored == null) {
            throw new IllegalArgumentException("The assistant does not exist");
        }
        Path folder = configuration.getDataFolder().toPath().resolve(FOLDER).resolve(stored.getUuid());
        return VirtualFileSystem.ofWorkspace(folder, limits(), locks.computeIfAbsent(stored.getUuid(), uuid -> new ReentrantLock()));
    }

    @Override
    @CommonTxReadOnly
    public FunctionSet fileTools(Joi joi) throws Exception {
        AgentFileToolsImpl tools = new AgentFileToolsImpl(open(joi));
        FunctionSet all = inferenceServices.getFunctionRegistry().bind(AgentFileTools.class, tools);
        // the same functions as an agent's, without the two for the results of commands
        List<FunctionDefinition> offered = all.getDefinitions().stream().filter(definition -> !NOT_OFFERED.contains(definition.getName())).toList();
        return FunctionSet.bind(offered, tools);
    }

    @Override
    @CommonTxReadOnly
    public FunctionSet imageTools(Joi joi, ShownImages shown) throws Exception {
        return inferenceServices.getFunctionRegistry().bind(ImageTools.class, new ImageToolsImpl(open(joi), shown));
    }

    @Override
    @CommonTxReadOnly
    public LLMChatMessage shownImages(Joi joi, ShownImages shown) throws Exception {
        List<VirtualPath> looked = shown.take();
        if (looked.isEmpty()) {
            return null;
        }
        VirtualFileSystem files = open(joi);
        StringBuilder text = new StringBuilder(looked.size() == 1 ? "The image you asked to look at: " : "The images you asked to look at, in this order: ");
        LLMChatMessage message = LLMChatMessage.of(LLMRole.USER, "");
        for (int i = 0; i < looked.size(); i++) {
            VirtualPath path = looked.get(i);
            text.append(i == 0 ? "" : ", ").append(path);
            try {
                ImageFiles.Prepared image = files.readImage(path);
                message.addAttachment(image.toAttachment(path.toString()));
            } catch (VirtualFileException e) {
                // it was there when she asked and is gone or no image now: she is told, not the turn failed
                text.append(" (cannot be shown: ").append(e.getMessage()).append(')');
            }
        }
        message.setContent(text.toString());
        return message;
    }

    @Override
    @CommonTxReadOnly
    public WorkspaceLimits limits() throws Exception {
        return appSettingsService.get().workspaceLimits();
    }

    @Override
    @CommonTxReadOnly
    public void clear(Joi joi) throws Exception {
        open(joi).clearWorkspace();
    }
}
