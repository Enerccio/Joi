package com.github.enerccio.joi.ui;

/**
 * The CSS classes of Joi's own stylesheet ({@code frontend/styles/joi-styles.css}); the shared ones are the platform's
 * {@code CssStyles}. A constant and its rule are added together.
 */
public final class JoiCssStyles {

    /** The icon in front of the caption of a workspace tab ({@code PartIcon}). */
    public static final String WORKSPACE_PART_ICON = "workspace-part-icon";

    /** A row of buttons that keeps its height when they are hidden, so what is under it does not move. */
    public static final String BUTTON_ROW = "button-row";

    private JoiCssStyles() {
    }

    /** The scrolled transcript of the Chats tab. */
    public static final String CHAT_TRANSCRIPT = "chat-transcript";
    /** The column of the cards inside the transcript. */
    public static final String CHAT_TRANSCRIPT_CONTENT = "chat-transcript-content";
    /** One exchange of the transcript. */
    public static final String CHAT_MESSAGE = "chat-message";
    /** The input of an exchange, with {@link #CHAT_INPUT_USER} or {@link #CHAT_INPUT_EVENT}. */
    public static final String CHAT_INPUT = "chat-input";
    public static final String CHAT_INPUT_USER = "chat-input-user";
    public static final String CHAT_INPUT_EVENT = "chat-input-event";
    /** An exchange asked for, her reply still to come. */
    public static final String CHAT_WAITING = "chat-waiting";
    /** The kind of an event, above its text. */
    public static final String CHAT_EVENT_KIND = "chat-event-kind";
    /** Her reply: the reasoning and the response. */
    public static final String CHAT_REPLY = "chat-reply";
    public static final String CHAT_REASONING = "chat-reasoning";
    public static final String CHAT_MARKDOWN = "chat-markdown";
    /** The folded list of the tools she used in a reply, and one of them, its arguments and result, and one that failed. */
    public static final String CHAT_TOOLS = "chat-tools";
    public static final String CHAT_TOOL = "chat-tool";
    public static final String CHAT_TOOL_DATA = "chat-tool-data";
    public static final String CHAT_TOOL_FAILED = "chat-tool-failed";
    /** When, which model, which node. */
    public static final String CHAT_META = "chat-meta";
    /** The bottom row of an exchange: its checkbox, the meta and its delete button. */
    public static final String CHAT_FOOTER = "chat-footer";
    /** An exchange checked for the mass delete. */
    public static final String CHAT_MESSAGE_CHECKED = "chat-message-checked";
    /** The row above the transcript: the hint and the delete of the checked messages. */
    public static final String CHAT_TOOLBAR = "chat-toolbar";

    /** The grid of the Memories tab. */
    public static final String MEMORY_GRID = "memory-grid";
    /** The fields of a memory, opened under its row. */
    public static final String MEMORY_EDITOR = "memory-editor";

    /** The model and body dialogs: the column of the fields beside the preview. */
    public static final String MODEL_SIDEBAR = "model-sidebar";
    /** The model and body dialogs: the split of the fields and the preview ({@code EditorSplit}). */
    public static final String EDITOR_SPLIT = "editor-split";
    /** The buttons that try the expressions of a model. */
    public static final String EXPRESSION_BUTTONS = "expression-buttons";
    /** The output of a conversion. */
    public static final String CONVERSION_LOG = "conversion-log";
    /** The body dialog: the choices of the state of each part. */
    public static final String BODY_PARTS = "body-parts";
    /** A chain of spring bones in the model dialog: its box, how it moves, its bones. */
    public static final String SPRING_CHAIN = "spring-chain";
    /** The 3D preview of a model. */
    public static final String MODEL_VIEWER = "model-viewer";
    /** The thumbnails of a body's snapshots and images. */
    public static final String BODY_THUMBNAILS = "body-thumbnails";
    /** A row of the toggles in the model dialog: the box, the name, Describe, and, on a line of its own, what it is part of. */
    public static final String TOGGLE_ROW = "toggle-row";
    /** A toggle row under others in the tree of toggles, followed by how many (1 to 3). */
    public static final String TOGGLE_DEPTH = "toggle-depth-";
    public static final String TOGGLE_GROUP = "toggle-group";
    public static final String TOGGLE_GROUP_STATES = "toggle-group-states";
    public static final String TOGGLE_GROUP_STATE = "toggle-group-state";
    /** The strip of an animation's steps under its preview, the one playing marked. */
    public static final String ANIMATION_STRIP = "animation-strip";
    public static final String JOI_ANIMATIONS = "joi-animations";
    public static final String STEP_ROTATION_TITLE = "step-rotation-title";
    public static final String STEP_ROTATION_FIELD = "step-rotation-field";
    public static final String ANIMATION_STRIP_ARROW = "animation-strip-arrow";
    public static final String ANIMATION_STEP = "animation-step";
    /** On the step of the strip playing now. */
    public static final String ANIMATION_STEP_PLAYING = "playing";
}
