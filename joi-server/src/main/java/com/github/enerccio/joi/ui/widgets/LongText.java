package com.github.enerccio.joi.ui.widgets;

import com.github.enerccio.joi.ui.JoiCssStyles;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import org.apache.commons.lang3.StringUtils;

/**
 * A text that can be long (what a tool answered, the arguments she gave it): the start of it, with a button that shows the
 * whole of it when it was cut. Plain text, kept as it is (line breaks and all, see {@link JoiCssStyles#CHAT_TOOL_DATA}).
 */
public final class LongText extends Div {

    /**
     * @param text     the whole text
     * @param limit    how many characters are shown at first
     * @param showAll  the text of the button, with {@code %d} for the length of the whole text
     */
    public LongText(String text, int limit, String showAll) {
        addClassName(JoiCssStyles.CHAT_TOOL_DATA);
        String whole = StringUtils.defaultString(text);
        Text shown = new Text(StringUtils.abbreviate(whole, limit));
        add(shown);
        if (whole.length() > limit) {
            Button more = new Button(String.format(showAll, whole.length()));
            more.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            more.addClickListener(e -> {
                shown.setText(whole);
                remove(more);
            });
            add(more);
        }
    }
}
