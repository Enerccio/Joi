package com.github.enerccio.joi.ui.dialogs;

import com.github.enerccio.joi.domain.bodies.Appearance;
import com.github.enerccio.joi.domain.bodies.ModelCapabilities;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.service.BodyService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.joi.ui.bodies.ModelViewer;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.github.enerccio.marginalia.ui.widgets.ResizableTextArea;
import com.github.enerccio.joi.ui.widgets.PromptAreas;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One body of an assistant ({@link Body}), full screen: on the left its name, its models (the full one and the one
 * for the Looking Glass, from the user's models with a VRM) and how she looks in it (the model's description, put
 * together for the state of the body: it is described and edited in the model's dialog); on the right the
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
    private Span capabilitiesInfo;
    private RadioButtonGroup<Boolean> previewChoice;
    private ModelViewer viewer;
    private Span previewInfo;

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

        VerticalLayout sidebar = new VerticalLayout(nameField, modelSelect, lookingGlassSelect, modelHint, capabilitiesInfo, description);
        sidebar.setPadding(false);
        sidebar.addClassName(JoiCssStyles.MODEL_SIDEBAR);

        viewer = new ModelViewer();
        viewer.setSizeFull();
        previewInfo = new Span();
        previewInfo.addClassName(CssStyles.HINT_TEXT);
        viewer.addInfoListener(e -> previewInfo.setText(String.format("%,d triangles · %d meshes · %d materials",
                e.getTriangles(), e.getMeshes(), e.getMaterials())));
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

        HorizontalLayout content = new HorizontalLayout(sidebar, previewColumn);
        content.setSizeFull();
        content.setPadding(false);
        content.setFlexGrow(1, previewColumn);
        content.addClassName(CssStyles.OVERFLOW_HIDDEN);
        add(content);

        Button exitButton = new Button(loc.getValue(L.LABEL_EXIT), e -> close());
        exitButton.setThemeName("primary");
        getFooter().add(exitButton);

        addOpenedChangeListener(e -> {
            if (!e.isOpened()) {
                onClose.run();
            }
        });
        showModel();
        nameField.focus();
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
            Long modelId = modelSelect.getValue();
            if (body == null) {
                if (StringUtils.isBlank(name) && modelId == null) {
                    return;
                }
                body = bodyService.create(joi, name);
            } else {
                body = bodyService.find(body.getId());
            }
            body.setName(StringUtils.defaultIfBlank(name, null));
            body.setModel(modelId == null ? null : models.get(modelId));
            Long lookingGlassId = lookingGlassSelect.getValue();
            body.setLookingGlassModel(lookingGlassId == null ? null : models.get(lookingGlassId));
            body = bodyService.save(body);
            updateTitle();
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
                : String.format(loc.getValue(J_L.MSG_BODY_CAPABILITIES), capabilities.expressions().size(), capabilities.toggles().size()));
        description.setValue(StringUtils.defaultString(model == null ? null : Appearance.of(model)));
        boolean lookingGlass = lookingGlassSelect.getValue() != null;
        previewChoice.setVisible(lookingGlass);
        if (!lookingGlass) {
            previewChoice.setValue(false);
        }
        showPreview();
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

    /** The appearance description, for tests. */
    public TextArea getDescription() {
        return description;
    }
}
