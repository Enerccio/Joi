package com.github.enerccio.joi.ui.dialogs.joi;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.Localization;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

/**
 * A tab that has nothing to show yet: the summaries come with the conversation.
 */
@Configurable
public class JoiNothingYetPart implements JoiDialogPart {

    @Autowired
    private Localization loc;

    private final J_L caption;

    public JoiNothingYetPart(J_L caption) {
        this.caption = caption;
    }

    @Override
    public J_L caption() {
        return caption;
    }

    @Override
    public Component create() {
        Span text = new Span(loc.getValue(J_L.MSG_NOTHING_YET));
        text.addClassName(CssStyles.HINT_TEXT);
        VerticalLayout layout = new VerticalLayout(text);
        layout.setSizeFull();
        return layout;
    }

    @Override
    public void load(Joi joi) {
    }
}
