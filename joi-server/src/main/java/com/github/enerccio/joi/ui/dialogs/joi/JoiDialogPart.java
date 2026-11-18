package com.github.enerccio.joi.ui.dialogs.joi;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.loc.J_L;
import com.vaadin.flow.component.Component;

/**
 * A tab of the {@link com.github.enerccio.joi.ui.dialogs.JoiDialog} (after Marginalia's {@code ManuscriptDialogPart}).
 * A part stores its changes as they are made, through the dialog.
 */
public interface JoiDialogPart {

    /** The caption of the tab. */
    J_L caption();

    /** Builds the content of the tab, once. */
    Component create() throws Exception;

    /** Shows the assistant. */
    void load(Joi joi) throws Exception;
}
