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
    /** The kind of an event, above its text. */
    public static final String CHAT_EVENT_KIND = "chat-event-kind";
    /** Her reply: the reasoning and the response. */
    public static final String CHAT_REPLY = "chat-reply";
    public static final String CHAT_REASONING = "chat-reasoning";
    public static final String CHAT_MARKDOWN = "chat-markdown";
    /** When, which model, which node. */
    public static final String CHAT_META = "chat-meta";

    /** The model dialog: the column of the fields beside the preview. */
    public static final String MODEL_SIDEBAR = "model-sidebar";
    /** The buttons that try the expressions of a model. */
    public static final String EXPRESSION_BUTTONS = "expression-buttons";
    /** The output of a conversion. */
    public static final String CONVERSION_LOG = "conversion-log";
    /** The 3D preview of a model. */
    public static final String MODEL_VIEWER = "model-viewer";
}
