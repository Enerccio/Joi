package com.github.enerccio.joi.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.splitlayout.SplitLayout;

/**
 * The JavaScript Joi runs in the browser from the server side (the platform's own is its {@code JavascriptLibrary}).
 * Every method wraps one script.
 */
public final class JoiJavascriptLibrary {

    private JoiJavascriptLibrary() {
    }

    /**
     * Keeps where the user left the divider of a split in the browser ({@code localStorage}, under the key) and puts
     * it back there when the split is shown again. A position kept that is out of range is ignored.
     */
    public static void rememberSplit(SplitLayout split, String storageKey) {
        split.getElement().executeJs("try { return localStorage.getItem($0) || ''; } catch (e) { return ''; }", storageKey)
                .then(String.class, kept -> {
                    try {
                        double position = Double.parseDouble(kept);
                        if (position >= 10 && position <= 90) {
                            split.setSplitterPosition(position);
                        }
                    } catch (NumberFormatException e) {
                        // nothing kept yet
                    }
                });
        split.addSplitterDragEndListener(e -> {
            Double position = split.getSplitterPosition();
            if (position != null) {
                split.getElement().executeJs("try { localStorage.setItem($0, $1); } catch (e) {}", storageKey,
                        String.valueOf(Math.round(position * 10) / 10.0));
            }
        });
    }

    /** A shift-click on the component doesn't select the page's text (it checks a range of messages instead). */
    public static void noShiftSelect(Component component) {
        component.getElement().executeJs("this.addEventListener('mousedown', e => { if (e.shiftKey) { e.preventDefault(); } });");
    }
}
