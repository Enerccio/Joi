package com.github.enerccio.joi.ui.dialogs;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.service.AnimationPackService;
import com.github.enerccio.joi.domain.service.AnimationService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.joi.ui.bodies.ClipPreview;
import com.github.enerccio.joi.ui.widgets.EditorSplit;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.util.ArrayList;
import java.util.List;

/**
 * One animation pack ({@link AnimationPack}), full screen: on the left its name and description, and its clips, chosen
 * from the user's library (the Animations tab, where clips are uploaded and converted); the clip chosen in the table
 * plays on the right, in the {@link ClipPreview} of the body the pack is previewed on (remembered). A clip is opened
 * from the table to edit it. Changes are stored at once; a new pack is stored once it gets a name or a clip.
 */
@Configurable
public class AnimationPackDialog extends Dialog {

    @Autowired
    private Localization loc;

    @Autowired
    private AnimationPackService packService;

    @Autowired
    private AnimationService animationService;

    private AnimationPack pack;
    private Runnable onClose = () -> {
    };
    private TextField nameField;
    private TextArea descriptionField;
    private MultiSelectComboBox<Animation> choice;
    private Button addButton;
    private Span emptyLibrary;
    private Grid<Animation> clips;
    private ClipPreview preview;

    /**
     * @param pack the stored pack, or a new one
     */
    public AnimationPackDialog(AnimationPack pack) {
        this.pack = pack;
    }

