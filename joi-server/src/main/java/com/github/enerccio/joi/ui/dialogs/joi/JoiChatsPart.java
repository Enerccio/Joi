package com.github.enerccio.joi.ui.dialogs.joi;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.joi.ui.JoiJavascriptLibrary;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.dialogs.ConfirmDialog;
import com.github.enerccio.marginalia.ui.JavascriptLibrary;
import com.github.enerccio.marginalia.ui.widgets.LazyLoadIndicator;
import com.github.enerccio.marginalia.ui.widgets.ScrollPanel;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.markdown.Markdown;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.HashMap;
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
        toolbar = new Div(hint, deleteSelectedButton);
        toolbar.addClassName(JoiCssStyles.CHAT_TOOLBAR);

        layout = new VerticalLayout(empty, toolbar, panel);
        layout.setSizeFull();
        layout.setPadding(false);
        layout.setFlexGrow(1, panel);
        return layout;
    }

    @Override
    public void load(Joi joi) throws Exception {
        this.joi = joi;
        shown.clear();
        cards.clear();
        boxes.clear();
        checked.clear();
        anchor = null;
        panel.removeAll();
        List<ChatMessage> latest = chatMessageService.findLatest(joi, PAGE + 1);
        hasEarlier = latest.size() > PAGE;
        if (hasEarlier) {
            latest = latest.subList(1, latest.size());
        }
        for (ChatMessage message : latest) {
            panel.add(card(message));
        }
        shown.addAll(latest);
        showEmpty();
        checkedChanged();
        settle(false);
        JavascriptLibrary.scrollToBottomAfterLayout(panel.getElement());
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
        empty.setVisible(shown.isEmpty());
        toolbar.setVisible(!shown.isEmpty());
        panel.setVisible(!shown.isEmpty());
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
        deleteSelectedButton.setText(checked.isEmpty() ? loc.getValue(L.LABEL_DELETE_SELECTED)
                : loc.getValue(L.LABEL_DELETE_SELECTED) + " (" + checked.size() + ")");
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

    /** One exchange: the input on the right (an event in the middle), her reply on the left. */
    private Component card(ChatMessage message) {
        Div card = new Div();
        card.addClassName(JoiCssStyles.CHAT_MESSAGE);

        boolean event = message.getType() == com.github.enerccio.joi.domain.collections.ChatMessageType.EVENT;
        if (StringUtils.isNotBlank(message.getInput()) || event) {
            Div input = new Div();
            input.addClassNames(JoiCssStyles.CHAT_INPUT, event ? JoiCssStyles.CHAT_INPUT_EVENT : JoiCssStyles.CHAT_INPUT_USER);
            if (event) {
                Span kind = new Span(loc.getValue(J_L.LABEL_EVENT)
                        + (StringUtils.isBlank(message.getEventKind()) ? "" : " · " + message.getEventKind()));
                kind.addClassName(JoiCssStyles.CHAT_EVENT_KIND);
                input.add(kind);
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

    /** Deletes the checked messages as the confirmed button does, for tests. */
    void deleteCheckedForTest() {
        delete(List.copyOf(checked));
    }

    /** Loads the earlier page as scrolling to the top does, for tests. */
    void scrolledToTop() {
        loadEarlier();
    }
}
