package com.github.enerccio.joi.ui.dialogs;

import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.widgets.DialogFocus;
import com.github.enerccio.marginalia.ui.widgets.DialogShortcuts;
import com.github.enerccio.marginalia.ui.widgets.ResizableTextArea;
import com.github.enerccio.marginalia.ui.widgets.WordDiffView;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.data.value.ValueChangeMode;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

/**
 * A template next to the one it inherits (its parent in the chain), as Marginalia's merge dialog shows a text next to the
 * book's: the parent read only on the left, this one to edit on the right, the words that change between them under
 * them (the platform's {@link WordDiffView}, following the edit). Save writes the edited text into the field it came from,
 * which stores it as it stores what the user types there; Cancel leaves the field as it was.
 */
@Configurable
public class TemplateCompareDialog extends Dialog {

    @Autowired
    private Localization loc;

    private final String label;
    private final String parent;
    private final HasValue<?, String> field;
    private TextArea edit;
    private WordDiffView differences;

    /**
     * @param label  the label of the template, for the title
     * @param parent the inherited text
     * @param field  the field of the template (this link of the chain), written on Save
     */
    public TemplateCompareDialog(String label, String parent, HasValue<?, String> field) {
        this.label = label;
        this.parent = StringUtils.defaultString(parent);
        this.field = field;
    }

    public void create() {
        setHeaderTitle(loc.getValue(J_L.LABEL_COMPARE_TEMPLATE_TITLE).formatted(StringUtils.defaultString(label)));
        setWidth("min(80em, 95vw)");

        Span hint = new Span(loc.getValue(J_L.HELP_COMPARE_TEMPLATE));
        hint.addClassName(CssStyles.HINT_TEXT);

        TextArea inherited = new TextArea(loc.getValue(J_L.LABEL_INHERITED_TEXT));
        inherited.setValue(parent);
        inherited.setReadOnly(true);
        ResizableTextArea.install(loc, inherited, "320px");

        String original = StringUtils.defaultString(field.getValue());
        edit = new TextArea(loc.getValue(J_L.LABEL_OWN_TEXT));
        edit.setValue(original);
        edit.setValueChangeMode(ValueChangeMode.LAZY);
        ResizableTextArea.install(loc, edit, "320px");

        HorizontalLayout texts = new HorizontalLayout(inherited, edit);
        texts.setWidthFull();
        texts.addClassName(JoiCssStyles.TEMPLATE_COMPARE);

        Button useInherited = new Button(loc.getValue(J_L.LABEL_USE_INHERITED_TEXT), e -> edit.setValue(parent));
        Button undo = new Button(loc.getValue(J_L.LABEL_UNDO_TEMPLATE_EDITS), e -> edit.setValue(original));
        for (Button button : new Button[]{useInherited, undo}) {
            button.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        }

        differences = new WordDiffView(loc.getValue(J_L.MSG_TEMPLATE_SAME_AS_INHERITED));
        differences.show(parent, original);
        edit.addValueChangeListener(e -> differences.show(parent, e.getValue()));
        Details details = new Details(loc.getValue(J_L.LABEL_TEMPLATE_DIFFERENCES), differences);
        details.setOpened(true);
        details.setWidthFull();

        VerticalLayout content = new VerticalLayout(hint, texts, new HorizontalLayout(useInherited, undo), details);
        content.setPadding(false);
        add(content);

        Button cancel = new Button(loc.getValue(L.LABEL_CANCEL), e -> close());
        Button save = new Button(loc.getValue(L.LABEL_SAVE), e -> save());
        save.setThemeName("primary");
        getFooter().add(cancel, save);
        DialogFocus.onOpen(this, edit);
        DialogShortcuts.submitWith(save, edit);
    }

    private void save() {
        field.setValue(edit.getValue());
        close();
    }

    /** The text being edited, for tests. */
    public TextArea getEdit() {
        return edit;
    }

    /** Clicks Save, for tests. */
    public void clickSave() {
        save();
    }
}
