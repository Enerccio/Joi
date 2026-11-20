package com.github.enerccio.joi.protocol.node;

import com.github.enerccio.joi.protocol.http.*;

/**
 * The types of {@link Frame}s of the node WebSocket and what their data is. The order and the rules are in
 * {@code asyncapi.yaml}: {@code hello}, {@code welcome}, {@code manifest}, then anything; a {@code say} is answered by
 * frames that refer to it, as a streamed chat ({@code reasoning}, {@code text}, {@code tool}, {@code reply},
 * {@code audio}, then {@code done} or {@code error}).
 */
public enum NodeMessage {
    /** {@link Hello}, node to server, the first frame. */
    HELLO("hello", Direction.TO_SERVER, Hello.class),
    /** {@link Welcome}, the answer to hello. */
    WELCOME("welcome", Direction.TO_NODE, Welcome.class),
    /** {@link Manifest}, after welcome and whenever it changes. */
    MANIFEST("manifest", Direction.TO_SERVER, Manifest.class),
    /** {@link Say}: the user talks; the id of the frame is the turn. */
    SAY("say", Direction.TO_SERVER, Say.class),
    /** {@link Stop} a turn of the node. */
    STOP("stop", Direction.TO_SERVER, Stop.class),
    /** {@link Display}: she is shown elsewhere now (since 1.4); the server starts her turn about it. */
    DISPLAY("display", Direction.TO_SERVER, Display.class),
    /** {@link PartSwitch}: the user switched a part of her body (since 1.5); the server keeps it and starts her turn about it. */
    PART("part", Direction.TO_SERVER, PartSwitch.class),
    /** {@link PartKept}: the server kept a part in the state asked for by a {@code part} with an id (since 1.5); {@code ref} is that id. */
    PART_KEPT("part_kept", Direction.TO_NODE, PartKept.class),
    /** {@link PartRefused}: she did not allow the change asked for by a {@code part} with an id (since 1.5); {@code ref} is that id. */
    PART_REFUSED("part_refused", Direction.TO_NODE, PartRefused.class),
    /** {@link PlayAnimation}: she plays an animation now (since 1.5), a function of hers. */
    ANIMATION("animation", Direction.TO_NODE, PlayAnimation.class),
    /** {@link ChatLog}: a note in her conversation, not given to the model (since 1.6). */
    LOG("log", Direction.TO_NODE, ChatLog.class),
    /** {@link TaskInfo}: a task of hers is made or changed its status; one that {@code waits} is for the node to ask the user about (since 1.7). */
    TASK("task", Direction.TO_NODE, TaskInfo.class),
    /** {@link TurnStart}: a turn the server started (first boot, the user is back); answered like a say. */
    TURN("turn", Direction.TO_NODE, TurnStart.class),
    /** {@link TextDelta} of the thinking (only when asked for). */
    REASONING("reasoning", Direction.TO_NODE, TextDelta.class),
    /** {@link TextDelta} of the reply. */
    TEXT("text", Direction.TO_NODE, TextDelta.class),
    /** {@link ToolUse}: she used a tool. */
    TOOL("tool", Direction.TO_NODE, ToolUse.class),
    /** {@link ChatReply} without audio: the text is whole and stored. */
    REPLY("reply", Direction.TO_NODE, ChatReply.class),
    /** {@link AudioChunk}: a piece of the reply spoken. */
    AUDIO("audio", Direction.TO_NODE, AudioChunk.class),
    /** {@link Done}: the last frame of a turn that went well. */
    DONE("done", Direction.TO_NODE, Done.class),
    /** {@link ToolCall}: run a tool of the node. */
    TOOL_CALL("tool_call", Direction.TO_NODE, ToolCall.class),
    /** {@link ToolProgress} of a call. */
    TOOL_PROGRESS("tool_progress", Direction.TO_SERVER, ToolProgress.class),
    /** {@link ToolResult} of a call. */
    TOOL_RESULT("tool_result", Direction.TO_SERVER, ToolResult.class),
    /** {@link ToolCancel}: the result is no longer wanted. */
    TOOL_CANCEL("tool_cancel", Direction.TO_NODE, ToolCancel.class),
    /** {@link Approval} of a call, asked and answered on the node. */
    APPROVAL("approval", Direction.TO_SERVER, Approval.class),
    /** {@link ServerEvent}: something changed on the server. */
    EVENT("event", Direction.TO_NODE, ServerEvent.class),
    /** No data; answered with pong. */
    PING("ping", Direction.BOTH, null),
    /** No data; refers to the ping. */
    PONG("pong", Direction.BOTH, null),
    /** {@link ApiError}: a frame that could not be taken (refers to it), or the end of a failed turn. */
    ERROR("error", Direction.TO_NODE, ApiError.class);

    /** Who sends a type. */
    public enum Direction {
        TO_SERVER, TO_NODE, BOTH
    }

    private final String type;
    private final Direction direction;
    private final Class<? extends Record> dataClass;

    NodeMessage(String type, Direction direction, Class<? extends Record> dataClass) {
        this.type = type;
        this.direction = direction;
        this.dataClass = dataClass;
    }

    /** The {@link Frame#type()}. */
    public String type() {
        return type;
    }

    public Direction direction() {
        return direction;
    }

    /** What {@link Frame#data()} holds, null for none. */
    public Class<? extends Record> dataClass() {
        return dataClass;
    }

    /** The type of a frame, null for one this version does not know (ignore it). */
    public static NodeMessage of(String type) {
        for (NodeMessage message : values()) {
            if (message.type.equals(type)) {
                return message;
            }
        }
        return null;
    }
}