    /** What runs when the dialog closes (the table of packs refreshes). */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose == null ? () -> {
        } : onClose;
    }

    public void create() throws Exception {
        setSizeFull();
        setCloseOnOutsideClick(false);
        updateTitle();

        nameField = new TextField(loc.getValue(L.LABEL_NAME));
        nameField.setWidthFull();
        nameField.setValue(StringUtils.defaultString(pack.getName()));
        nameField.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                storePack(false);
            }
        });
        descriptionField = new TextArea(loc.getValue(L.LABEL_DESCRIPTION));
        descriptionField.setWidthFull();
        descriptionField.setValue(StringUtils.defaultString(pack.getDescription()));
        descriptionField.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                storePack(false);
            }
        });

        H4 clipsTitle = new H4(loc.getValue(J_L.LABEL_CLIPS));
        Span hint = new Span(loc.getValue(J_L.MSG_ANIMATION_PACK_HINT));
        hint.addClassName(CssStyles.HINT_TEXT);
        choice = new MultiSelectComboBox<>(loc.getValue(J_L.LABEL_CHOOSE_CLIPS));
        choice.setItemLabelGenerator(clip -> StringUtils.defaultString(clip.getName()));
        choice.setWidthFull();
        addButton = new Button(loc.getValue(J_L.LABEL_ADD_CLIPS), Solid.PLUS.create(), e -> addChosen());
        HorizontalLayout adding = new HorizontalLayout(choice, addButton);
        adding.setWidthFull();
        adding.setAlignItems(FlexComponent.Alignment.BASELINE);
        adding.setFlexGrow(1, choice);
        emptyLibrary = new Span(loc.getValue(J_L.MSG_NO_CLIPS_YET));
        emptyLibrary.addClassName(CssStyles.HINT_TEXT);

        clips = new Grid<>(Animation.class, false);
        clips.setWidthFull();
        clips.setAllRowsVisible(true);
        clips.addColumn(Animation::getName).setHeader(loc.getValue(L.LABEL_NAME)).setFlexGrow(1);
        clips.addColumn(clip -> AnimationDialog.length(clip.getDuration()))
                .setHeader(loc.getValue(J_L.LABEL_ANIMATION_LENGTH)).setFlexGrow(0).setWidth("90px");
        clips.addColumn(clip -> String.join(", ", clip.getTags()))
                .setHeader(loc.getValue(J_L.LABEL_ANIMATION_TAGS)).setFlexGrow(1);
        clips.addComponentColumn(this::clipActions).setHeader("").setFlexGrow(0).setWidth("120px");
        clips.addSelectionListener(e -> preview.play(e.getFirstSelectedItem().orElse(null)));
        clips.addItemDoubleClickListener(e -> openClip(e.getItem()));

        VerticalLayout sidebar = new VerticalLayout(nameField, descriptionField, clipsTitle, hint, adding, emptyLibrary, clips);
        sidebar.setPadding(false);
        sidebar.addClassName(JoiCssStyles.MODEL_SIDEBAR);

        AnimationPack stored = pack.getId() == null ? null : pack;
        preview = new ClipPreview(stored == null || packService.previewBody(stored) == null ? null : stored.getPreviewBodyId());
        preview.setOnBodyChosen(id -> storePack(false));
        add(EditorSplit.create(sidebar, preview, "joi.animation-pack-dialog.split"));

        Button exitButton = new Button(loc.getValue(L.LABEL_EXIT), e -> close());
        exitButton.setThemeName("primary");
        getFooter().add(exitButton);
        addOpenedChangeListener(e -> {
            if (!e.isOpened()) {
                onClose.run();
            }
        });
        refreshClips();
        nameField.focus();
    }

    private Component clipActions(Animation clip) {
        Button open = new Button(Solid.FOLDER_OPEN.create(), e -> openClip(clip));
        open.setTooltipText(loc.getValue(L.LABEL_OPEN));
        Button remove = new Button(Solid.MINUS.create(), e -> {
            try {
                pack = packService.removeClip(pack, clip);
                if (clips.getSelectedItems().contains(clip)) {
                    clips.deselectAll();
                }
                refreshClips();
            } catch (Exception ex) {
                UIUtils.internalServerError(loc, ex);
            }
        });
        remove.setThemeName("error tertiary");
        remove.setTooltipText(loc.getValue(J_L.LABEL_REMOVE_FROM_PACK));
        HorizontalLayout actions = new HorizontalLayout(open, remove);
        actions.setSpacing(true);
        return actions;
    }

    /** Adds the clips chosen to the pack (stored first when new). */
    private void addChosen() {
        try {
            List<Animation> chosen = new ArrayList<>(choice.getSelectedItems());
            if (chosen.isEmpty()) {
                return;
            }
            if (pack.getId() == null) {
                storePack(true);
            }
            pack = packService.addClips(pack, chosen);
            choice.clear();
            refreshClips();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void openClip(Animation clip) {
        try {
            AnimationDialog dialog = new AnimationDialog(animationService.find(clip.getId()));
            dialog.setOnClose(() -> {
                try {
                    refreshClips();
                } catch (Exception e) {
                    UIUtils.internalServerError(loc, e);
                }
            });
            dialog.create();
            dialog.open();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /**
     * Stores the pack's name, description and preview body.
     *
     * @param always whether to store a new pack without a name too (clips are added to it)
     */
    private void storePack(boolean always) {
        try {
            String name = StringUtils.defaultIfBlank(StringUtils.strip(nameField.getValue()), null);
            if (pack.getId() == null && name == null && !always) {
                return;
            }
            AnimationPack stored = pack.getId() == null ? pack : packService.find(pack.getId());
            stored.setName(name);
            stored.setDescription(StringUtils.defaultIfBlank(descriptionField.getValue(), null));
            stored.setPreviewBodyId(preview == null ? null : preview.getBodySelect().getValue());
            pack = packService.save(stored);
            updateTitle();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The pack's clips into the table, the library's others into the choice. */
    private void refreshClips() throws Exception {
        List<Animation> inPack = packService.clips(pack);
        clips.setItems(inPack);
        List<String> uuids = inPack.stream().map(Animation::getUuid).toList();
        List<Animation> others = animationService.findAllByName().stream().filter(clip -> !uuids.contains(clip.getUuid())).toList();
        choice.setItems(others);
        boolean library = !others.isEmpty() || !inPack.isEmpty();
        emptyLibrary.setVisible(!library);
        choice.setEnabled(!others.isEmpty());
        addButton.setEnabled(!others.isEmpty());
    }

    private void updateTitle() {
        setHeaderTitle(StringUtils.isBlank(pack.getName()) ? loc.getValue(J_L.LABEL_NEW_ANIMATION_PACK) : pack.getName());
    }

    /** The pack as stored last (no id while a new one is not stored), for tests. */
    public AnimationPack getPack() {
        return pack;
    }

    /** The table of the pack's clips, for tests. */
    public Grid<Animation> getClips() {
        return clips;
    }

    /** The choice of clips to add, for tests. */
    public MultiSelectComboBox<Animation> getChoice() {
        return choice;
    }

    /** Adds the clips chosen, for tests. */
    public void addChosenForTest() {
        addChosen();
    }

    /** The name field, for tests. */
    public TextField getNameField() {
        return nameField;
    }

    /** The preview, for tests. */
    public ClipPreview getPreview() {
        return preview;
    }
}
