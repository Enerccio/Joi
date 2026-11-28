package com.github.enerccio.joi.ui.dialogs.joi;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.chat.ChatService;
import com.github.enerccio.joi.domain.chat.ToolUsage;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;
import com.github.enerccio.joi.domain.chat.turn.TurnInput;
import com.github.enerccio.joi.domain.collections.ChatMessageType;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.ChatImageService;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.chat.ChatImages;
import com.github.enerccio.joi.domain.service.SummarizerService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.joi.ui.widgets.LongText;
import com.github.enerccio.joi.ui.JoiJavascriptLibrary;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.dialogs.ConfirmDialog;
import com.github.enerccio.marginalia.ui.JavascriptLibrary;
import com.github.enerccio.marginalia.ui.widgets.LazyLoadIndicator;
import com.github.enerccio.marginalia.ui.widgets.Notification;
import com.github.enerccio.marginalia.ui.widgets.ScrollPanel;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.Shortcuts;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.server.streams.UploadHandler;
import com.github.enerccio.marginalia.bound.ApiRequestAttributes;
import com.github.enerccio.marginalia.bound.SessionPoint;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.domain.security.service.UserService;
import com.github.enerccio.marginalia.ui.components.ThreadCopyRequestAttributes.InRequestScope;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.markdown.Markdown;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.server.StreamResource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.io.File;
import java.nio.file.Files;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Chats tab: her conversation as a transcript, oldest at the top, each exchange a card (the input, her reasoning
 * folded when she thought, her response in Markdown, when and through which model). A message is deleted by its
 * trash button, or checked (shift-click checks the range from the last one clicked) and deleted with the others.
 * <p>
 * Lazy like Marginalia's story: the tab opens with the last {@link #PAGE} messages at the bottom, and scrolling to the
 * top adds the earlier page above, keeping the message the user reads in place (the platform's {@code watchEdge} and
 * {@code restoreAnchor}). Nothing is dropped: a transcript is read backwards a little, not end to end.
 */
@Configurable
public class JoiChatsPart implements JoiDialogPart {

    /** How many messages a page has. */
    static final int PAGE = 30;

    @Autowired
    private Localization loc;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private ChatService chatService;

    @Autowired
    private SummarizerService summarizerService;

    /** Up to when the summaries cover the messages (read at each load): the cards before it are shown faded. */
    private Date covered;

    @Autowired
    private ChatImageService chatImageService;

    @Autowired
    private UserService userService;

    @Autowired
    private User currentUser;

    private final DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
    private VerticalLayout layout;
    private ScrollPanel panel;
    private LazyLoadIndicator earlierIndicator;
    private Span empty;
    private Joi joi;
    private final List<ChatMessage> shown = new ArrayList<>();
    private boolean hasEarlier;
    /** The ids of the messages checked, and the card and checkbox of each message shown. */
    private final Set<Long> checked = new LinkedHashSet<>();
    private final Map<Long, Div> cards = new HashMap<>();
    private final Map<Long, Checkbox> boxes = new HashMap<>();
    /** The message last clicked, where a shift-click's range starts. */
    private Long anchor;
    private Div toolbar;
    private Button deleteSelectedButton;
    private Button summarizeButton;
    /** What was asked for and has no reply yet, by turn, oldest first: shown below the transcript, and its card. */
    private final Map<String, Waiting> waiting = new LinkedHashMap<>();
    private Registration watching;
    /** The one player of the tab: a clip played stops the one before it. */
    private final Element audio = new Element("audio");
    /** What the user writes to her: the field, the images waiting to go with it, the attach button and Send. */
    private TextArea messageField;
    private Div pendingImages;
    private Upload attach;
    private Button sendButton;
    private final List<ChatImageService.Image> pending = new ArrayList<>();
    /** Whether she can look at images (her chat model takes them, or a model that does describes them for it), see {@link #load}. */
    private boolean canSee;

    private record Waiting(TurnInput input, Date at, Div card) {
    }

    @Override
    public J_L caption() {
        return J_L.LABEL_CHATS;
    }

