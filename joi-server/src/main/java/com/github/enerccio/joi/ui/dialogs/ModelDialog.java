package com.github.enerccio.joi.ui.dialogs;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.bodies.ConversionReport;
import com.github.enerccio.joi.domain.bodies.ModelCapabilities;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.collections.ConversionStatus;
import com.github.enerccio.joi.domain.collections.ModelFormat;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.service.ModelConversionService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.joi.ui.bodies.ModelViewer;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.loc.LocalizationKey;
import com.github.enerccio.marginalia.ui.widgets.Notification;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.checkbox.CheckboxGroupVariant;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.server.streams.UploadHandler;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.io.File;
import java.nio.file.Files;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One 3D model ({@link Model}), full screen: on the left its name, the upload, what the file is and its conversion to
 * VRM; on the right the {@link ModelViewer 3D preview} of the upload or of the converted VRM. Changes are stored at
 * once. A new model is stored once it gets a name or a file.
 * <p>
 * The upload has no size limit (models are large) and streams to a file of the user's resources, never into memory.
 * A .vrm or .glb is one file; a model of several files (an .fbx or a .gltf with its textures) comes as a .zip; a
 * VRChat avatar as its .unitypackage.
 * <p>
 * The conversion ({@link ModelConversionService}) reads the chosen FBX; what is left out is the user's choice: the
 * meshes (known after the first conversion, from its report) and shape keys to delete or to bake in.
 */
@Configurable
public class ModelDialog extends Dialog {

    private static final int LOG_LINES = 200;

    @Autowired
    private Localization loc;

    @Autowired
    private ModelService modelService;

    @Autowired
    private ModelConversionService conversionService;

    private Model model;
    private Runnable onClose = () -> {
    };
    private TextField nameField;
    private Upload upload;
    private Div fileInfo;
    private Span previewInfo;
    private ModelViewer viewer;
    private RadioButtonGroup<Boolean> previewChoice;

    private VerticalLayout conversion;
    private VerticalLayout capabilities;
    private Span capabilitiesInfo;
    private Div expressionButtons;
    private VerticalLayout toggleRows;
    private String triedExpression;
    private Span conversionHint;
    private Select<String> fbxSelect;
    private Select<Integer> textureSelect;
    private CheckboxGroup<String> meshes;
    private Span meshesHint;
    private TextArea dropKeys;
    private TextArea bakeKeys;
    private Button convertButton;
    private Span conversionStatus;
    private Div conversionSummary;
    private Pre logView;
    private final Deque<String> logLines = new ArrayDeque<>();
    private Runnable stopListening = () -> {
    };
    private boolean loading;

    public ModelDialog(Model model) {
        this.model = model;
    }

    /** What runs when the dialog closes (the table of the models refreshes). */
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
        nameField.setValue(StringUtils.defaultString(model.getName()));
        nameField.addValueChangeListener(e -> rename(e.getValue()));

        File folder = modelService.uploadFolder();
        UploadHandler handler = UploadHandler.toFile((metadata, file) -> uploaded(file, metadata.fileName()),
                metadata -> ModelFiles.uploadFile(folder, metadata.fileName()));
        upload = new Upload(handler);
        upload.setMaxFiles(1);
        upload.setAcceptedFileTypes(".vrm", ".glb", ".gltf", ".fbx", ".zip", ".unitypackage");
        upload.setWidthFull();
        upload.setUploadButton(new Button(loc.getValue(J_L.LABEL_UPLOAD_3D_MODEL), Solid.UPLOAD.create()));

        Span hint = new Span(loc.getValue(J_L.MSG_3D_MODEL_UPLOAD_HINT));
        hint.addClassName(CssStyles.HINT_TEXT);

        fileInfo = new Div();
        previewInfo = new Span();
        previewInfo.addClassName(CssStyles.HINT_TEXT);

        createConversion();
        createCapabilities();

        VerticalLayout sidebar = new VerticalLayout(nameField, upload, hint, fileInfo, previewInfo, conversion, capabilities);
        sidebar.setPadding(false);
        sidebar.addClassName(JoiCssStyles.MODEL_SIDEBAR);

