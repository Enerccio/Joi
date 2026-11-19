package com.github.enerccio.joi.ui.workspace;

import com.github.enerccio.joi.ui.JoiCssStyles;
import com.vaadin.flow.component.html.Span;

/**
 * The icons of the parts of the {@link JoiWorkspace}: line drawings on a 24 x 24 grid, a round stroke in the color of
 * the text (so they follow the theme and the selected tab) and no fill. Size and stroke come from the
 * {@link JoiCssStyles#WORKSPACE_PART_ICON} rule; a drawing only gives the SVG elements.
 */
public enum PartIcon {

    /**
     * A hologram of a woman: a long-haired bust standing on the plate of a projector, between the dashed edges of its
     * light: the assistants.
     */
    JOI("<path d='M12 4a3 3 0 0 0-3 3c0 3-.6 4.4-2 5.3M12 4a3 3 0 0 1 3 3c0 3 .6 4.4 2 5.3'/>"
            + "<path d='M10 9.5a2 2 0 0 0 4 0'/><path d='M7.5 17a4.5 4 0 0 1 9 0'/><path d='M5 21h14'/>"
            + "<path d='M3 4v2M21 4v2M3 10v2M21 10v2M3 16v1M21 16v1'/>"),

    /** A figure on a stand, arms out: the bodies (3D models). */
    BODIES("<circle cx='12' cy='4.5' r='2.5'/><path d='M12 7v8M6 9.5l6 1.5 6-1.5M9 20l3-5 3 5'/><path d='M7 21h10'/>"),

    /** A figure in mid-step with motion lines behind it: the animations. */
    ANIMATIONS("<circle cx='14' cy='4.5' r='2.5'/><path d='M13 7.5l-2 6 3 2.5v5M11 13.5l-3 7M12.5 9l3.5 2.5 2.5-1M12.5 9L9 10.5l-1.5 3'/>"
            + "<path d='M3 9h3M2 13h4M3 17h3'/>"),

    /** A chip with pins: the models. */
    MODELS("<rect x='7' y='7' width='10' height='10' rx='2'/>"
            + "<path d='M10 3v4M14 3v4M10 17v4M14 17v4M3 10h4M3 14h4M17 10h4M17 14h4'/>"),

    /** Sliders: the user's settings. */
    SETTINGS("<path d='M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12'/><circle cx='16' cy='6' r='2'/>"
            + "<circle cx='10' cy='12' r='2'/><circle cx='18' cy='18' r='2'/>"),

    /** A shield: the administration. */
    ADMIN("<path d='M12 3l7 3v5c0 4.5-3 8.5-7 10-4-1.5-7-5.5-7-10V6z'/><path d='M9 12l2 2 4-4'/>");

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
