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
    /** A note of the system in the transcript: not an exchange, not for the model. */
    public static final String CHAT_INPUT_LOG = "chat-input-log";
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
    /** The images of a message of the user, and one of them. */
    public static final String CHAT_IMAGES = "chat-images";
    public static final String CHAT_IMAGE = "chat-image";
    /** The row under the transcript where the user writes to her: the field, the images waiting to go with it, the buttons. */
    public static final String CHAT_COMPOSE = "chat-compose";
    public static final String CHAT_COMPOSE_FIELD = "chat-compose-field";
    public static final String CHAT_COMPOSE_IMAGES = "chat-compose-images";
    public static final String CHAT_COMPOSE_IMAGE = "chat-compose-image";
    /** The upload component that the paste and the attach button drive: its own buttons are hidden. */
    public static final String CHAT_COMPOSE_UPLOAD = "chat-compose-upload";
    /** An exchange checked for the mass delete. */
    public static final String CHAT_MESSAGE_CHECKED = "chat-message-checked";
    /** A message a summary covers: no longer given to her, shown faded. */
    public static final String CHAT_MESSAGE_SUMMARIZED = "chat-message-summarized";
    /** The row above the transcript: the hint and the delete of the checked messages. */
    public static final String CHAT_TOOLBAR = "chat-toolbar";

    /** A summary no longer shown to her: a longer one covers its time. */
    public static final String SUMMARY_COVERED = "summary-covered";
    /** The queue of the summaries above the list of the Summaries tab. */
    public static final String SUMMARY_JOBS = "summary-jobs";
    public static final String SUMMARY_PANEL = "summary-panel";

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
    /** The outfits of a body in the body dialog: a row each. */
    public static final String BODY_OUTFIT = "body-outfit";
    public static final String BODY_OUTFITS = "body-outfits";
    public static final String BODY_OUTFIT_TEXT = "body-outfit-text";
    public static final String BODY_OUTFIT_DESCRIPTION = "body-outfit-description";
    /** The boxes of the toggles in the outfit dialog. */
    public static final String OUTFIT_TOGGLES = "outfit-toggles";
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
    /** Her stance and its End button in the Animations tab of her dialog. */
    public static final String JOI_STANCE = "joi-stance";
    public static final String JOI_PROFILES = "joi-profiles";
    public static final String STEP_ROTATION_TITLE = "step-rotation-title";
    public static final String STEP_ROTATION_FIELD = "step-rotation-field";
    public static final String ANIMATION_STRIP_ARROW = "animation-strip-arrow";
    public static final String ANIMATION_STEP = "animation-step";
    /** On the step of the strip playing now. */
    public static final String ANIMATION_STEP_PLAYING = "playing";

    /** Administration and User Settings → Tools: the column of the permissions and the web options (scrolls). */
    public static final String TOOLS_PANEL = "tools-panel";
    /** The rows of the permissions of the tools. */
    public static final String TOOL_PERMISSIONS = "tool-permissions";
    /** The grid of the search sources. */
    public static final String WEB_SOURCES = "web-sources";
    /** What a test of a source or a page found, or why it failed. */
    public static final String TEST_RESULT = "test-result";
    /** The Tasks tab: what the selected task asked, found and did, under the grid. */
    public static final String TASK_DETAILS = "task-details";
    /** A message of a task's transcript. */
    public static final String TASK_STEP = "task-step";
    /** The line of facts above a task's details: tool, model, when, how long. */
    public static final String TASK_META = "task-meta";
    /** A block of a task's details (what was asked, the result, the error). */
    public static final String TASK_BLOCK = "task-block";
    /** What the task found: the block that stands out. */
    public static final String TASK_RESULT = "task-result";
    /** Why the task failed. */
    public static final String TASK_ERROR = "task-error";
    /** The head of a step of the transcript: its number, model and tokens. */
    public static final String TASK_STEP_HEAD = "task-step-head";
    /** The QR code of Pair a phone: on white, so a camera reads it in the dark theme too. */
    public static final String PAIR_QR = "pair-qr";
    /** On the (i) of a template whose text differs from the one it inherits (red). */
    public static final String TEMPLATE_OVERRIDDEN = "template-overridden";
    /** The line in the popover of such a template that says so. */
    public static final String TEMPLATE_OVERRIDDEN_NOTE = "template-overridden-note";
    /** The two texts of the compare dialog of a template, side by side. */
    public static final String TEMPLATE_COMPARE = "template-compare";
}