        viewer = new ModelViewer();
        viewer.setSizeFull();
        viewer.addInfoListener(e -> previewInfo.setText(
                (e.getVrm() == null ? "" : "VRM " + e.getVrm() + " · " + e.getExpressions() + " expressions · ")
                        + String.format("%,d triangles · %d meshes · %d materials", e.getTriangles(), e.getMeshes(), e.getMaterials())));
        viewer.addErrorListener(e -> previewInfo.setText(e.getMessage()));
        previewChoice = new RadioButtonGroup<>();
        previewChoice.setItems(false, true);
        previewChoice.setItemLabelGenerator(converted -> loc.getValue(converted ? J_L.LABEL_PREVIEW_CONVERTED : J_L.LABEL_PREVIEW_UPLOADED));
        previewChoice.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                showPreview();
            }
        });
        VerticalLayout previewColumn = new VerticalLayout(previewChoice, viewer);
        previewColumn.setPadding(false);
        previewColumn.setSpacing(false);
        previewColumn.addClassName(JoiCssStyles.MODEL_VIEWER);
        previewColumn.setFlexGrow(1, viewer);

        HorizontalLayout body = new HorizontalLayout(sidebar, previewColumn);
        body.setSizeFull();
        body.setPadding(false);
        body.setFlexGrow(1, previewColumn);
        body.addClassName(CssStyles.OVERFLOW_HIDDEN);
        add(body);

        Button exitButton = new Button(loc.getValue(L.LABEL_EXIT), e -> close());
        exitButton.setThemeName("primary");
        getFooter().add(exitButton);

        addOpenedChangeListener(e -> {
            if (!e.isOpened()) {
                stopListening.run();
                onClose.run();
            }
        });
        showFile();
        nameField.focus();
    }

    private void createConversion() {
        H4 title = new H4(loc.getValue(J_L.LABEL_CONVERSION));
        conversionHint = new Span();
        conversionHint.addClassName(CssStyles.HINT_TEXT);

        fbxSelect = new Select<>();
        fbxSelect.setLabel(loc.getValue(J_L.LABEL_AVATAR_FBX));
        fbxSelect.setWidthFull();
        fbxSelect.addValueChangeListener(e -> {
            if (!loading && e.getValue() != null && !e.getValue().equals(model.fbxToConvert())) {
                model.setConvertFbx(e.getValue());
                // the meshes of another FBX are other meshes
                model.setRemoveMeshes(List.of());
                store();
                showConversion();
            }
        });

        textureSelect = new Select<>();
        textureSelect.setLabel(loc.getValue(J_L.LABEL_MAX_TEXTURE_SIZE));
        textureSelect.setItems(1024, 2048, 4096, 8192);
        textureSelect.setWidthFull();
        textureSelect.addValueChangeListener(e -> {
            if (!loading && e.getValue() != null) {
                model.setMaxTextureSize(e.getValue());
                store();
            }
        });

        meshes = new CheckboxGroup<>(loc.getValue(J_L.LABEL_MESHES));
        meshes.addThemeVariants(CheckboxGroupVariant.LUMO_VERTICAL);
        meshes.addValueChangeListener(e -> {
            if (!loading) {
                Set<String> kept = e.getValue();
                model.setRemoveMeshes(meshNames().stream().filter(name -> !kept.contains(name)).toList());
                store();
            }
        });
        meshesHint = new Span(loc.getValue(J_L.MSG_MESHES_HINT));
        meshesHint.addClassName(CssStyles.HINT_TEXT);

        dropKeys = new TextArea(loc.getValue(J_L.LABEL_DROP_SHAPE_KEYS));
        dropKeys.setWidthFull();
        dropKeys.addValueChangeListener(e -> {
            if (!loading) {
                model.setDropShapeKeys(lines(e.getValue()));
                store();
            }
        });
        bakeKeys = new TextArea(loc.getValue(J_L.LABEL_BAKE_SHAPE_KEYS));
        bakeKeys.setWidthFull();
        bakeKeys.addValueChangeListener(e -> {
            if (!loading) {
                model.setBakeShapeKeys(lines(e.getValue()));
                store();
            }
        });
        VerticalLayout keys = new VerticalLayout(dropKeys, bakeKeys);
        keys.setPadding(false);
        Details keysDetails = new Details(loc.getValue(J_L.LABEL_SHAPE_KEYS), keys);
        keysDetails.setWidthFull();

        convertButton = new Button(loc.getValue(J_L.LABEL_CONVERT), Solid.MAGIC.create(), e -> convert());
        convertButton.setThemeName("primary");
        conversionStatus = new Span();
        conversionSummary = new Div();
        conversionSummary.addClassName(CssStyles.HINT_TEXT);

        logView = new Pre();
        logView.addClassName(JoiCssStyles.CONVERSION_LOG);
        Details logDetails = new Details(loc.getValue(J_L.LABEL_LOG), logView);
        logDetails.setWidthFull();

        conversion = new VerticalLayout(title, conversionHint, fbxSelect, textureSelect, meshes, meshesHint, keysDetails,
                convertButton, conversionStatus, conversionSummary, logDetails);
        conversion.setPadding(false);
    }

    private void createCapabilities() {
        H4 title = new H4(loc.getValue(J_L.LABEL_CAPABILITIES));
        capabilitiesInfo = new Span();
        capabilitiesInfo.addClassName(CssStyles.HINT_TEXT);
        Span expressionsTitle = new Span(loc.getValue(J_L.LABEL_EXPRESSIONS));
        expressionButtons = new Div();
        expressionButtons.addClassName(JoiCssStyles.EXPRESSION_BUTTONS);
        Span togglesTitle = new Span(loc.getValue(J_L.LABEL_TOGGLES));
        toggleRows = new VerticalLayout();
        toggleRows.setPadding(false);
        toggleRows.setSpacing(false);
        capabilities = new VerticalLayout(title, capabilitiesInfo, expressionsTitle, expressionButtons, togglesTitle, toggleRows);
        capabilities.setPadding(false);
    }

    /** What the model can do: its rig, expressions to try, toggles to try and to name. */
    private void showCapabilities() throws Exception {
        // read again on each opening (only the JSON of the VRM): what was stored by an older version is brought up to date
        String before = model.getCapabilities();
        if (model.getId() != null && modelService.readCapabilities(model) && !Objects.equals(before, model.getCapabilities())) {
            store();
        }
        ModelCapabilities read = ModelCapabilities.fromJson(model.getCapabilities());
        expressionButtons.removeAll();
        toggleRows.removeAll();
        triedExpression = null;
        capabilities.setVisible(model.getId() != null && model.getFormat() != null);
        if (read == null) {
            capabilitiesInfo.setText(loc.getValue(J_L.MSG_NO_CAPABILITIES));
            expressionButtons.setVisible(false);
            toggleRows.setVisible(false);
            return;
        }
        expressionButtons.setVisible(true);
        toggleRows.setVisible(true);
        String bones = read.vrm() == null ? "" : String.format(loc.getValue(J_L.MSG_HUMANOID_BONES), read.bones().size());
        String missing = read.missing().isEmpty() ? "" : " · " + String.format(loc.getValue(J_L.MSG_MISSING_BONES), String.join(", ", read.missing()));
        capabilitiesInfo.setText((read.vrm() == null ? "" : "VRM " + read.vrm() + " · ") + bones + missing + ". "
                + loc.getValue(J_L.MSG_CAPABILITIES_HINT));

        for (ModelCapabilities.Expression expression : read.expressions()) {
            Button button = new Button(expression.name());
            button.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_SMALL);
            button.setTooltipText(expression.kind() + " · " + expression.keys());
            button.addClickListener(e -> tryExpression(expression.name()));
            expressionButtons.add(button);
        }
        for (ModelCapabilities.Toggle toggle : read.toggles()) {
            com.vaadin.flow.component.checkbox.Checkbox shown = new com.vaadin.flow.component.checkbox.Checkbox(toggle.shown());
            shown.setTooltipText(toggle.key() == null ? toggle.mesh() : toggle.mesh() + " / " + toggle.key());
            shown.addValueChangeListener(e -> {
                if (toggle.key() == null) {
                    viewer.setMeshVisible(toggle.mesh(), e.getValue());
                } else {
                    // a "Hide X" key at full weight hides the part
                    viewer.setMorph(toggle.mesh(), toggle.key(), e.getValue() == toggle.hides() ? 0 : 1);
                }
            });
            TextField name = new TextField();
            name.addThemeVariants(com.vaadin.flow.component.textfield.TextFieldVariant.LUMO_SMALL);
            name.setWidthFull();
            name.setValue(model.getCapabilityNames().getOrDefault(toggle.id(), toggle.defaultName()));
            name.setHelperText(toggle.key() == null ? toggle.mesh() : toggle.mesh() + " / " + toggle.key());
            name.addValueChangeListener(e -> rename(toggle.id(), e.getValue(), toggle.defaultName()));
            HorizontalLayout row = new HorizontalLayout(shown, name);
            row.setWidthFull();
            row.setAlignItems(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.BASELINE);
            row.setFlexGrow(1, name);
            toggleRows.add(row);
        }
    }

    /** Shows the expression on the preview; clicking it again clears it. */
    private void tryExpression(String name) {
        if (name.equals(triedExpression)) {
            viewer.setExpression(name, 0);
            triedExpression = null;
        } else {
            viewer.setExpression(name, 1);
            triedExpression = name;
        }
    }

    /** Names a capability; its default name (or a blank one) is not stored. */
    private void rename(String id, String value, String defaultName) {
        String name = StringUtils.strip(value);
        java.util.Map<String, String> names = new java.util.LinkedHashMap<>(model.getCapabilityNames());
        if (StringUtils.isBlank(name) || name.equals(defaultName)) {
            names.remove(id);
        } else {
            names.put(id, name);
        }
        model.setCapabilityNames(names);
        store();
    }

    private static List<String> lines(String text) {
        return Arrays.stream(StringUtils.defaultString(text).split("\\R")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private void rename(String value) {
        String name = StringUtils.defaultIfBlank(StringUtils.strip(value), null);
        if (Objects.equals(name, model.getName()) || (name == null && model.getId() == null)) {
            return;
        }
        model.setName(name);
        store();
        updateTitle();
    }

    /** Stores the model after a change of the dialog (the latest from the database, so a finished conversion is kept). */
    private void store() {
        try {
            if (model.getId() != null) {
                Model stored = modelService.find(model.getId());
                stored.setName(model.getName());
                stored.setConvertFbx(model.getConvertFbx());
                stored.setRemoveMeshes(model.getRemoveMeshes());
                stored.setDropShapeKeys(model.getDropShapeKeys());
                stored.setBakeShapeKeys(model.getBakeShapeKeys());
                stored.setMaxTextureSize(model.getMaxTextureSize());
                stored.setCapabilityNames(model.getCapabilityNames());
                if (model.getCapabilities() != null) {
                    stored.setCapabilities(model.getCapabilities());
                }
                model = stored;
            }
            model = modelService.save(model);
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void uploaded(File file, String fileName) {
        try {
            model = modelService.attach(model, file, fileName);
            nameField.setValue(StringUtils.defaultString(model.getName()));
            updateTitle();
            showFile();
            Notification.success(loc.getValue(J_L.MSG_3D_MODEL_UPLOADED));
        } catch (IllegalArgumentException e) {
            Notification.error(loc.getValue(J_L.MSG_NOT_A_3D_MODEL));
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
            try {
                Files.deleteIfExists(file.toPath());
            } catch (Exception ignored) {
                // left for the garbage panel of the administration
            }
        } finally {
            upload.clearFileList();
        }
    }

    private void convert() {
        try {
            UI ui = UI.getCurrent();
            logLines.clear();
            logView.setText("");
            conversionService.convert(model, listener(ui));
            model = modelService.find(model.getId());
            showConversion();
        } catch (IllegalStateException e) {
            Notification.error(e.getMessage());
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private ModelConversionService.Listener listener(UI ui) {
        return new ModelConversionService.Listener() {
            @Override
            public void line(String line) {
                ui.access(() -> appendLog(line));
            }

            @Override
            public void finished(Model finished) {
                ui.access(() -> {
                    model = finished;
                    try {
                        showFile();
                    } catch (Exception e) {
                        UIUtils.internalServerError(loc, e);
                    }
                });
            }
        };
    }

    private void appendLog(String line) {
        logLines.addLast(line);
        while (logLines.size() > LOG_LINES) {
            logLines.removeFirst();
        }
        logView.setText(String.join("\n", logLines));
    }

    /** What the file is, its conversion, and the preview. */
    private void showFile() throws Exception {
        fileInfo.removeAll();
        previewInfo.setText("");
        boolean hasFile = model.getId() != null && model.getFormat() != null && modelService.file(model) != null;
        upload.setUploadButton(new Button(loc.getValue(hasFile ? J_L.LABEL_REPLACE_3D_MODEL : J_L.LABEL_UPLOAD_3D_MODEL), Solid.UPLOAD.create()));
        if (!hasFile) {
            Span none = new Span(loc.getValue(J_L.MSG_NO_3D_MODEL_FILE));
            none.addClassName(CssStyles.HINT_TEXT);
            fileInfo.add(none);
        } else {
            line(L.LABEL_FILE, model.getFileName());
            line(J_L.LABEL_FORMAT, model.getFormat().name());
            if (model.getFileSize() != null) {
                line(L.LABEL_SIZE, FileUtils.byteCountToDisplaySize(model.getFileSize()));
            }
            if (model.getMainFile() != null) {
                line(J_L.LABEL_MAIN_FILE, model.getMainFile());
                line(J_L.LABEL_FILES, String.valueOf(model.getFileCount()));
            }
        }
        conversion.setVisible(hasFile);
        showConversion();

        boolean converted = model.getId() != null && modelService.convertedFile(model) != null;
        previewChoice.setVisible(hasFile && converted);
        previewChoice.setItemEnabledProvider(choice -> choice || !ModelFiles.isUnityPackage(model.getFileName()));
        previewChoice.setValue(converted);
        showPreview();
        showCapabilities();
    }

    private void showPreview() {
        try {
            boolean converted = Boolean.TRUE.equals(previewChoice.getValue());
            File file = converted ? modelService.convertedFile(model) : model.getId() == null ? null : modelService.file(model);
            previewInfo.setText("");
            if (file == null || model.getFormat() == null) {
                viewer.clear();
            } else if (converted) {
                viewer.show(file, ModelFormat.VRM, null);
            } else if (ModelFiles.isUnityPackage(model.getFileName())) {
                viewer.clear();
                previewInfo.setText(loc.getValue(J_L.MSG_PACKAGE_PREVIEW));
            } else {
                viewer.show(file, model.getFormat(), model.getMainFile());
            }
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The conversion settings, its state and the summary of its last report. */
    private void showConversion() {
        loading = true;
        try {
            boolean running = conversionService.isRunning(model);
            boolean convertible = model.fbxToConvert() != null;
            conversionHint.setText(convertible ? "" : loc.getValue(model.getFormat() == ModelFormat.VRM ? J_L.MSG_ALREADY_VRM : J_L.MSG_NO_FBX));
            conversionHint.setVisible(!convertible);
            for (var field : List.of(fbxSelect, textureSelect, meshes, meshesHint, convertButton)) {
                field.setVisible(convertible);
            }
            fbxSelect.setItems(model.getFbxFiles());
            fbxSelect.setValue(model.fbxToConvert());
            fbxSelect.setVisible(convertible && model.getFbxFiles().size() > 1);
            textureSelect.setValue(model.getMaxTextureSize() == null ? 2048 : model.getMaxTextureSize());
            dropKeys.setValue(String.join("\n", model.getDropShapeKeys()));
            bakeKeys.setValue(String.join("\n", model.getBakeShapeKeys()));

            ConversionReport report = ConversionReport.parse(model.getConversionReport());
            List<ConversionReport.Mesh> reported = report == null ? List.of() : report.meshes();
            meshes.setItems(reported.stream().map(ConversionReport.Mesh::name).toList());
            meshes.setItemLabelGenerator(name -> reported.stream().filter(m -> m.name().equals(name)).findFirst()
                    .map(m -> String.format("%s (%,d)", m.name(), m.triangles())).orElse(name));
            Set<String> kept = new LinkedHashSet<>(meshNames());
            kept.removeAll(model.getRemoveMeshes());
            meshes.setValue(kept);
            meshes.setVisible(convertible && !reported.isEmpty());
            meshesHint.setVisible(convertible && reported.isEmpty());

            convertButton.setEnabled(!running);
            convertButton.setText(loc.getValue(model.getConvertedResourceId() == null ? J_L.LABEL_CONVERT : J_L.LABEL_CONVERT_AGAIN));
            conversionStatus.setText(status(running));
            conversionStatus.setClassName(CssStyles.HINT_TEXT, !running && model.getConversionStatus() != ConversionStatus.FAILED);

            conversionSummary.removeAll();
            if (report != null && !running) {
                long matched = report.materials().stream().filter(m -> m.unity() != null).count();
                long textured = report.materials().stream().filter(ConversionReport.Material::textured).count();
                if (report.triangles() != null && report.triangles() > 0) {
                    conversionSummary.add(new Div(String.format(loc.getValue(J_L.MSG_CONVERSION_SUMMARY), report.triangles(), (int) matched,
                            report.materials().size(), (int) textured, report.expressions().size(),
                            report.springs() == null ? 0 : report.springs())));
                }
                if (!report.humanoidMissing().isEmpty()) {
                    conversionSummary.add(new Div(String.format(loc.getValue(J_L.MSG_MISSING_BONES), String.join(", ", report.humanoidMissing()))));
                }
                for (String warning : report.warnings()) {
                    conversionSummary.add(new Div(warning));
                }
            }
            if (!running && logLines.isEmpty() && model.getConversionLog() != null) {
                logView.setText(model.getConversionLog());
            }
            stopListening.run();
            stopListening = running ? conversionService.listen(model, listener(UI.getCurrent())) : () -> {
            };
        } finally {
            loading = false;
        }
    }

    private String status(boolean running) {
        if (running) {
            return loc.getValue(J_L.MSG_CONVERTING);
        }
        if (model.getConversionStatus() == ConversionStatus.FAILED) {
            return String.format(loc.getValue(J_L.MSG_CONVERSION_FAILED), StringUtils.defaultString(model.getConversionError()));
        }
        if (model.getConvertedResourceId() != null && model.getConvertedAt() != null) {
            return String.format(loc.getValue(J_L.MSG_CONVERTED), DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(model.getConvertedAt()),
                    model.getConvertedFileSize() == null ? "" : FileUtils.byteCountToDisplaySize(model.getConvertedFileSize()));
        }
        return "";
    }

    private List<String> meshNames() {
        ConversionReport report = ConversionReport.parse(model.getConversionReport());
        return report == null ? List.of() : report.meshes().stream().map(ConversionReport.Mesh::name).toList();
    }

    private void line(LocalizationKey label, String value) {
        fileInfo.add(new Div(new Span(loc.getValue(label) + ": "), new Span(StringUtils.defaultString(value))));
    }

    private void updateTitle() {
        setHeaderTitle(StringUtils.isNotBlank(model.getName()) ? model.getName() : loc.getValue(J_L.LABEL_NEW_3D_MODEL));
    }

    /** The model as stored last, for tests. */
    public Model getModel() {
        return model;
    }

    /** What an upload does once the file is written, for tests. */
    void uploadedForTest(File file, String fileName) {
        uploaded(file, fileName);
    }

    /** The name field, for tests. */
    TextField getNameField() {
        return nameField;
    }

    /** The toggles, for tests. */
    VerticalLayout getToggleRows() {
        return toggleRows;
    }

    /** The mesh checklist, for tests. */
    CheckboxGroup<String> getMeshes() {
        return meshes;
    }
}
