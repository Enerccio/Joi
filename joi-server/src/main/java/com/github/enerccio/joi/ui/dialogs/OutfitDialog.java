package com.github.enerccio.joi.ui.dialogs;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.bodies.BodyState;
import com.github.enerccio.joi.domain.bodies.ModelCapabilities;
import com.github.enerccio.joi.domain.bodies.Outfit;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.service.AppearanceService;
import com.github.enerccio.joi.domain.service.BodyService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.joi.ui.bodies.AppearancePanel;
import com.github.enerccio.joi.ui.bodies.ModelViewer;
import com.github.enerccio.joi.ui.widgets.EditorSplit;
import com.github.enerccio.joi.ui.widgets.PromptAreas;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.widgets.Notification;
import com.github.enerccio.marginalia.ui.widgets.ResizableTextArea;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.server.streams.UploadHandler;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An outfit of a body, full screen: its name, how she looks in it, and what the body's model shows in it (a box for each
 * toggle, shown on the 3D preview). The <i>photoshoot</i> takes renders of the model in the outfit and has a
 * multimodal model describe what she wears (the user reviews it in the field). A new outfit starts from what the body
 * shows now.
 */
@Configurable
public class OutfitDialog extends Dialog {

    @Autowired
    private Localization loc;

    @Autowired
    private BodyService bodyService;

    @Autowired
    private ModelService modelService;

    @Autowired
    private AppearanceService appearanceService;

    private final Body body;
    /** The profile whose state a new outfit starts from; null for the default one. */
    private final String profile;
    private final Outfit outfit;
    private final Model model;
    private final Runnable onSaved;
    private TextField nameField;
    private TextArea description;
    private Button photoshootButton;
    private Span photoshootStatus;
    private ModelViewer viewer;
    /** The box of each toggle of the model, by the toggle's id. */
    private final Map<String, Checkbox> toggles = new LinkedHashMap<>();
    /** The renders the viewer sent, until it says they are all there. */
    // filled by the upload requests of the snapshots
    private final Map<String, byte[]> pendingShots = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean previewShown;

    /**
     * @param outfit  the outfit to edit, null for a new one
     * @param onSaved runs once it is stored
     */
    public OutfitDialog(Body body, Model model, Outfit outfit, Runnable onSaved) {
        this(body, null, model, outfit, onSaved);
    }

    /** @param profile the profile whose state of the body's parts is where a new outfit starts, null for the default one */
    public OutfitDialog(Body body, String profile, Model model, Outfit outfit, Runnable onSaved) {
        this.profile = profile;
        this.body = body;
        this.model = model;
        this.outfit = outfit;
        this.onSaved = onSaved;
    }

    /** What the body shows now in the profile. */
    private Map<String, String> states() {
        try {
            return bodyService.partStates(body, profile);
        } catch (Exception e) {
            return body.partStateMap();
        }
    }

    public void create() throws Exception {
        setSizeFull();
        setCloseOnOutsideClick(false);
        setHeaderTitle(outfit == null ? loc.getValue(J_L.LABEL_NEW_OUTFIT) : outfit.name());
        ModelCapabilities capabilities = ModelCapabilities.fromJson(model.getCapabilities());

        nameField = new TextField(loc.getValue(L.LABEL_NAME));
        nameField.setWidthFull();
        nameField.setValue(outfit == null ? "" : StringUtils.defaultString(outfit.name()));

        description = new TextArea(loc.getValue(J_L.LABEL_OUTFIT_DESCRIPTION));
        description.setWidthFull();
        description.setValue(outfit == null ? "" : StringUtils.defaultString(outfit.description()));
        ResizableTextArea.install(loc, description, PromptAreas.TEMPLATE_HEIGHT);
        description.setHelperText(loc.getValue(J_L.MSG_OUTFIT_DESCRIPTION_HINT));

        photoshootButton = new Button(loc.getValue(J_L.LABEL_OUTFIT_PHOTOSHOOT), Solid.CAMERA.create(), e -> photoshoot());
        photoshootStatus = new Span();
        photoshootStatus.addClassName(CssStyles.HINT_TEXT);
        if (!hasAi()) {
            photoshootButton.setEnabled(false);
            photoshootStatus.setText(loc.getValue(J_L.MSG_OUTFIT_PHOTOSHOOT_NO_AI));
        }

        H4 togglesTitle = new H4(loc.getValue(J_L.LABEL_OUTFIT_TOGGLES));
        Span togglesHint = new Span(loc.getValue(J_L.MSG_OUTFIT_TOGGLES_HINT));
        togglesHint.addClassName(CssStyles.HINT_TEXT);
        Button fromBody = new Button(loc.getValue(J_L.LABEL_OUTFIT_FROM_BODY), e -> setToggles(BodyState.shownNow(model, states())));
        Div boxes = new Div();
        boxes.addClassName(JoiCssStyles.OUTFIT_TOGGLES);
        Map<String, Boolean> start = outfit != null ? outfit.toggles() : BodyState.shownNow(model, states());
        if (capabilities != null) {
            for (ModelCapabilities.Toggle toggle : capabilities.toggles()) {
                String name = model.toggleName(toggle);
                Checkbox box = new Checkbox(toggle.key() == null ? name : name + " (" + toggle.key() + ")");
                box.setValue(start.getOrDefault(toggle.id(), model.shownByDefault(toggle)));
                box.addValueChangeListener(e -> showState());
                toggles.put(toggle.id(), box);
                boxes.add(box);
            }
        }

        VerticalLayout sidebar = new VerticalLayout(nameField, description, photoshootButton, photoshootStatus, togglesTitle, togglesHint,
                fromBody, boxes);
        sidebar.setPadding(false);
        sidebar.addClassName(JoiCssStyles.MODEL_SIDEBAR);

        viewer = new ModelViewer();
        viewer.setSizeFull();
        viewer.addInfoListener(e -> {
            previewShown = true;
            showState();
        });
        viewer.addSnapshotsListener(e -> snapshotsTaken());
        viewer.addSnapshotsErrorListener(e -> {
            photoshootButton.setEnabled(hasAi());
            photoshootStatus.setText(String.format(loc.getValue(J_L.MSG_OUTFIT_PHOTOSHOOT_FAILED), e.getMessage()));
        });
        VerticalLayout previewColumn = new VerticalLayout(viewer);
        previewColumn.setPadding(false);
        previewColumn.setSpacing(false);
        previewColumn.addClassName(JoiCssStyles.MODEL_VIEWER);
        previewColumn.setFlexGrow(1, viewer);
        add(EditorSplit.create(sidebar, previewColumn, "joi.outfit-dialog.split"));

        Button save = new Button(loc.getValue(L.LABEL_SAVE), e -> save());
        save.setThemeName("primary");
        Button cancel = new Button(loc.getValue(L.LABEL_CANCEL), e -> close());
        getFooter().add(cancel, save);

        File file = modelService.bodyFile(model);
        if (file != null) {
            viewer.show(file, modelService.bodyFormat(model), null);
        }
        nameField.focus();
    }