    @Override
    public Component create() {
        panel = new ScrollPanel();
        panel.addClassName(JoiCssStyles.CHAT_TRANSCRIPT);
        panel.getContent().addClassName(JoiCssStyles.CHAT_TRANSCRIPT_CONTENT);
        earlierIndicator = new LazyLoadIndicator(loc.getValue(J_L.MSG_LOADING_EARLIER_MESSAGES));
        panel.setHeader(earlierIndicator);
        panel.getElement().addEventListener("edge-top", e -> loadEarlier());

        empty = new Span(loc.getValue(J_L.MSG_NO_CHATS));
        empty.addClassName(CssStyles.HINT_TEXT);

        Span hint = new Span(loc.getValue(J_L.MSG_CHATS_SELECT_HINT));
        hint.addClassName(CssStyles.HINT_TEXT);
        deleteSelectedButton = new Button(loc.getValue(L.LABEL_DELETE_SELECTED), Solid.TRASH.create(), e -> deleteChecked());
        deleteSelectedButton.setThemeName("error");
        deleteSelectedButton.setEnabled(false);
        summarizeButton = new Button(loc.getValue(J_L.LABEL_SUMMARIZE_CHECKED), Solid.COMPRESS.create(), e -> summarizeChecked());
        summarizeButton.setTooltipText(loc.getValue(J_L.MSG_CHATS_SUMMARIZE_HINT));
        summarizeButton.setEnabled(false);
        toolbar = new Div(hint, summarizeButton, deleteSelectedButton);
        toolbar.addClassName(JoiCssStyles.CHAT_TOOLBAR);

        layout = new VerticalLayout(empty, toolbar, panel, compose());
        layout.setSizeFull();
        layout.setPadding(false);
        layout.setFlexGrow(1, panel);
        layout.getElement().appendChild(audio);
        layout.addAttachListener(e -> watch(e.getUI()));
        layout.addDetachListener(e -> unwatch());
        return layout;
    }

