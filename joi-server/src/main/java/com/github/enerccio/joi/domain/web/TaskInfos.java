package com.github.enerccio.joi.domain.web;

import com.github.enerccio.joi.domain.model.impl.JoiTask;
import com.github.enerccio.joi.domain.tools.ServerTool;
import com.github.enerccio.joi.protocol.http.TaskInfo;
import com.github.enerccio.joi.protocol.http.TaskStep;
import org.apache.commons.lang3.StringUtils;

import java.util.Date;
import java.util.List;
import java.util.Locale;

/** A task as the API and the nodes see it ({@link TaskInfo}); one place, so the HTTP API and the {@code task} frame say the same. */
public final class TaskInfos {

    private TaskInfos() {
    }

    /** @param steps the transcript, null for none (the list, the frame) */
    public static TaskInfo of(JoiTask task, List<TaskStep> steps) {
        ServerTool tool = ServerTool.of(task.getTool());
        return new TaskInfo(task.getUuid(), StringUtils.defaultString(task.getTitle()), task.getTool(),
                tool == null ? null : tool.description(), task.getStatus().name().toLowerCase(Locale.ROOT), task.getInstructions(),
                task.getUrl(), task.getResult(), task.getError(), iso(task.getCreation()), iso(task.getStartedAt()),
                iso(task.getFinishedAt()), steps);
    }

    private static String iso(Date date) {
        return date == null ? null : date.toInstant().toString();
    }
}
