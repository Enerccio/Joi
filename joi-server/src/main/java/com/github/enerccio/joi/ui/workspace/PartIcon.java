package com.github.enerccio.joi.ui.workspace;

import com.github.enerccio.joi.ui.JoiCssStyles;
import com.vaadin.flow.component.html.Span;

/**
 * The icons of the parts of the {@link JoiWorkspace}: line drawings on a 24 x 24 grid, a round stroke in the color of
 * the text (so they follow the theme and the selected tab) and no fill. Size and stroke come from the
 * {@link JoiCssStyles#WORKSPACE_PART_ICON} rule; a drawing only gives the SVG elements.
 */
public enum PartIcon {

    /** A chip with pins: the models. */
    MODELS("<rect x='7' y='7' width='10' height='10' rx='2'/>"
            + "<path d='M10 3v4M14 3v4M10 17v4M14 17v4M3 10h4M3 14h4M17 10h4M17 14h4'/>");

    private final String markup;

    PartIcon(String markup) {
        this.markup = markup;
    }

    /**
     * @return the whole {@code <svg>} element of the icon
     */
    public String svg() {
        return "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' aria-hidden='true'>" + markup + "</svg>";
    }

    /**
     * @return a new component with the icon, to put in front of the caption of a tab
     */
    public Span create() {
        Span span = new Span();
        span.addClassName(JoiCssStyles.WORKSPACE_PART_ICON);
        span.getElement().setProperty("innerHTML", svg());
        return span;
    }
}
