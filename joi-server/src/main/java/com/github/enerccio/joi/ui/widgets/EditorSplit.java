package com.github.enerccio.joi.ui.widgets;

import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.joi.ui.JoiJavascriptLibrary;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.splitlayout.SplitLayout;

/**
 * The editing side and the 3D preview of the model and body dialogs, side by side with a divider the user drags; where
 * it was left is kept in the browser.
 */
public final class EditorSplit {

    /** The share of the editing side when the user hasn't moved the divider, in percent. */
    public static final double DEFAULT_POSITION = 40;

    private EditorSplit() {
    }

    /**
     * @param storageKey where the position is kept, one per dialog
     */
    public static SplitLayout create(Component editor, Component preview, String storageKey) {
        SplitLayout split = new SplitLayout(editor, preview);
        split.setSizeFull();
        split.setSplitterPosition(DEFAULT_POSITION);
        split.addClassName(JoiCssStyles.EDITOR_SPLIT);
        JoiJavascriptLibrary.rememberSplit(split, storageKey);
        return split;
    }
}
