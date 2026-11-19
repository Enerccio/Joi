package com.github.enerccio.joi.ui.dialogs.joi;

import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.JavascriptLibrary;
import com.github.enerccio.marginalia.ui.widgets.LazyLoadIndicator;
import com.github.enerccio.marginalia.ui.widgets.ScrollPanel;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Text;
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
import java.util.List;

/**
 * The Chats tab: her conversation as a transcript, oldest at the top, each exchange a card (the input, her reasoning
 * folded when she thought, her response in Markdown, when and through which model). Read only for now.
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

        layout = new VerticalLayout(empty, panel);
        layout.setSizeFull();
        layout.setPadding(false);
        layout.setFlexGrow(1, panel);
        return layout;
    }

    @Override
    public void load(Joi joi) throws Exception {
        this.joi = joi;
        shown.clear();
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
        empty.setVisible(shown.isEmpty());
        panel.setVisible(!shown.isEmpty());
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
        card.add(info);
        return card;
    }

    /** The messages on the screen, oldest first, for tests. */
    List<ChatMessage> getShown() {
        return shown;
    }

    /** Loads the earlier page as scrolling to the top does, for tests. */
    void scrolledToTop() {
        loadEarlier();
    }
}