    /** The row under the transcript where the user writes to her, with the images that go along (uploaded, pasted or dropped). */
    private Component compose() {
        messageField = new TextArea();
        messageField.setPlaceholder(loc.getValue(J_L.PLACEHOLDER_CHAT_MESSAGE));
        messageField.setWidthFull();
        messageField.setMinRows(1);
        messageField.setMaxRows(6);
        // the words are on the server when Enter is pressed: a field sends its value when it loses the focus otherwise
        messageField.setValueChangeMode(ValueChangeMode.EAGER);
        pendingImages = new Div();
        pendingImages.addClassName(JoiCssStyles.CHAT_COMPOSE_IMAGES);
        Div field = new Div(pendingImages, messageField);
        field.addClassName(JoiCssStyles.CHAT_COMPOSE_FIELD);

        UploadHandler handler = UploadHandler.toFile((metadata, file) -> imageUploaded(UI.getCurrent(), file), metadata -> {
            try {
                return chatImageService.newUploadFile();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        attach = new Upload(handler);
        attach.addClassName(JoiCssStyles.CHAT_COMPOSE_UPLOAD);
        attach.setAcceptedFileTypes("image/*");
        attach.setMaxFiles(ChatImageService.MAX_IMAGES);
        attach.setMaxFileSize((int) ChatImages.MAX_BYTES);
        attach.setDropAllowed(false);
        attach.setUploadButton(new Button(Solid.IMAGE.create()));
        attach.getElement().setAttribute("title", loc.getValue(J_L.LABEL_ATTACH_IMAGE));
        attach.addAllFinishedListener(e -> attach.clearFileList());
        attach.addFileRejectedListener(e -> Notification.warning(String.format(loc.getValue(J_L.MSG_CHAT_IMAGE_FAILED), e.getErrorMessage())));
        JoiJavascriptLibrary.takeImages(messageField, attach);

        sendButton = new Button(loc.getValue(J_L.LABEL_SEND_MESSAGE), Solid.PAPER_PLANE.create(), e -> send());
        sendButton.setThemeName("primary");
        // Enter sends, Shift+Enter is a new line
        Shortcuts.addShortcutListener(messageField, this::send, Key.ENTER).listenOn(messageField);
        Div compose = new Div(field, attach, sendButton);
        compose.addClassName(JoiCssStyles.CHAT_COMPOSE);
        compose.setWidthFull();
        return compose;
    }

    /** An image arrived (chosen, pasted or dropped): it is kept, and waits next to the field to go with the next message. */
    private void imageUploaded(UI ui, File file) {
        try {
            if (pending.size() >= ChatImageService.MAX_IMAGES) {
                file.delete();
                ui.access(() -> Notification.warning(String.format(loc.getValue(J_L.MSG_CHAT_IMAGES_FULL), ChatImageService.MAX_IMAGES)));
                return;
            }
            ChatImageService.Image image = chatImageService.store(file);
            ui.access(() -> {
                pending.add(image);
                showPending();
            });
        } catch (ChatImages.BadImageException e) {
            ui.access(() -> Notification.warning(String.format(loc.getValue(J_L.MSG_CHAT_IMAGE_FAILED), e.getMessage())));
        } catch (Exception e) {
            ui.access(() -> UIUtils.internalServerError(loc, e));
        }
    }

    /** The images waiting to go with the message, each with a button that takes it away. */
    private void showPending() {
        pendingImages.removeAll();
        for (ChatImageService.Image image : pending) {
            Div thumb = new Div();
            thumb.addClassName(JoiCssStyles.CHAT_COMPOSE_IMAGE);
            Button remove = new Button(Solid.XMARK.create(), e -> {
                pending.remove(image);
                showPending();
            });
            remove.setThemeName("tertiary small");
            remove.setAriaLabel(loc.getValue(L.LABEL_REMOVE));
            thumb.add(imageOf(image.id()), remove);
            pendingImages.add(thumb);
        }
    }

    /** The image of the user as a component, a thumbnail the size of the card's (it is the stored JPEG, scaled by the browser). */
    private Image imageOf(String uuid) {
        return imageOf(uuid, J_L.LABEL_CHAT_IMAGE);
    }

    private Image imageOf(String uuid, J_L label) {
        StreamResource resource = new StreamResource("image-" + uuid + ".jpg", () -> {
            try {
                File file = chatImageService.file(uuid);
                return file == null ? java.io.InputStream.nullInputStream() : Files.newInputStream(file.toPath());
            } catch (Exception e) {
                throw new java.io.UncheckedIOException(new java.io.IOException(e));
            }
        });
        resource.setContentType("image/jpeg");
        Image image = new Image(resource, loc.getValue(label));
        image.addClassName(JoiCssStyles.CHAT_IMAGE);
        return image;
    }

    /** The images of a message in the transcript: each a link to the whole image. */
    private Component imagesOf(List<String> uuids) {
        return imagesOf(uuids, J_L.LABEL_CHAT_IMAGE);
    }

    private Component imagesOf(List<String> uuids, J_L label) {
        Div images = new Div();
        images.addClassName(JoiCssStyles.CHAT_IMAGES);
        for (String uuid : uuids) {
            Image image = imageOf(uuid, label);
            Anchor link = new Anchor(image.getSrc(), "");
            link.setTarget("_blank");
            link.add(image);
            images.add(link);
        }
        return images;
    }

    /** Sends what is in the field, with the images that wait. */
    private void send() {
        String text = StringUtils.strip(messageField.getValue());
        List<String> images = pending.stream().map(ChatImageService.Image::id).toList();
        if (joi == null || (StringUtils.isEmpty(text) && images.isEmpty())) {
            return;
        }
        try {
            // what can be refused is refused here, while the user is looking: no model, no way to look at images, an image that is gone
            chatService.checkImages(joi, images);
        } catch (ChatService.NoVisionException e) {
            Notification.warning(loc.getValue(J_L.MSG_CHAT_NO_VISION));
            return;
        } catch (ChatService.NoModelException | IllegalArgumentException e) {
            Notification.warning(String.format(loc.getValue(J_L.MSG_CHAT_SEND_FAILED), e.getMessage()));
            return;
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
            return;
        }
        messageField.clear();
        pending.clear();
        showPending();
        ask(new TurnInput(StringUtils.defaultString(text), null, false, false, null, images));
    }

    /**
     * Asks her the turn in the request scope of the user, which must outlive the turn (the one of this request ends with it): the
     * conversation shows it as it shows every turn ({@link #watch}), so nothing is told here but a failure.
     */
    private void ask(TurnInput input) {
        UI ui = UI.getCurrent();
        Joi asked = joi;
        User owner;
        try {
            owner = userService.find(currentUser.getId());
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
            return;
        }
        Thread.ofVirtual().name("joi-web-chat").start(() -> {
            try (ApiRequestAttributes attributes = new ApiRequestAttributes(owner, new SessionPoint());
                 InRequestScope _ = new InRequestScope(attributes)) {
                chatService.chat(asked, input, null).get();
            } catch (java.util.concurrent.CancellationException | InterruptedException e) {
                // stopped
            } catch (Exception e) {
                Throwable cause = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause() : e;
                ui.access(() -> Notification.warning(String.format(loc.getValue(J_L.MSG_CHAT_SEND_FAILED), StringUtils.defaultIfBlank(cause.getMessage(),
                        cause.getClass().getSimpleName()))));
            }
        });
    }

    /** For tests: writes and sends as the user does. */
    void sendForTest(String text, List<String> images) {
        messageField.setValue(text);
        pending.clear();
        for (String id : images) {
            try {
                pending.add(chatImageService.find(id));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        send();
    }

    @Override
    public void load(Joi joi) throws Exception {
        this.joi = joi;
        canSee = chatImageService.canSee(chatService.chatModel(joi));
        attach.setEnabled(canSee);
        attach.getElement().setAttribute("title", loc.getValue(canSee ? J_L.LABEL_ATTACH_IMAGE : J_L.MSG_CHAT_NO_VISION));
        shown.clear();
        cards.clear();
        boxes.clear();
        checked.clear();
        anchor = null;
        panel.removeAll();
        covered = summarizerService.coveredUpTo(joi);
        List<ChatMessage> latest = chatMessageService.findLatest(joi, PAGE + 1);
        hasEarlier = latest.size() > PAGE;
        if (hasEarlier) {
            latest = latest.subList(1, latest.size());
        }
        for (ChatMessage message : latest) {
            panel.add(card(message));
        }
        shown.addAll(latest);
        waiting.replaceAll((turn, w) -> new Waiting(w.input(), w.at(), waitingCard(w.input(), w.at())));
        waiting.values().forEach(w -> panel.add(w.card()));
        showEmpty();
        checkedChanged();
        settle(false);
        JavascriptLibrary.scrollToBottomAfterLayout(panel.getElement());
        watch(UI.getCurrent());
    }

    /** Follows the conversation from now on: what is asked for shows as it is, her reply as it is stored. */
    private void watch(UI ui) {
        unwatch();
        if (joi == null || ui == null) {
            return;
        }
        watching = chatService.watch(joi, new ChatService.Feed() {
            @Override
            public void queued(String turn, TurnInput input, Date at) {
                ui.access(() -> asked(turn, input, at));
            }

            @Override
            public void finished(String turn, ChatMessage message) {
                ui.access(() -> answered(turn, message));
            }

            @Override
            public void voiced(ChatMessage message) {
                ui.access(() -> refresh(message));
            }

            @Override
            public void logged(ChatMessage message) {
                ui.access(() -> answered(null, message));
            }
        });
    }

    private void unwatch() {
        if (watching != null) {
            watching.unregister();
            watching = null;
        }
    }

    /** A turn was asked for: its input shows at the bottom, waiting for her reply. */
    private void asked(String turn, TurnInput input, Date at) {
        if (waiting.containsKey(turn)) {
            return;
        }
        Div card = waitingCard(input, at);
        waiting.put(turn, new Waiting(input, at, card));
        panel.add(card);
        showEmpty();
        followEnd();
    }

    /** The turn ended: its waiting card goes, and the message it stored (none when it was stopped) takes its place. */
    private void answered(String turn, ChatMessage message) {
        fadeSummarized();
        Waiting done = turn == null ? null : waiting.remove(turn);
        if (done != null) {
            panel.remove(done.card());
        }
        if (message != null && !cards.containsKey(message.getId())) {
            Component card = card(message);
            if (waiting.isEmpty()) {
                panel.add(card);
            } else {
                panel.getContent().addComponentAtIndex(panel.getContent().indexOf(waiting.values().iterator().next().card()), card);
            }
            shown.add(message);
        }
        showEmpty();
        followEnd();
    }

    /** A summary may have been written meanwhile (it is told to her as an event): the cards it covers are faded now. */
    private void fadeSummarized() {
        try {
            Date now = summarizerService.coveredUpTo(joi);
            if (now == null || now.equals(covered)) {
                return;
            }
            covered = now;
            for (ChatMessage shownMessage : shown) {
                Div card = cards.get(shownMessage.getId());
                if (card != null && shownMessage.getSentAt() != null && shownMessage.getSentAt().before(covered)
                        && !card.hasClassName(JoiCssStyles.CHAT_MESSAGE_SUMMARIZED)) {
                    card.addClassName(JoiCssStyles.CHAT_MESSAGE_SUMMARIZED);
                    card.getElement().setAttribute("title", loc.getValue(J_L.LABEL_CHAT_SUMMARIZED));
                }
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(JoiChatsPart.class).warn("Could not read what the summaries cover", e);
        }
    }

    /** The message got its clip: its card is made again, with the play button. */
    private void refresh(ChatMessage message) {
        Div old = cards.get(message.getId());
        if (old == null) {
            return;
        }
        int at = panel.getContent().indexOf(old);
        int shownAt = indexOf(message.getId());
        Component card = card(message);
        panel.getContent().remove(old);
        panel.getContent().addComponentAtIndex(at, card);
        if (shownAt >= 0) {
            shown.set(shownAt, message);
        }
    }

    /** Plays the message's clip, from its start. */
    private void play(ChatMessage message) {
        try {
            File file = chatMessageService.clipFile(message);
            if (file == null || !file.isFile()) {
                return;
            }
            String mime = chatMessageService.clipMime(message);
            StreamResource resource = new StreamResource("voice-" + message.getId() + "." + (file.getName().endsWith(".mp3") ? "mp3" : "wav"),
                    () -> {
                        try {
                            return Files.newInputStream(file.toPath());
                        } catch (java.io.IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    });
            resource.setContentType(mime);
            audio.callJsFunction("pause");
            audio.setAttribute("src", resource);
            audio.callJsFunction("play");
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** Scrolls to the new end, unless the user has scrolled up to read. */
    private void followEnd() {
        panel.getElement().executeJs("""
                const el = $0;
                if (el._stick === false) return;
                requestAnimationFrame(() => { el.scrollTop = el.scrollHeight; el._lastTop = el.scrollTop; });
                """, panel.getElement());
    }

    /** What was asked, with her reply still to come. */
    private Div waitingCard(TurnInput input, Date at) {
        Div card = new Div();
        card.addClassNames(JoiCssStyles.CHAT_MESSAGE, JoiCssStyles.CHAT_WAITING);
        Div text = new Div();
        text.addClassNames(JoiCssStyles.CHAT_INPUT, input.isEvent() ? JoiCssStyles.CHAT_INPUT_EVENT : JoiCssStyles.CHAT_INPUT_USER);
        if (input.isEvent()) {
            Span kind = new Span(loc.getValue(J_L.LABEL_EVENT) + " · " + input.eventKind());
            kind.addClassName(JoiCssStyles.CHAT_EVENT_KIND);
            text.add(kind);
        }
        if (!input.images().isEmpty()) {
            text.add(imagesOf(input.images()));
        }
        text.add(new Text(StringUtils.defaultString(input.text())));
        Span state = new Span(loc.getValue(J_L.MSG_CHAT_WAITING));
        state.addClassName(JoiCssStyles.CHAT_META);
        Span info = new Span(format.format(at));
        info.addClassName(JoiCssStyles.CHAT_META);
        card.add(text, new Div(info, new Text(" · "), state));
        return card;
    }

    /** The tab was selected: its panel had no size while hidden, so it scrolls to the end now. */
    @Override
    public void shown() throws Exception {
        load(joi);
    }

    private void loadEarlier() {
        if (!hasEarlier || shown.isEmpty()) {
            return;
        }
        try {
            List<ChatMessage> earlier = chatMessageService.findBefore(joi, shown.getFirst(), PAGE + 1);
            hasEarlier = earlier.size() > PAGE;
            if (hasEarlier) {
                earlier = earlier.subList(1, earlier.size());
            }
            for (ChatMessage message : earlier.reversed()) {
                panel.addComponentAsFirst(card(message));
            }
            shown.addAll(0, earlier);
            settle(true);
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void showEmpty() {
        boolean none = shown.isEmpty() && waiting.isEmpty();
        empty.setVisible(none);
        toolbar.setVisible(!none);
        panel.setVisible(!none);
    }

    /**
     * A message's checkbox was clicked: it flips (as the server knows it), and with shift the messages between it and
     * the one clicked before take the same state.
     */
    private void clicked(ChatMessage message, boolean shift) {
        Long id = message.getId();
        boolean on = !checked.contains(id);
        int to = indexOf(id);
        int from = shift && anchor != null ? indexOf(anchor) : -1;
        if (from < 0) {
            from = to;
        }
        for (int i = Math.min(from, to); i <= Math.max(from, to); i++) {
            setChecked(shown.get(i).getId(), on);
        }
        anchor = id;
        checkedChanged();
    }

    private int indexOf(Long id) {
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private void setChecked(Long id, boolean on) {
        if (on) {
            checked.add(id);
        } else {
            checked.remove(id);
        }
        Checkbox box = boxes.get(id);
        if (box != null) {
            box.setValue(on);
        }
        Div card = cards.get(id);
        if (card != null) {
            card.setClassName(JoiCssStyles.CHAT_MESSAGE_CHECKED, on);
        }
    }

    private void checkedChanged() {
        deleteSelectedButton.setEnabled(!checked.isEmpty());
        summarizeButton.setEnabled(!checked.isEmpty());
        deleteSelectedButton.setText(checked.isEmpty() ? loc.getValue(L.LABEL_DELETE_SELECTED)
                : loc.getValue(L.LABEL_DELETE_SELECTED) + " (" + checked.size() + ")");
    }

    /**
     * Summarizes the checked messages. A summary never leaves a gap: it starts where the one before it ended, so all the
     * messages from there to the last one checked are checked too (the ones that are summarized already are not), and the
     * user is asked about that range before it is queued. Cancelled, the selection is as it was.
     */
    private void summarizeChecked() {
        try {
            ChatMessage last = null;
            for (ChatMessage message : shown) {
                if (checked.contains(message.getId()) && message.getType() != ChatMessageType.LOG) {
                    last = message;
                }
            }
            if (last == null) {
                return;
            }
            List<ChatMessage> range = summarizerService.rangeUpTo(joi, last);
            if (range.isEmpty()) {
                Notification.warning(loc.getValue(J_L.MSG_SUMMARY_NOTHING));
                return;
            }
            Set<Long> before = new LinkedHashSet<>(checked);
            Set<Long> wanted = new LinkedHashSet<>();
            range.forEach(m -> wanted.add(m.getId()));
            for (ChatMessage message : shown) {
                setChecked(message.getId(), wanted.contains(message.getId()));
            }
            checkedChanged();
            ChatMessage through = last;
            ConfirmDialog.show(String.format(loc.getValue(J_L.MSG_CONFIRM_SUMMARIZE), range.size(), format.format(range.getFirst().getSentAt()),
                    format.format(range.getLast().getSentAt())), () -> summarize(through), () -> restoreChecked(before));
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void summarize(ChatMessage last) {
        try {
            summarizerService.summarize(joi, last);
            new ArrayList<>(checked).forEach(id -> setChecked(id, false));
            checkedChanged();
            Notification.success(loc.getValue(J_L.MSG_SUMMARY_QUEUED));
        } catch (IllegalArgumentException | IllegalStateException e) {
            Notification.warning(e.getMessage());
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void restoreChecked(Set<Long> before) {
        for (ChatMessage message : shown) {
            setChecked(message.getId(), before.contains(message.getId()));
        }
        checkedChanged();
    }

    private void deleteChecked() {
        List<Long> ids = List.copyOf(checked);
        if (ids.isEmpty()) {
            return;
        }
        ConfirmDialog.show(String.format(loc.getValue(J_L.MSG_CONFIRM_DELETE_CHATS), ids.size()), () -> delete(ids));
    }

    private void deleteOne(ChatMessage message) {
        ConfirmDialog.show(loc.getValue(J_L.MSG_CONFIRM_DELETE_CHAT), () -> delete(List.of(message.getId())));
    }

    /** Deletes the messages and takes their cards away, leaving the rest of the transcript where it is. */
    private void delete(List<Long> ids) {
        try {
            chatMessageService.deleteMessages(joi, ids);
            for (Long id : ids) {
                Div card = cards.remove(id);
                if (card != null) {
                    panel.remove(card);
                }
                boxes.remove(id);
                checked.remove(id);
                if (id.equals(anchor)) {
                    anchor = null;
                }
            }
            shown.removeIf(message -> ids.contains(message.getId()));
            if (shown.isEmpty() && hasEarlier) {
                load(joi);
                return;
            }
            showEmpty();
            checkedChanged();
            settle(false);
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void settle(boolean keepPosition) {
        earlierIndicator.setVisible(hasEarlier);
        if (keepPosition) {
            JavascriptLibrary.restoreAnchor(panel.getElement(), panel.getContent().getElement());
        }
        JavascriptLibrary.watchEdge(panel.getElement(), panel.getContent().getElement(), true, hasEarlier);
    }

    /** How much of an argument or a result is shown: a page a tool read can be long, the whole of it stays stored. */
    private static final int TOOL_TEXT = 3000;

    /** The tools she used in the reply, all of them in the order they happened, folded: each with its arguments and result. */
    private Component toolsList(List<ToolUsage> tools) {
        Div list = new Div();
        for (ToolUsage tool : tools) {
            Div entry = new Div();
            entry.addClassName(JoiCssStyles.CHAT_TOOL);
            if (tool.failed()) {
                entry.addClassName(JoiCssStyles.CHAT_TOOL_FAILED);
            }
            Element bold = new Element("b");
            bold.setText(tool.name() + (tool.failed() ? " (" + loc.getValue(J_L.LABEL_TOOL_FAILED) + ")" : ""));
            entry.getElement().appendChild(bold);
            String arguments = StringUtils.trimToEmpty(tool.arguments());
            if (!arguments.isEmpty() && !"{}".equals(arguments)) {
                entry.add(toolData(arguments));
            }
            if (StringUtils.isNotBlank(tool.result())) {
                entry.add(toolData("→ " + tool.result()));
            }
            list.add(entry);
        }
        Details details = new Details(loc.getValue(J_L.LABEL_TOOL_CALLS) + " (" + tools.size() + "): "
                + tools.stream().map(ToolUsage::name).distinct().collect(java.util.stream.Collectors.joining(", ")), list);
        details.addClassName(JoiCssStyles.CHAT_TOOLS);
        return details;
    }

    private Component toolData(String text) {
        return new LongText(text, TOOL_TEXT, loc.getValue(J_L.LABEL_SHOW_ALL));
    }

    /** One exchange: the input on the right (an event in the middle), her reply on the left. */
    Component card(ChatMessage message) {
        Div card = new Div();
        card.addClassName(JoiCssStyles.CHAT_MESSAGE);
        if (covered != null && message.getSentAt() != null && message.getSentAt().before(covered)) {
            // a summary stands in its place: it is no longer given to her as a message
            card.addClassName(JoiCssStyles.CHAT_MESSAGE_SUMMARIZED);
            card.getElement().setAttribute("title", loc.getValue(J_L.LABEL_CHAT_SUMMARIZED));
        }

        boolean event = message.getType() == com.github.enerccio.joi.domain.collections.ChatMessageType.EVENT;
        boolean note = message.getType() == com.github.enerccio.joi.domain.collections.ChatMessageType.LOG;
        if (StringUtils.isNotBlank(message.getInput()) || event || !message.imageUuids().isEmpty()) {
            Div input = new Div();
            input.addClassNames(JoiCssStyles.CHAT_INPUT, note ? JoiCssStyles.CHAT_INPUT_LOG : event ? JoiCssStyles.CHAT_INPUT_EVENT : JoiCssStyles.CHAT_INPUT_USER);
            if (event || note) {
                Span kind = new Span(loc.getValue(note ? J_L.LABEL_CHAT_NOTE : J_L.LABEL_EVENT)
                        + (StringUtils.isBlank(message.getEventKind()) ? "" : " · " + message.getEventKind()));
                kind.addClassName(JoiCssStyles.CHAT_EVENT_KIND);
                input.add(kind);
            }
            if (!message.imageUuids().isEmpty()) {
                input.add(imagesOf(message.imageUuids()));
            }
            input.add(new Text(StringUtils.defaultString(message.getInput())));
            card.add(input);
        }

        Div reply = new Div();
        reply.addClassName(JoiCssStyles.CHAT_REPLY);
        if (StringUtils.isNotBlank(message.getResponseReasoning())) {
            Markdown reasoning = new Markdown(message.getResponseReasoning());
            reasoning.addClassName(JoiCssStyles.CHAT_MARKDOWN);
            Details details = new Details(loc.getValue(J_L.LABEL_REASONING), reasoning);
            details.addClassName(JoiCssStyles.CHAT_REASONING);
            reply.add(details);
        }
        List<ToolUsage> tools = ToolUsage.fromJson(message.getToolCalls());
        if (!tools.isEmpty()) {
            reply.add(toolsList(tools));
        }
        if (!message.toolImageUuids().isEmpty()) {
            // the pictures her tools brought (a page's screenshot)
            reply.add(imagesOf(message.toolImageUuids(), J_L.LABEL_CHAT_TOOL_IMAGE));
        }
        if (StringUtils.isNotBlank(message.getResponse())) {
            Markdown response = new Markdown(message.getResponse());
            response.addClassName(JoiCssStyles.CHAT_MARKDOWN);
            reply.add(response);
        }
        if (reply.getComponentCount() > 0) {
            card.add(reply);
        }

        List<String> meta = new ArrayList<>();
        if (message.getSentAt() != null) {
            meta.add(format.format(message.getSentAt()));
        }
        if (StringUtils.isNotBlank(message.getModelUsed())) {
            meta.add(message.getModelUsed());
        }
        if (StringUtils.isNotBlank(message.getNode())) {
            meta.add(message.getNode());
        }
        Span info = new Span(String.join(" · ", meta));
        info.addClassName(JoiCssStyles.CHAT_META);

        Checkbox box = new Checkbox(checked.contains(message.getId()));
        box.addClickListener(e -> clicked(message, e.isShiftKey()));
        JoiJavascriptLibrary.noShiftSelect(box);
        Button delete = new Button(Solid.TRASH.create(), e -> deleteOne(message));
        delete.setThemeName("error tertiary small");
        delete.setTooltipText(loc.getValue(L.LABEL_DELETE));
        delete.setAriaLabel(loc.getValue(L.LABEL_DELETE));
        Div footer = new Div(box, info, delete);
        if (message.getClipResourceId() != null) {
            Button play = new Button(Solid.PLAY.create(), e -> play(message));
            play.setThemeName("tertiary small");
            play.setTooltipText(loc.getValue(J_L.LABEL_PLAY));
            play.setAriaLabel(loc.getValue(J_L.LABEL_PLAY));
            footer.addComponentAtIndex(2, play);
        }
        footer.addClassName(JoiCssStyles.CHAT_FOOTER);
        card.add(footer);
        card.setClassName(JoiCssStyles.CHAT_MESSAGE_CHECKED, checked.contains(message.getId()));
        cards.put(message.getId(), card);
        boxes.put(message.getId(), box);
        return card;
    }

    /** The messages on the screen, oldest first, for tests. */
    List<ChatMessage> getShown() {
        return shown;
    }

    /** A click on the message's checkbox, with or without shift, for tests. */
    void clickForTest(ChatMessage message, boolean shift) {
        clicked(message, shift);
    }

    /** The ids of the checked messages, for tests. */
    Set<Long> getChecked() {
        return checked;
    }

    /** Presses Summarize, then confirms (true) or cancels (false) the question, for tests. */
    void summarizeForTest(boolean confirm) {
        ChatMessage last = null;
        for (ChatMessage message : shown) {
            if (checked.contains(message.getId())) {
                last = message;
            }
        }
        try {
            List<ChatMessage> range = summarizerService.rangeUpTo(joi, last);
            Set<Long> before = new LinkedHashSet<>(checked);
            Set<Long> wanted = new LinkedHashSet<>();
            range.forEach(m -> wanted.add(m.getId()));
            for (ChatMessage message : shown) {
                setChecked(message.getId(), wanted.contains(message.getId()));
            }
            checkedChanged();
            if (confirm) {
                summarize(last);
            } else {
                restoreChecked(before);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Deletes the checked messages as the confirmed button does, for tests. */
    void deleteCheckedForTest() {
        delete(List.copyOf(checked));
    }

    /** Loads the earlier page as scrolling to the top does, for tests. */
    void scrolledToTop() {
        loadEarlier();
    }
}
