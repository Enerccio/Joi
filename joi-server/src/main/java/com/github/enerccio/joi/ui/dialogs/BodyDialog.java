package com.github.enerccio.joi.ui.dialogs;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.bodies.BodyProposal;
import com.github.enerccio.joi.domain.bodies.ModelCapabilities;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.collections.ProposalStatus;
import com.github.enerccio.joi.domain.service.BodySelfImageService;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.ui.widgets.Notification;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.server.streams.DownloadHandler;
import com.vaadin.flow.server.streams.UploadHandler;
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
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One body of an assistant ({@link Body}), full screen: on the left its name, its models (the full one and the one
 * for the Looking Glass, from the user's models with a VRM) and how she looks in it; on the right the
 * {@link ModelViewer 3D preview} of either model. Changes are stored at once; a new body is stored once it gets a name
 * or a model (her first body is the one she is in).
 * <p>
 * Her <b>self-image</b> (once the body has a model): the viewer takes snapshots of the full model, the user adds images
 * and notes, and {@link BodySelfImageService} asks a multimodal model for the description and the toggle names. The
 * proposal is reviewed here (the description edited, each name kept or not) before it is used.
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
    private BodySelfImageService selfImageService;

    private final Joi joi;
    private Body body;
    private Runnable onClose = () -> {
    };
    /** The user's models a body can be made of, by id. */
    private final Map<Long, Model> models = new LinkedHashMap<>();
    /** Something the user does that can fail. */
    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    /** The order of the snapshots (the viewer's shots). */
    private static final List<String> SHOT_ORDER = List.of("full-front", "full-three-quarter", "full-profile", "full-back",
            "face-front", "face-three-quarter");
    private TextField nameField;
    private Select<Long> modelSelect;
    private Select<Long> lookingGlassSelect;
    private TextArea description;
    private Span capabilitiesInfo;
    private RadioButtonGroup<Boolean> previewChoice;
    private ModelViewer viewer;
    private Span previewInfo;

    private VerticalLayout selfImage;
    private Span describedInfo;
    private Button snapshotButton;
    private Span snapshotInfo;
    private Div snapshotThumbnails;
    private Upload referenceUpload;
    private Div referenceThumbnails;
    private TextArea notes;
    private Button proposeButton;
    private Span proposalStatus;
    private VerticalLayout proposalPanel;
    private TextArea proposedDescription;
    private VerticalLayout proposedToggles;
    private final Map<String, Checkbox> toggleChoices = new LinkedHashMap<>();
    private final Map<String, String> proposedNames = new LinkedHashMap<>();
    /** The snapshots the viewer sent, until it says they are all there. */
    private final Map<String, byte[]> pendingShots = new ConcurrentHashMap<>();
    private Runnable stopListening = () -> {
    };
    private boolean loading;
    /** The images the thumbnails show now: they are drawn again only when these change (each is a download). */
    private List<String> shownSnapshots = List.of();
    private List<String> shownReferences = List.of();

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
        nameField.addValueChangeListener(e -> {
            store();
            showSelfImage();
        });

        modelSelect = modelSelect(J_L.LABEL_BODY_MODEL);
        modelSelect.setEmptySelectionAllowed(true);
        modelSelect.setValue(body == null || body.getModel() == null ? null : body.getModel().getId());
        modelSelect.addValueChangeListener(e -> {
            store();
            showModel();
            showSelfImage();
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
        description.addClassName(JoiCssStyles.BODY_APPEARANCE);
        description.setHelperText(loc.getValue(J_L.MSG_APPEARANCE_HINT));
        description.setValue(body == null ? "" : StringUtils.defaultString(body.getDescription()));
        description.addValueChangeListener(e -> {
            if (!loading) {
                store();
            }
        });

        createSelfImage();

        VerticalLayout sidebar = new VerticalLayout(nameField, modelSelect, lookingGlassSelect, modelHint, capabilitiesInfo, description, selfImage);
        sidebar.setPadding(false);
        sidebar.addClassName(JoiCssStyles.MODEL_SIDEBAR);

        viewer = new ModelViewer();
        viewer.setSizeFull();
        previewInfo = new Span();
        previewInfo.addClassName(CssStyles.HINT_TEXT);
        viewer.addInfoListener(e -> previewInfo.setText(String.format("%,d triangles · %d meshes · %d materials",
                e.getTriangles(), e.getMeshes(), e.getMaterials())));
        viewer.addErrorListener(e -> previewInfo.setText(e.getMessage()));
        viewer.addSnapshotsListener(e -> snapshotsTaken());
        viewer.addSnapshotsErrorListener(e -> {
            pendingShots.clear();
            snapshotButton.setEnabled(true);
            Notification.warning(String.format(loc.getValue(J_L.MSG_SNAPSHOTS_FAILED), e.getMessage()));
        });
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
                stopListening.run();
                onClose.run();
            }
        });
        showModel();
        showSelfImage();
        if (body != null && selfImageService.isRunning(body)) {
            listen();
        }
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
            body.setDescription(StringUtils.defaultIfBlank(description.getValue(), null));
            if (notes != null) {
                body.setNotes(StringUtils.defaultIfBlank(notes.getValue(), null));
            }
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

    // ----------------------------------------------------------------------------------------------------------------
    // the self-image: snapshots, the user's images and notes, the LLM's proposal to review
    // ----------------------------------------------------------------------------------------------------------------

    private void createSelfImage() throws Exception {
        H4 title = new H4(loc.getValue(J_L.LABEL_SELF_IMAGE));
        Span hint = new Span(loc.getValue(J_L.MSG_SELF_IMAGE_HINT));
        hint.addClassName(CssStyles.HINT_TEXT);
        describedInfo = new Span();
        describedInfo.addClassName(CssStyles.HINT_TEXT);

        snapshotButton = new Button(loc.getValue(J_L.LABEL_TAKE_SNAPSHOTS), Solid.CAMERA.create(), e -> takeSnapshots());
        snapshotInfo = new Span();
        snapshotInfo.addClassName(CssStyles.HINT_TEXT);
        snapshotThumbnails = new Div();
        snapshotThumbnails.addClassName(JoiCssStyles.BODY_THUMBNAILS);

        File folder = modelService.uploadFolder();
        UploadHandler handler = UploadHandler.toFile((metadata, file) -> referenceUploaded(file, metadata.fileName()),
                metadata -> ModelFiles.uploadFile(folder, metadata.fileName()));
        referenceUpload = new Upload(handler);
        referenceUpload.setAcceptedFileTypes(".png", ".jpg", ".jpeg", ".webp");
        referenceUpload.setWidthFull();
        referenceUpload.setUploadButton(new Button(loc.getValue(J_L.LABEL_UPLOAD_IMAGES), Solid.UPLOAD.create()));
        referenceUpload.addAllFinishedListener(e -> referenceUpload.clearFileList());
        Span referencesTitle = new Span(loc.getValue(J_L.LABEL_REFERENCE_IMAGES));
        referenceThumbnails = new Div();
        referenceThumbnails.addClassName(JoiCssStyles.BODY_THUMBNAILS);

        notes = new TextArea(loc.getValue(J_L.LABEL_BODY_NOTES));
        notes.setWidthFull();
        notes.setHelperText(loc.getValue(J_L.MSG_BODY_NOTES_HINT));
        notes.setValue(body == null ? "" : StringUtils.defaultString(body.getNotes()));
        notes.addValueChangeListener(e -> store());

        proposeButton = new Button(loc.getValue(J_L.LABEL_PROPOSE_DESCRIPTION), Solid.MAGIC.create(), e -> propose());
        proposeButton.setThemeName("primary");
        proposalStatus = new Span();
        proposalStatus.addClassName(CssStyles.HINT_TEXT);

        proposedDescription = new TextArea(loc.getValue(J_L.LABEL_PROPOSED_DESCRIPTION));
        proposedDescription.setWidthFull();
        proposedDescription.addClassName(JoiCssStyles.BODY_APPEARANCE);
        Span togglesTitle = new Span(loc.getValue(J_L.LABEL_PROPOSED_TOGGLE_NAMES));
        proposedToggles = new VerticalLayout();
        proposedToggles.setPadding(false);
        proposedToggles.setSpacing(false);
        Button useButton = new Button(loc.getValue(J_L.LABEL_USE_PROPOSAL), e -> useProposal());
        useButton.setThemeName("primary");
        Button discardButton = new Button(loc.getValue(J_L.LABEL_DISCARD_PROPOSAL), e -> discardProposal());
        discardButton.setThemeName("tertiary");
        proposalPanel = new VerticalLayout(proposedDescription, togglesTitle, proposedToggles, new HorizontalLayout(useButton, discardButton));
        proposalPanel.setPadding(false);

        selfImage = new VerticalLayout(title, hint, describedInfo, snapshotButton, snapshotInfo, snapshotThumbnails,
                referencesTitle, referenceUpload, referenceThumbnails, notes, proposeButton, proposalStatus, proposalPanel);
        selfImage.setPadding(false);
    }

    /** The self-image of the stored body: hidden until it has a model. */
    private void showSelfImage() {
        try {
            boolean shown = body != null && modelSelect.getValue() != null;
            selfImage.setVisible(shown);
            if (!shown) {
                return;
            }
            body = bodyService.find(body.getId());
            String hash = bodyService.modelHash(body);
            describedInfo.setVisible(body.getDescribedHash() != null);
            describedInfo.setText(loc.getValue(Objects.equals(hash, body.getDescribedHash()) ? J_L.MSG_DESCRIBED_CURRENT : J_L.MSG_DESCRIBED_OUTDATED));

            DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
            snapshotInfo.setText(body.getSnapshots().isEmpty() || body.getSnapshotsAt() == null ? loc.getValue(J_L.MSG_NO_SNAPSHOTS)
                    : String.format(loc.getValue(J_L.MSG_SNAPSHOTS_TAKEN), format.format(body.getSnapshotsAt()))
                    + (Objects.equals(hash, body.getSnapshotsHash()) ? "" : " " + loc.getValue(J_L.MSG_SNAPSHOTS_OUTDATED)));
            List<String> snapshots = List.copyOf(body.getSnapshots().values());
            if (!snapshots.equals(shownSnapshots)) {
                shownSnapshots = snapshots;
                snapshotThumbnails.removeAll();
                for (Map.Entry<String, String> shot : body.getSnapshots().entrySet()) {
                    thumbnail(snapshotThumbnails, shot.getValue(), shot.getKey(), null);
                }
            }
            List<String> references = List.copyOf(body.getReferences().keySet());
            if (!references.equals(shownReferences)) {
                shownReferences = references;
                referenceThumbnails.removeAll();
                for (Map.Entry<String, String> reference : body.getReferences().entrySet()) {
                    thumbnail(referenceThumbnails, reference.getKey(), reference.getValue(), () -> {
                        body = bodyService.removeReference(body, reference.getKey());
                        showSelfImage();
                    });
                }
            }
            showProposal();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** A thumbnail of an image of the body, with a button that removes it when it can be removed. */
    private void thumbnail(Div into, String resourceId, String name, Action remove) throws Exception {
        File file = bodyService.imageFile(body, resourceId);
        if (file == null) {
            return;
        }
        Image image = new Image(DownloadHandler.forFile(file, name).inline(), name);
        image.setTitle(name);
        Div item = new Div(image);
        if (remove != null) {
            Button removeButton = new Button(Solid.TIMES.create(), e -> {
                try {
                    remove.run();
                } catch (Exception ex) {
                    UIUtils.internalServerError(loc, ex);
                }
            });
            removeButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_ICON);
            removeButton.setTooltipText(loc.getValue(L.LABEL_DELETE));
            item.add(removeButton);
        }
        into.add(item);
    }

    /** The status of the request, and the proposal to review when there is one. */
    private void showProposal() throws Exception {
        boolean running = selfImageService.isRunning(body);
        AI ai = selfImageService.model(joiService.find(joi.getId()));
        proposeButton.setEnabled(!running && ai != null && !(body.getSnapshots().isEmpty() && body.getReferences().isEmpty()));
        BodyProposal proposal = running ? null : BodyProposal.fromJson(body.getProposal());
        if (running) {
            proposalStatus.setText(String.format(loc.getValue(J_L.MSG_PROPOSAL_RUNNING), ai == null ? "" : ai.getName()));
        } else if (body.getProposalStatus() == ProposalStatus.FAILED) {
            proposalStatus.setText(String.format(loc.getValue(J_L.MSG_PROPOSAL_FAILED), body.getProposalError()));
        } else if (proposal != null) {
            proposalStatus.setText(String.format(loc.getValue(J_L.MSG_PROPOSAL_FROM), proposal.model(),
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(proposal.at())));
        } else {
            proposalStatus.setText(ai == null ? loc.getValue(J_L.MSG_NO_VISION_MODEL) : "");
        }
        proposalStatus.setVisible(!proposalStatus.getText().isEmpty());

        proposalPanel.setVisible(proposal != null);
        toggleChoices.clear();
        proposedNames.clear();
        proposedToggles.removeAll();
        if (proposal == null) {
            return;
        }
        proposedDescription.setValue(StringUtils.defaultString(proposal.description()));
        Model model = modelSelect.getValue() == null ? null : modelService.find(modelSelect.getValue());
        ModelCapabilities capabilities = model == null ? null : ModelCapabilities.fromJson(model.getCapabilities());
        for (ModelCapabilities.Toggle toggle : capabilities == null ? List.<ModelCapabilities.Toggle>of() : capabilities.toggles()) {
            String proposed = proposal.toggles().get(toggle.id());
            String current = StringUtils.defaultIfBlank(model.getCapabilityNames().get(toggle.id()), toggle.defaultName());
            if (proposed == null || proposed.equals(current)) {
                continue;
            }
            Checkbox choice = new Checkbox(current + " → " + proposed, true);
            choice.setTooltipText(toggle.key() == null ? toggle.mesh() : toggle.mesh() + " / " + toggle.key());
            toggleChoices.put(toggle.id(), choice);
            proposedNames.put(toggle.id(), proposed);
            proposedToggles.add(choice);
        }
    }

    private void takeSnapshots() {
        if (Boolean.TRUE.equals(previewChoice.getValue())) {
            previewChoice.setValue(false);
            showPreview();
        }
        pendingShots.clear();
        snapshotButton.setEnabled(false);
        viewer.takeSnapshots(UploadHandler.inMemory((metadata, bytes) ->
                pendingShots.put(StringUtils.removeEndIgnoreCase(metadata.fileName(), ".jpg"), bytes)));
    }

    private void snapshotsTaken() {
        snapshotButton.setEnabled(true);
        try {
            Map<String, byte[]> shots = new LinkedHashMap<>();
            for (String shot : SHOT_ORDER) {
                if (pendingShots.containsKey(shot)) {
                    shots.put(shot, pendingShots.get(shot));
                }
            }
            pendingShots.keySet().stream().filter(shot -> !shots.containsKey(shot)).sorted().forEach(shot -> shots.put(shot, pendingShots.get(shot)));
            pendingShots.clear();
            if (!shots.isEmpty()) {
                body = bodyService.storeSnapshots(body, shots);
            }
            showSelfImage();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** For tests: the viewer sent the snapshots. */
    void snapshotsTakenForTest(Map<String, byte[]> shots) {
        pendingShots.putAll(shots);
        snapshotsTaken();
    }

    private void referenceUploaded(File file, String fileName) {
        try {
            body = bodyService.addReference(body, file, fileName);
            showSelfImage();
        } catch (IllegalArgumentException e) {
            Notification.warning(e.getMessage());
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** For tests: an image was uploaded. */
    void referenceUploadedForTest(File file, String fileName) {
        referenceUploaded(file, fileName);
    }

    private void propose() {
        try {
            selfImageService.propose(body, null);
            listen();
            showSelfImage();
        } catch (IllegalStateException e) {
            Notification.warning(e.getMessage());
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** Shows the end of the running request when it comes. */
    private void listen() {
        UI ui = UI.getCurrent();
        stopListening.run();
        stopListening = selfImageService.listen(body, finished -> ui.access(this::showSelfImage));
    }

    private void useProposal() {
        try {
            Map<String, String> names = new LinkedHashMap<>();
            for (Map.Entry<String, Checkbox> choice : toggleChoices.entrySet()) {
                if (choice.getValue().getValue()) {
                    names.put(choice.getKey(), proposedNames.get(choice.getKey()));
                }
            }
            bodyService.applyProposal(body, proposedDescription.getValue(), names);
            body = bodyService.find(body.getId());
            reloadModels();
            loading = true;
            try {
                description.setValue(StringUtils.defaultString(body.getDescription()));
            } finally {
                loading = false;
            }
            showModel();
            showSelfImage();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The models again, after the toggle names changed. */
    private void reloadModels() throws Exception {
        for (Long id : new ArrayList<>(models.keySet())) {
            Model model = modelService.find(id);
            if (model != null) {
                models.put(id, model);
            }
        }
    }

    private void discardProposal() {
        try {
            body = bodyService.discardProposal(body);
            showSelfImage();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The proposal's description, for tests. */
    public TextArea getProposedDescription() {
        return proposedDescription;
    }

    /** The choices of the proposed toggle names (by the toggle's id), for tests. */
    Map<String, Checkbox> getToggleChoices() {
        return toggleChoices;
    }

    /** Uses the proposal as reviewed, for tests. */
    void useProposalForTest() {
        useProposal();
    }

    /** The notes, for tests. */
    public TextArea getNotes() {
        return notes;
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
