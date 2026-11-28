package com.github.enerccio.joi.domain.tools;

/**
 * A function of hers that the chain of permissions decides on (assistant → user → administrator, see {@code ToolPermissionService}): a
 * tool of the server ({@link ServerTool}) or one an extension declared ({@code ExtensionTool}). Its settings in each place of the chain
 * are kept by its function name ({@code ToolMetadata.toolKey}), so the name never changes.
 */
public interface ApprovableTool {

    /** The name of the function she calls: the key of its settings. */
    String function();

    /** What the tool is when no link of the chain says. */
    ToolPermission fallback();

    /** What the tool does, in a line: sent with a task, so a client can keep a standing approval as {name, description}. */
    String description();
}
