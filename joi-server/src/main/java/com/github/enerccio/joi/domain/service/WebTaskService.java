package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.JoiTask;
import com.github.enerccio.joi.domain.tools.ApprovableTool;
import com.github.enerccio.joi.domain.tools.ServerTool;

import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;

import java.util.List;

/**
 * The tasks of the web tools ({@code web_search}, {@code fetch_page}) and of the extensions ({@code ExtensionTask}): what she asks for
 * becomes a {@link JoiTask} that
 * the stronger model does after the reply that asked for it (see {@code DESIGN.md} → Tasks after the reply). Whether it
 * runs at once or waits for the user is the tool's permission along the chain ({@link ToolPermissionService}). When it
 * ends, she is told in one event with the result, through a connected node, else when a node connects.
 */
public interface WebTaskService {

    /** The tool is not allowed (the permission along the chain is deny): no task was made. */
    class NotAllowedException extends Exception {
        public NotAllowedException(String message) {
            super(message);
        }
    }

    /**
     * Makes the task of a call of hers: {@code QUEUED} when the tool is allowed, {@code WAITING} for the user's answer when
     * it asks. A queued task runs when her turns are done.
     *
     * @param question   what she wants to know (for a page: what to find in it), may be blank for a page
     * @param url        the page to read, null for a search
     * @param toolCallId the id of her call, to say which it answered
     * @throws NotAllowedException when the tool is denied
     */
    default JoiTask submit(Joi joi, ServerTool tool, String question, String url, String toolCallId) throws Exception, NotAllowedException {
        return submit(joi, tool, question, url, null, null, toolCallId);
    }

    /**
     * Makes the task of a call of hers, of a tool of the server or of an extension ({@code ExtensionTask}).
     *
     * @param title     the name of the task in the lists, null for the question (or the page)
     * @param arguments the arguments of her call as JSON, for a task of an extension (given back to it when it runs); null for none
     * @throws NotAllowedException when the tool is denied
     */
    JoiTask submit(Joi joi, ApprovableTool tool, String question, String url, String title, String arguments, String toolCallId)
            throws Exception, NotAllowedException;

    /** The tool of a function name: one of the server, or one an extension that is loaded declared; null for none. */
    ApprovableTool tool(String function);

    /** The user allows a task that waits: it runs when her turns are done. */
    JoiTask approve(JoiTask task) throws Exception;

    /**
     * The user allows a task that waits and, with {@code always}, the tool for the assistant ahead of time
     * ({@link #autoApprove}): the answer of a client that offers "approve all of this tool".
     */
    JoiTask approve(JoiTask task, boolean always) throws Exception;

    /** The user does not allow a task that waits; she is told, with the reason when there is one. */
    JoiTask deny(JoiTask task, String reason) throws Exception;

    /** Stops a task: at once when it has not started, between its steps when it runs. She is told. */
    JoiTask cancel(JoiTask task) throws Exception;

    /** Whether the user approved every use of the tool for the assistant ahead of time. */
    boolean autoApproves(Joi joi, ApprovableTool tool) throws Exception;

    /**
     * Approves the tool for the assistant ahead of time, or takes that back. A task of it that asks is then made, put through
     * the approval as the user's answer would, and approved at once; she is told so when it ends, which is also how it is
     * logged in the chat. A tool that is denied stays denied. Turning it on approves the tasks of the tool that wait now.
     */
    void autoApprove(Joi joi, ApprovableTool tool, boolean on) throws Exception;

    /** The tools the user approved ahead of time for the assistant (the server's, then those of the extensions that are loaded). */
    List<ApprovableTool> autoApproved(Joi joi) throws Exception;

    /** The tasks of the assistant, the newest first. */
    List<JoiTask> tasks(Joi joi) throws Exception;

    /**
     * Calls {@code onChange} (on whatever thread made the change) when a task of the assistant changes: made, answered,
     * started, a step of its transcript, ended. Unregister when the Tasks tab goes away.
     */
    Registration watch(Joi joi, Runnable onChange) throws Exception;

    /** The task of the uuid when it is the user's, else null. */
    JoiTask find(String uuid) throws Exception;

    /** Starts the next queued task of the assistant when no turn of hers runs or waits (now, or when they are done). */
    void drain(Joi joi);
}
