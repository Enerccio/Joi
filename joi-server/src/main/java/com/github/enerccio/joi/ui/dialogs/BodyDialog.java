package com.github.enerccio.joi.ui.dialogs;

import com.github.enerccio.joi.domain.bodies.Appearance;
import com.github.enerccio.joi.domain.bodies.BodyState;
import com.github.enerccio.joi.domain.bodies.DescribedPart;
import com.github.enerccio.joi.domain.bodies.ModelCapabilities;
import com.github.enerccio.joi.domain.bodies.Outfit;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.service.BodyService;
import com.github.enerccio.joi.domain.service.AnimationPackService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.JoiaService;
import com.github.enerccio.joi.protocol.joia.JoiaFile;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.joi.ui.widgets.EditorSplit;
import com.github.enerccio.joi.ui.bodies.AppearancePanel;
import com.github.enerccio.joi.ui.bodies.ModelViewer;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.dialogs.ConfirmDialog;
import com.github.enerccio.marginalia.ui.widgets.Notification;
import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.github.enerccio.marginalia.ui.widgets.ResizableTextArea;
import com.github.enerccio.joi.ui.widgets.PromptAreas;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.server.streams.DownloadHandler;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One body of an assistant ({@link Body}), full screen: on the left its name, its models (the full one and the one
 * for the Looking Glass, from the user's models with a VRM) and how she looks in it (the model's description, put
 * together for the state of the body: it is described and edited in the model's dialog), and the state of each part
 * described apart, which the user switches (she is told), and the animations she plays in it (all of the library by
 * default), written into the body's {@code .joia} on Exit when they changed; on the right the
 * {@link ModelViewer 3D preview} of either model. Changes are stored at once; a new body is stored once it gets a name
 * or a model (her first body is the one she is in).
 */
@Configurable
public class BodyDialog extends Dialog {

    @Autowired
    private Localization loc;

    @Autowired
    private BodyService bodyService;

    @Autowired
    private ModelService modelService;

    @Autowired
    private JoiService joiService;

    @Autowired
    private AnimationPackService packService;

    @Autowired
    private JoiaService joiaService;

    private final Joi joi;
    private Body body;
    private Runnable onClose = () -> {
    };
    /** The user's models a body can be made of, by id. */
    private final Map<Long, Model> models = new LinkedHashMap<>();
    private TextField nameField;
    private Select<Long> modelSelect;
    private Select<Long> lookingGlassSelect;
    private TextArea description;
    private TextArea bodyDescription;
    private Div outfitsBox;
    private Button addOutfitButton;
    private Span outfitsHint;
    private Span capabilitiesInfo;
    private RadioButtonGroup<Boolean> previewChoice;
    private ModelViewer viewer;
    private Span previewInfo;
    /** The choice of the state of each part described apart, by the part's id. */
    private final Map<String, Select<String>> partSelects = new LinkedHashMap<>();
    private Div parts;
    private Span partsHint;
    /** The user's animations, by uuid, in the order of their names. */
    private final Map<String, AnimationPack> packs = new LinkedHashMap<>();
    private Checkbox allAnimations;
    private MultiSelectComboBox<String> animationSelect;
    private Div joiaInfo;

    /**
     * @param body the stored body, null for a new one
     */
    public BodyDialog(Joi joi, Body body) {
        this.joi = joi;
        this.body = body;
    }

    /** What runs when the dialog closes (the table of her bodies refreshes). */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose == null ? () -> {
        } : onClose;
    }

    public void create() throws Exception {
        setSizeFull();
        setCloseOnOutsideClick(false);
        for (Model model : modelService.findAllByName()) {
            if (model.renderable()) {
                models.put(model.getId(), model);
            }
        }
        // a model that stopped being renderable stays choosable for the body that has it
        for (Model model : new Model[]{reference(body == null ? null : body.getModel()), reference(body == null ? null : body.getLookingGlassModel())}) {
            if (model != null) {
                models.putIfAbsent(model.getId(), model);
            }
        }
        updateTitle();

        nameField = new TextField(loc.getValue(L.LABEL_NAME));
        nameField.setWidthFull();
        nameField.setValue(body == null ? "" : StringUtils.defaultString(body.getName()));
        nameField.addValueChangeListener(e -> store());

        modelSelect = modelSelect(J_L.LABEL_BODY_MODEL);
        modelSelect.setEmptySelectionAllowed(true);
        modelSelect.setValue(body == null || body.getModel() == null ? null : body.getModel().getId());
        modelSelect.addValueChangeListener(e -> {
            store();
            showModel();
        });

        lookingGlassSelect = modelSelect(J_L.LABEL_LOOKING_GLASS_MODEL);
        lookingGlassSelect.setEmptySelectionAllowed(true);
        lookingGlassSelect.setEmptySelectionCaption(loc.getValue(J_L.LABEL_SAME_AS_FULL_MODEL));
        lookingGlassSelect.setValue(body == null || body.getLookingGlassModel() == null ? null : body.getLookingGlassModel().getId());
        lookingGlassSelect.addValueChangeListener(e -> {
            store();
            showModel();
        });

        Span modelHint = new Span(loc.getValue(J_L.MSG_BODY_MODEL_HINT));
        modelHint.addClassName(CssStyles.HINT_TEXT);
        capabilitiesInfo = new Span();
        capabilitiesInfo.addClassName(CssStyles.HINT_TEXT);

        description = new TextArea(loc.getValue(J_L.LABEL_APPEARANCE));
        description.setWidthFull();
        description.setReadOnly(true);
        ResizableTextArea.install(loc, description, PromptAreas.TEMPLATE_HEIGHT);
        description.setHelperText(loc.getValue(J_L.MSG_BODY_APPEARANCE_HINT));

        bodyDescription = new TextArea(loc.getValue(J_L.LABEL_BODY_DESCRIPTION));
        bodyDescription.setWidthFull();
        bodyDescription.setValue(body == null ? "" : StringUtils.defaultString(body.getDescription()));
        ResizableTextArea.install(loc, bodyDescription, PromptAreas.TEMPLATE_HEIGHT);
        bodyDescription.setHelperText(loc.getValue(J_L.MSG_BODY_DESCRIPTION_HINT));
        bodyDescription.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                store();
            }
        });

        H4 outfitsTitle = new H4(loc.getValue(J_L.LABEL_BODY_OUTFITS));
        outfitsHint = new Span(loc.getValue(J_L.MSG_BODY_OUTFITS_HINT));
        outfitsHint.addClassName(CssStyles.HINT_TEXT);
        outfitsBox = new Div();
        outfitsBox.addClassName(JoiCssStyles.BODY_OUTFITS);
        addOutfitButton = new Button(loc.getValue(J_L.LABEL_ADD_OUTFIT), Solid.PLUS.create(), e -> editOutfit(null));

        H4 partsTitle = new H4(loc.getValue(J_L.LABEL_BODY_PARTS));
        partsHint = new Span();
        partsHint.addClassName(CssStyles.HINT_TEXT);
        parts = new Div();
        parts.addClassName(JoiCssStyles.BODY_PARTS);

        H4 animationsTitle = new H4(loc.getValue(J_L.LABEL_BODY_ANIMATIONS));
        Span animationsHint = new Span(loc.getValue(J_L.MSG_BODY_ANIMATIONS_HINT));
        animationsHint.addClassName(CssStyles.HINT_TEXT);
        for (AnimationPack pack : packService.findAllByName()) {
            packs.put(pack.getUuid(), pack);
        }
        List<String> chosen = body == null ? null : body.animationUuids();
        allAnimations = new Checkbox(loc.getValue(J_L.LABEL_BODY_ALL_ANIMATIONS), chosen == null);
        animationSelect = new MultiSelectComboBox<>(loc.getValue(J_L.LABEL_BODY_CHOSEN_ANIMATIONS));
        animationSelect.setWidthFull();
        animationSelect.setItems(packs.keySet());
        animationSelect.setItemLabelGenerator(uuid -> StringUtils.defaultIfBlank(packs.get(uuid).getName(), uuid));
        animationSelect.setValue(chosen == null ? packs.keySet() : chosen.stream().filter(packs::containsKey).toList());
        animationSelect.setEnabled(chosen != null);
        allAnimations.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                animationSelect.setEnabled(!e.getValue());
                if (e.getValue()) {
                    animationSelect.setValue(packs.keySet());
                }
                storeAnimations();
            }
        });
        animationSelect.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                storeAnimations();
            }
        });
        joiaInfo = new Div();
        joiaInfo.addClassName(CssStyles.HINT_TEXT);

        VerticalLayout sidebar = new VerticalLayout(nameField, modelSelect, lookingGlassSelect, modelHint, capabilitiesInfo, bodyDescription, description,
                outfitsTitle, outfitsHint, outfitsBox, addOutfitButton, partsTitle, partsHint, parts, animationsTitle, animationsHint, allAnimations, animationSelect, joiaInfo);
        sidebar.setPadding(false);
        sidebar.addClassName(JoiCssStyles.MODEL_SIDEBAR);

        viewer = new ModelViewer();
        viewer.setSizeFull();
        previewInfo = new Span();
        previewInfo.addClassName(CssStyles.HINT_TEXT);
        viewer.addInfoListener(e -> {
            previewInfo.setText(String.format("%,d triangles · %d meshes · %d materials", e.getTriangles(), e.getMeshes(), e.getMaterials()));
            showState();
        });
        viewer.addErrorListener(e -> previewInfo.setText(e.getMessage()));
        previewChoice = new RadioButtonGroup<>();
        previewChoice.setItems(false, true);
        previewChoice.setItemLabelGenerator(lookingGlass -> loc.getValue(lookingGlass ? J_L.LABEL_PREVIEW_LOOKING_GLASS : J_L.LABEL_PREVIEW_FULL));
        previewChoice.setValue(false);
        previewChoice.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                showPreview();
            }
        });
        HorizontalLayout previewHeader = new HorizontalLayout(previewChoice, previewInfo);
        previewHeader.setAlignItems(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.BASELINE);
        VerticalLayout previewColumn = new VerticalLayout(previewHeader, viewer);
        previewColumn.setPadding(false);
        previewColumn.setSpacing(false);
        previewColumn.addClassName(JoiCssStyles.MODEL_VIEWER);
        previewColumn.setFlexGrow(1, viewer);

        com.vaadin.flow.component.splitlayout.SplitLayout content = EditorSplit.create(sidebar, previewColumn, "joi.body-dialog.split");
        add(content);

        Button exitButton = new Button(loc.getValue(L.LABEL_EXIT), e -> exit());
        exitButton.setThemeName("primary");
        getFooter().add(exitButton);

        addOpenedChangeListener(e -> {
            if (!e.isOpened()) {
                announce();
                onClose.run();
            }
        });
        showModel();
        showJoia();
        nameField.focus();
    }

    /** Stores the animations chosen: null for all of the library. */
    private void storeAnimations() {
        try {
            if (body == null) {
                // stored with the choice once it has a name or a model
                store();
                return;
            }
            body = bodyService.find(body.getId());
            body.setAnimationUuids(chosenAnimations());
            body = bodyService.save(body);
            bodyService.changed(body);
            showJoia();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The uuids of the animations chosen, in the order of their names; null for all of the library. */
    private List<String> chosenAnimations() {
        if (allAnimations.getValue()) {
            return null;
        }
        List<String> uuids = new ArrayList<>(packs.keySet());
        uuids.retainAll(animationSelect.getValue());
        return uuids;
    }

    /** The body's .joia: its size and time with a download, and whether it is current. */
    private void showJoia() {
        joiaInfo.removeAll();
        try {
            if (body == null) {
                return;
            }
            File file = bodyService.animationsFile(body);
            Div line = new Div(new Span(loc.getValue(J_L.LABEL_JOIA_FILE) + ": "));
            if (file != null) {
                String when = body.getAnimationsExportedAt() == null ? ""
                        : DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(body.getAnimationsExportedAt());
                line.add(new Span(String.format(loc.getValue(J_L.MSG_JOIM_INFO), FileUtils.byteCountToDisplaySize(file.length()), when) + " "));
                Anchor download = new Anchor(DownloadHandler.forFile(file, JoiaFile.fileName(body.getName())), loc.getValue(J_L.LABEL_JOIA_DOWNLOAD));
                download.setRouterIgnore(true);
                line.add(download);
            }
            joiaInfo.add(line);
            boolean stale = joiaService.needsBodyExport(body);
            if (file == null || stale) {
                joiaInfo.add(new Div(loc.getValue(!stale ? J_L.MSG_JOIA_EMPTY : file == null ? J_L.MSG_JOIA_NONE : J_L.MSG_JOIA_STALE)));
            }
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** Closes the dialog; the body's .joia is written first when its animations changed. */
    private void exit() {
        try {
            if (body != null && joiaService.needsBodyExport(body)) {
                body = joiaService.exportBody(body);
            }
        } catch (Exception e) {
            Notification.error(String.format(loc.getValue(J_L.MSG_JOIA_WRITE_ERROR), e.getMessage()));
        }
        close();
    }

    private Select<Long> modelSelect(J_L label) {
        Select<Long> select = new Select<>();
        select.setLabel(loc.getValue(label));
        select.setWidthFull();
        select.setItems(models.keySet());
        select.setItemLabelGenerator(id -> id == null ? "" : StringUtils.defaultString(models.get(id).getName()));
        return select;
    }

    /** A model of the body (a lazy reference), read by its id; null for none or a deleted one. */
    private Model reference(Model reference) throws Exception {
        if (reference == null) {
            return null;
        }
        Model model = modelService.find(reference.getId());
        return model == null || model.isDeleted() ? null : model;
    }

    /** Stores the fields; a new body once it has a name or a model. */
    private void store() {
        try {
            String name = StringUtils.strip(nameField.getValue());
            // another body's name is not taken: the field says so and the body keeps the name it has (none for a new one)
            boolean taken = bodyService.nameTaken(joi, name, body);
            nameField.setInvalid(taken);
            nameField.setErrorMessage(taken ? loc.getValue(J_L.MSG_BODY_NAME_TAKEN) : null);
            Long modelId = modelSelect.getValue();
            if (body == null) {
                if ((taken || StringUtils.isBlank(name)) && modelId == null) {
                    return;
                }
                body = bodyService.create(joi, taken ? null : name);
                body.setAnimationUuids(allAnimations == null ? null : chosenAnimations());
            } else {
                body = bodyService.find(body.getId());
            }
            if (!taken) {
                body.setName(StringUtils.defaultIfBlank(name, null));
            }
            body.setDescription(StringUtils.defaultIfBlank(StringUtils.strip(bodyDescription.getValue()), null));
            Long before = body.getModel() == null ? null : body.getModel().getId();
            if (!java.util.Objects.equals(before, modelId)) {
                // the parts are the model's: another model starts as it comes
                body.setPartStateMap(Map.of());
            }
            body.setModel(modelId == null ? null : models.get(modelId));
            Long lookingGlassId = lookingGlassSelect.getValue();
            body.setLookingGlassModel(lookingGlassId == null ? null : models.get(lookingGlassId));
            body = bodyService.save(body);
            bodyService.changed(body);
            updateTitle();
            if (joiaInfo != null) {
                showJoia();
            }
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** What the chosen model can do, and its preview. */
    private void showModel() {
        Model model = modelSelect.getValue() == null ? null : models.get(modelSelect.getValue());
        ModelCapabilities capabilities = model == null ? null : ModelCapabilities.fromJson(model.getCapabilities());
        capabilitiesInfo.setText(model == null ? loc.getValue(J_L.MSG_BODY_NO_MODEL)
                : capabilities == null ? loc.getValue(J_L.MSG_NO_CAPABILITIES)
                : String.format(loc.getValue(J_L.MSG_BODY_CAPABILITIES), capabilities.expressions().size(), DescribedPart.of(model, capabilities).size()));
        showParts(model, capabilities);
        showDescription(model);
        showOutfits();
        boolean lookingGlass = lookingGlassSelect.getValue() != null;
        previewChoice.setVisible(lookingGlass);
        if (!lookingGlass) {
            previewChoice.setValue(false);
        }
        showPreview();
    }

    /** The states of the body's switched parts ({@code BodyState}), empty while a new body is not stored. */
    private Map<String, String> partStates() {
        return body == null ? Map.of() : body.partStateMap();
    }

    private void showDescription(Model model) {
        try {
            description.setValue(StringUtils.defaultString(model == null ? null
                    : body != null ? bodyService.appearance(body) : Appearance.of(model, BodyState.toggles(model, partStates()))));
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The outfits of the body, each with what it looks like and what the user does with it. */
    private void showOutfits() {
        outfitsBox.removeAll();
        Model model = modelSelect.getValue() == null ? null : models.get(modelSelect.getValue());
        addOutfitButton.setEnabled(model != null);
        outfitsHint.setText(loc.getValue(model == null ? J_L.MSG_BODY_OUTFITS_NEED_MODEL : J_L.MSG_BODY_OUTFITS_HINT));
        if (body == null || model == null) {
            return;
        }
        try {
            Outfit wearing = bodyService.currentOutfit(body);
            for (Outfit outfit : bodyService.outfits(body)) {
                boolean worn = wearing != null && wearing.id().equals(outfit.id());
                Span name = new Span(outfit.name() + (worn ? " · " + loc.getValue(J_L.LABEL_OUTFIT_WORN) : ""));
                Span text = new Span(StringUtils.defaultIfBlank(outfit.description(), loc.getValue(J_L.LABEL_OUTFIT_UNDESCRIBED)));
                text.addClassName(JoiCssStyles.BODY_OUTFIT_DESCRIPTION);
                text.getElement().setProperty("title", text.getText());
                Div label = new Div(name, text);
                label.addClassName(JoiCssStyles.BODY_OUTFIT_TEXT);
                Button wear = new Button(Solid.SHIRT.create(), e -> wear(outfit));
                wear.setTooltipText(loc.getValue(J_L.LABEL_OUTFIT_WEAR));
                wear.setEnabled(!worn);
                Button edit = new Button(Solid.PEN.create(), e -> editOutfit(outfit));
                edit.setTooltipText(loc.getValue(L.LABEL_EDIT));
                Button delete = new Button(Solid.TRASH.create(), e -> ConfirmDialog.show(loc.getValue(L.MSG_CONFIRM_DELETE), () -> {
                    try {
                        bodyService.deleteOutfit(body, outfit.id());
                        showOutfits();
                    } catch (Exception ex) {
                        UIUtils.internalServerError(loc, ex);
                    }
                }));
                delete.setThemeName("error tertiary");
                delete.setTooltipText(loc.getValue(L.LABEL_DELETE));
                Div row = new Div(label, wear, edit, delete);
                row.addClassName(JoiCssStyles.BODY_OUTFIT);
                outfitsBox.add(row);
            }
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The user puts an outfit on the body: stored (she is told when she is in the body), shown everywhere in the dialog. */
    private void wear(Outfit outfit) {
        try {
            bodyService.wear(body, outfit.id());
            body = bodyService.find(body.getId());
            Model model = modelSelect.getValue() == null ? null : models.get(modelSelect.getValue());
            showParts(model, model == null ? null : ModelCapabilities.fromJson(model.getCapabilities()));
            showDescription(model);
            showOutfits();
            showState();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** Opens the outfit's dialog: a new outfit (null; it starts from what the body shows) or a stored one. */
    private void editOutfit(Outfit outfit) {
        try {
            if (body == null) {
                store();
            }
            Model model = modelSelect.getValue() == null ? null : models.get(modelSelect.getValue());
            if (body == null || model == null) {
                return;
            }
            OutfitDialog dialog = new OutfitDialog(body, model, outfit, () -> {
                showOutfits();
                showDescription(model);
            });
            dialog.create();
            dialog.open();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** She is told of what is new: the body, its outfits (one event, added to a waiting one). */
    private void announce() {
        try {
            if (body != null) {
                bodyService.announce(body);
            }
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** A choice of the state for each part of the model described apart. */
    private void showParts(Model model, ModelCapabilities capabilities) {
        parts.removeAll();
        partSelects.clear();
        List<DescribedPart> described = model == null ? List.of() : DescribedPart.of(model, capabilities);
        partsHint.setText(loc.getValue(model == null ? J_L.MSG_BODY_NO_MODEL : described.isEmpty() ? J_L.MSG_BODY_NO_PARTS : J_L.MSG_BODY_PARTS_HINT));
        Map<String, String> states = partStates();
        for (DescribedPart part : described) {
            Select<String> select = new Select<>();
            select.setLabel(part.name());
            select.setItems(part.states().stream().map(DescribedPart.State::id).toList());
            select.setItemLabelGenerator(id -> stateLabel(part, BodyState.state(part, id)));
            DescribedPart.State in = BodyState.stateOf(model, part, states);
            select.setValue(in == null ? null : in.id());
            select.addValueChangeListener(e -> {
                if (e.isFromClient() && e.getValue() != null) {
                    switchPart(model, part, e.getValue());
                }
            });
            partSelects.put(part.id(), select);
            parts.add(select);
        }
    }

    private String stateLabel(DescribedPart part, DescribedPart.State state) {
        if (state == null) {
            return "";
        }
        String name = part.group() || !state.name().equals(state.id()) ? state.name()
                : loc.getValue(DescribedPart.SHOWN.equals(state.id()) ? J_L.LABEL_PART_SHOWN : J_L.LABEL_PART_HIDDEN);
        return part.isDefault(state) ? name + loc.getValue(J_L.LABEL_STATE_DEFAULT_SUFFIX) : name;
    }

    /** The user switched a part: stored (she is told when she is in the body), shown on the preview and in her look. */
    private void switchPart(Model model, DescribedPart part, String stateId) {
        try {
            if (body == null) {
                store();
            }
            if (body == null) {
                return;
            }
            bodyService.switchPart(body, part.id(), stateId);
            body = bodyService.find(body.getId());
            showDescription(model);
            showOutfits();
            showState();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The preview of the full model in the state of the body (the Looking Glass one as it comes). */
    private void showState() {
        Model model = modelSelect.getValue() == null ? null : models.get(modelSelect.getValue());
        if (model != null && !Boolean.TRUE.equals(previewChoice.getValue())) {
            viewer.setToggles(AppearancePanel.toggles(model, BodyState.toggles(model, partStates())));
        }
    }

    /** The choice of the state of a part, by the part's id, for tests. */
    public Select<String> getPartSelect(String partId) {
        return partSelects.get(partId);
    }

    private void showPreview() {
        try {
            Long id = Boolean.TRUE.equals(previewChoice.getValue()) ? lookingGlassSelect.getValue() : modelSelect.getValue();
            Model model = id == null ? null : models.get(id);
            File file = model == null ? null : modelService.bodyFile(model);
            previewInfo.setText("");
            if (file == null) {
                viewer.clear();
            } else {
                viewer.show(file, modelService.bodyFormat(model), null);
            }
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void updateTitle() throws Exception {
        String name = body == null || StringUtils.isBlank(body.getName()) ? loc.getValue(J_L.LABEL_NEW_BODY) : body.getName();
        setHeaderTitle(joiService.name(joi) + ": " + name);
    }

    /** The body as stored last, null while a new one is not stored, for tests. */
    public Body getBody() {
        return body;
    }

    /** The name field, for tests. */
    public TextField getNameField() {
        return nameField;
    }

    /** The choice of the full model, for tests. */
    public Select<Long> getModelSelect() {
        return modelSelect;
    }

    /** The choice of the Looking Glass model, for tests. */
    public Select<Long> getLookingGlassSelect() {
        return lookingGlassSelect;
    }

    /** Whether the body plays all of the library, for tests. */
    public Checkbox getAllAnimations() {
        return allAnimations;
    }

    /** The choice of the body's animations, for tests. */
    public MultiSelectComboBox<String> getAnimationSelect() {
        return animationSelect;
    }

    /** Exit: writes the body's .joia when it changed and closes, for tests. */
    public void exitForTest() {
        exit();
    }

    /** The appearance description, for tests. */
    public TextArea getDescription() {
        return description;
    }
}
