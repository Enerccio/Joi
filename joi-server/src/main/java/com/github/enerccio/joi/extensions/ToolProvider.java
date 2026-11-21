package com.github.enerccio.joi.extensions;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.marginalia.domain.service.function.FunctionDefinition;

import java.util.List;

/**
 * The functions an extension offers to her turns, asked at the start of every turn ({@code COLLECT_TOOLS}). Build them with
 * {@code FunctionDefinition.dynamic(name, description, schema, handler)}: the handler answers through its continuation, from any
 * thread, when it likes. The names are the extension's to keep apart from hers (a prefix of the extension is a good one); a name that is
 * taken already by a function of the server is not offered.
 */
@FunctionalInterface
public interface ToolProvider {

    /**
     * @param joi the assistant whose turn it is
     * @return what she may call in this turn, empty for none
     */
    List<FunctionDefinition> tools(Joi joi) throws Exception;
}
