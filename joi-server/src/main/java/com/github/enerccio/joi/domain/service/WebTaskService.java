package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.JoiTask;
import com.github.enerccio.joi.domain.tools.ServerTool;

import java.util.List;

/**
 * The tasks of the web tools ({@code web_search}, {@code fetch_page}): what she asks for becomes a {@link JoiTask} that
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
    JoiTask submit(Joi joi, ServerTool tool, String question, String url, String toolCallId) throws Exception, NotAllowedException;

    /** The user allows a task that waits: it runs when her turns are done. */
    JoiTask approve(JoiTask task) throws Exception;

    /** The user does not allow a task that waits; she is told, with the reason when there is one. */
    JoiTask deny(JoiTask task, String reason) throws Exception;

    /** Stops a task: at once when it has not started, between its steps when it runs. She is told. */
    JoiTask cancel(JoiTask task) throws Exception;

    /** The tasks of the assistant, the newest first. */
    List<JoiTask> tasks(Joi joi) throws Exception;

    /** The task of the uuid when it is the user's, else null. */
    JoiTask find(String uuid) throws Exception;

    /** Starts the next queued task of the assistant when no turn of hers runs or waits (now, or when they are done). */
    void drain(Joi joi);
}
