package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.marginalia.domain.service.agent.ShownImages;
import com.github.enerccio.marginalia.domain.service.agent.VirtualFileSystem;
import com.github.enerccio.marginalia.domain.service.agent.WorkspaceLimits;
import com.github.enerccio.marginalia.domain.service.function.FunctionSet;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMChatMessage;

/**
 * The workspace of an assistant: a folder of files of her own (notes, drafts, what her tasks write), kept between her turns, behind the
 * platform's {@link VirtualFileSystem} (a model sees virtual paths such as {@code /notes/plot.md}, never the disk). Each assistant has
 * her own; the limits are the administrator's ({@code JoiAppSettings}), the same for all. Her turns and her tasks use the same files
 * through the same lock.
 */
public interface JoiWorkspaceService {

    /** The workspace of the assistant, which has to be the current user's. */
    VirtualFileSystem open(Joi joi) throws Exception;

    /** The file functions of her turns and her tasks (list, read, write, append, replace, grep, move, copy, unzip, merge, delete). */
    FunctionSet fileTools(Joi joi) throws Exception;

    /**
     * {@code view_image}, for a model that sees images: it checks an image of her workspace and notes its path in {@code shown}; the
     * pictures come to the model in a message after the results of the round ({@link #shownImages}).
     */
    FunctionSet imageTools(Joi joi, ShownImages shown) throws Exception;

    /**
     * The message with the images she asked to look at since the last call (read from her workspace and scaled down for the model), null
     * when she asked for none.
     */
    LLMChatMessage shownImages(Joi joi, ShownImages shown) throws Exception;

    /** What a workspace may hold now. */
    WorkspaceLimits limits() throws Exception;

    /** Deletes every file of her workspace. */
    void clear(Joi joi) throws Exception;
}