    /** Whether the user has a model that takes images. */
    private boolean hasAi() {
        try {
            return appearanceService.model() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** What the boxes say: the toggle's id → whether it is shown. */
    private Map<String, Boolean> state() {
        Map<String, Boolean> shown = new LinkedHashMap<>();
        toggles.forEach((id, box) -> shown.put(id, box.getValue()));
        return shown;
    }

    private void setToggles(Map<String, Boolean> shown) {
        toggles.forEach((id, box) -> box.setValue(shown.getOrDefault(id, box.getValue())));
        showState();
    }

    /** The model on the preview in the state of the boxes. */
    private void showState() {
        if (previewShown) {
            viewer.setToggles(AppearancePanel.toggles(model, state()));
        }
    }

    private void photoshoot() {
        photoshootButton.setEnabled(false);
        photoshootStatus.setText(loc.getValue(J_L.MSG_OUTFIT_PHOTOSHOOT_RUNNING));
        pendingShots.clear();
        viewer.takeSnapshots(UploadHandler.inMemory((metadata, bytes) ->
                        pendingShots.put(StringUtils.removeEndIgnoreCase(metadata.fileName(), ".jpg"), bytes)),
                AppearancePanel.defaultToggles(model), AppearancePanel.toggles(model, state()), List.of());
    }

    private void snapshotsTaken() {
        Map<String, byte[]> shots = new LinkedHashMap<>();
        for (String shot : AppearancePanel.SHOT_ORDER) {
            if (pendingShots.containsKey(shot)) {
                shots.put(shot, pendingShots.get(shot));
            }
        }
        pendingShots.clear();
        try {
            Outfit draft = (outfit == null ? Outfit.create(StringUtils.defaultIfBlank(StringUtils.strip(nameField.getValue()), "new outfit"), null, state())
                    : outfit.named(StringUtils.defaultIfBlank(StringUtils.strip(nameField.getValue()), outfit.name()))).with(state())
                    .described(description.getValue());
            UI ui = UI.getCurrent();
            appearanceService.describeOutfit(body, draft, shots).whenComplete((text, error) -> ui.access(() -> {
                photoshootButton.setEnabled(true);
                if (error != null) {
                    photoshootStatus.setText(String.format(loc.getValue(J_L.MSG_OUTFIT_PHOTOSHOOT_FAILED),
                            StringUtils.defaultIfBlank(ExceptionUtils.getRootCauseMessage(error), error.toString())));
                } else {
                    description.setValue(text);
                    photoshootStatus.setText("");
                }
            }));
        } catch (Exception e) {
            photoshootButton.setEnabled(true);
            photoshootStatus.setText(String.format(loc.getValue(J_L.MSG_OUTFIT_PHOTOSHOOT_FAILED), StringUtils.defaultIfBlank(e.getMessage(), e.toString())));
        }
    }

    private void save() {
        String name = StringUtils.strip(nameField.getValue());
        if (StringUtils.isBlank(name)) {
            Notification.warning(loc.getValue(J_L.MSG_OUTFIT_NO_NAME));
            return;
        }
        try {
            if (outfit == null) {
                bodyService.addOutfit(body, name, description.getValue(), state());
            } else {
                bodyService.saveOutfit(body, outfit.named(name).described(description.getValue()).with(state()));
            }
            close();
            onSaved.run();
        } catch (IllegalArgumentException e) {
            Notification.warning(e.getMessage());
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The name field, for tests. */
    public TextField getNameField() {
        return nameField;
    }

    /** The description field, for tests. */
    public TextArea getDescription() {
        return description;
    }

    /** The box of a toggle, by its id, for tests. */
    public Checkbox getToggle(String toggleId) {
        return toggles.get(toggleId);
    }

    /** Save, for tests. */
    public void saveForTest() {
        save();
    }

    /** For tests: the viewer sent the renders. */
    public void snapshotsTakenForTest(Map<String, byte[]> shots) {
        pendingShots.putAll(shots);
        snapshotsTaken();
    }
}
