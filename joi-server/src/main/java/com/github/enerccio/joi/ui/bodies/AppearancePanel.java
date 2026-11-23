package com.github.enerccio.joi.ui.bodies;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.bodies.AppearanceProposal;
import com.github.enerccio.joi.domain.bodies.DescribedPart;
import com.github.enerccio.joi.domain.bodies.ModelCapabilities;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.bodies.ToggleTree;
import com.github.enerccio.joi.domain.collections.ProposalStatus;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.service.AppearanceService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.widgets.Notification;
import com.github.enerccio.marginalia.ui.widgets.ResizableTextArea;
import com.github.enerccio.joi.ui.widgets.PromptAreas;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.server.streams.DownloadHandler;
import com.vaadin.flow.server.streams.UploadHandler;
import com.vaadin.flow.component.upload.Upload;
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
import java.util.function.Supplier;

/**
 * How she looks in a model (see {@code DESIGN.md} → Bodies, self-image), a section of the model dialog:
 * <ul>
 *     <li>the description of what never changes, and for each part described apart ({@link DescribedPart}: a group of
 *     toggles, or a toggle) a sentence per state, all editable (they are the model's, so every body of it and the export
 *     have them);</li>
 *     <li><i>Take snapshots</i>: the viewer renders the whole model in its default state and a close-up of each part
 *     described apart in each state; the user's images and notes;</li>
 *     <li><i>Propose a description</i>: {@link AppearanceService} asks a multimodal model, in the background; the
 *     proposal is reviewed here (edited, each name and part kept or not) before it is used.</li>
 * </ul>
 */
@Configurable
public class AppearancePanel {

    @Autowired
    private Localization loc;

    @Autowired
    private ModelService modelService;

    @Autowired
    private AppearanceService appearanceService;

    /** The order of the snapshots of the whole model (the viewer's shots). */
    public static final List<String> SHOT_ORDER = List.of("full-front", "full-three-quarter", "full-profile", "full-back",
            "face-front", "face-three-quarter");

    /** The height of the text area of a sentence of a part in its fixed mode. */
    private static final String PART_HEIGHT = "72px";

    /** Something the user does that can fail. */
    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    private final ModelViewer viewer;
    private final Supplier<Model> currentModel;
    private final Runnable beforeSnapshots;
    private final Runnable modelChanged;

    private Model model;
    private VerticalLayout layout;
    private Span describedInfo;
    private TextArea appearance;
    private VerticalLayout partRows;
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
    private VerticalLayout proposedParts;
    private final Map<String, Checkbox> toggleChoices = new LinkedHashMap<>();
    private final Map<String, String> proposedNames = new LinkedHashMap<>();
    private final Map<String, Checkbox> partChoices = new LinkedHashMap<>();
    private final Map<String, TextArea[]> partAreas = new LinkedHashMap<>();
    /** The snapshots the viewer sent, until it says they are all there; the close-ups' indexes → the parts. */
    private final Map<String, byte[]> pendingShots = new ConcurrentHashMap<>();
    private final Map<Integer, DescribedPart> pendingParts = new ConcurrentHashMap<>();
    private Runnable stopListening = () -> {
    };
    private boolean loading;
    /** The images the thumbnails show now: drawn again only when these change (each is a download). */
    private List<String> shownSnapshots = List.of();
    private List<String> shownReferences = List.of();

    /**
     * @param viewer          the dialog's preview (it takes the snapshots)
     * @param currentModel    the model of the dialog (its id is what counts)
     * @param beforeSnapshots shows the model's VRM in the viewer, which the snapshots are of
     * @param modelChanged    the model changed here (names applied): the dialog reads it again
     */
    public AppearancePanel(ModelViewer viewer, Supplier<Model> currentModel, Runnable beforeSnapshots, Runnable modelChanged) {
        this.viewer = viewer;
        this.currentModel = currentModel;
        this.beforeSnapshots = beforeSnapshots;
        this.modelChanged = modelChanged;
    }

    public Component create() throws Exception {
        H4 title = new H4(loc.getValue(J_L.LABEL_SELF_IMAGE));
        Span hint = new Span(loc.getValue(J_L.MSG_SELF_IMAGE_HINT));
        hint.addClassName(CssStyles.HINT_TEXT);
        describedInfo = new Span();
        describedInfo.addClassName(CssStyles.HINT_TEXT);

        appearance = new TextArea(loc.getValue(J_L.LABEL_APPEARANCE_CORE));
        appearance.setWidthFull();
        ResizableTextArea.install(loc, appearance, PromptAreas.TEMPLATE_HEIGHT);
        appearance.setHelperText(loc.getValue(J_L.MSG_APPEARANCE_HINT));
        appearance.addValueChangeListener(e -> {
            if (!loading) {
                edit(stored -> stored.setAppearance(StringUtils.defaultIfBlank(StringUtils.strip(e.getValue()), null)));
            }
        });
        Span partsTitle = new Span(loc.getValue(J_L.LABEL_DESCRIBED_PARTS));
        partRows = new VerticalLayout();
        partRows.setPadding(false);

        snapshotButton = new Button(loc.getValue(J_L.LABEL_TAKE_SNAPSHOTS), Solid.CAMERA.create(), e -> takeSnapshots());
        snapshotInfo = new Span();
        snapshotInfo.addClassName(CssStyles.HINT_TEXT);
        snapshotThumbnails = new Div();
        snapshotThumbnails.addClassName(JoiCssStyles.BODY_THUMBNAILS);
        viewer.addSnapshotsListener(e -> snapshotsTaken());
        viewer.addSnapshotsErrorListener(e -> {
            pendingShots.clear();
            snapshotButton.setEnabled(true);
            Notification.warning(String.format(loc.getValue(J_L.MSG_SNAPSHOTS_FAILED), e.getMessage()));
        });

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
        ResizableTextArea.install(loc, notes, PromptAreas.TEXT_HEIGHT);
        notes.addValueChangeListener(e -> {
            if (!loading) {
                edit(stored -> stored.setNotes(StringUtils.defaultIfBlank(e.getValue(), null)));
            }
        });

        proposeButton = new Button(loc.getValue(J_L.LABEL_PROPOSE_DESCRIPTION), Solid.MAGIC.create(), e -> propose());
        proposeButton.setThemeName("primary");
        proposalStatus = new Span();
        proposalStatus.addClassName(CssStyles.HINT_TEXT);

        proposedDescription = new TextArea(loc.getValue(J_L.LABEL_PROPOSED_DESCRIPTION));
        proposedDescription.setWidthFull();
        ResizableTextArea.install(loc, proposedDescription, PromptAreas.TEMPLATE_HEIGHT);
        Span togglesTitle = new Span(loc.getValue(J_L.LABEL_PROPOSED_TOGGLE_NAMES));
        proposedToggles = new VerticalLayout();
        proposedToggles.setPadding(false);
        proposedToggles.setSpacing(false);
        Span proposedPartsTitle = new Span(loc.getValue(J_L.LABEL_DESCRIBED_PARTS));
        proposedParts = new VerticalLayout();
        proposedParts.setPadding(false);
        Button useButton = new Button(loc.getValue(J_L.LABEL_USE_PROPOSAL), e -> useProposal());
        useButton.setThemeName("primary");
        Button discardButton = new Button(loc.getValue(J_L.LABEL_DISCARD_PROPOSAL), e -> discardProposal());
        discardButton.setThemeName("tertiary");
        proposalPanel = new VerticalLayout(proposedDescription, togglesTitle, proposedToggles, proposedPartsTitle, proposedParts,
                new HorizontalLayout(useButton, discardButton));
        proposalPanel.setPadding(false);

        layout = new VerticalLayout(title, hint, describedInfo, appearance, partsTitle, partRows, snapshotButton, snapshotInfo,
                snapshotThumbnails, referencesTitle, referenceUpload, referenceThumbnails, notes, proposeButton, proposalStatus,
                proposalPanel);
        layout.setPadding(false);
        return layout;
    }

    /** Changes the stored model (the latest, so the dialog's own fields are kept). */
    private void edit(java.util.function.Consumer<Model> change) {
        try {
            Model stored = modelService.find(model.getId());
            change.accept(stored);
            model = modelService.save(stored);
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** Shows the model as stored: hidden until it has a VRM. */
    public void show() {
        try {
            Model current = currentModel.get();
            boolean shown = current != null && current.getId() != null && current.renderable();
            layout.setVisible(shown);
            if (!shown) {
                return;
            }
            model = modelService.find(current.getId());
            ModelCapabilities capabilities = ModelCapabilities.fromJson(model.getCapabilities());
            String hash = modelService.bodyHash(model);
            loading = true;
            try {
                appearance.setValue(StringUtils.defaultString(model.getAppearance()));
                notes.setValue(StringUtils.defaultString(model.getNotes()));
            } finally {
                loading = false;
            }
            describedInfo.setVisible(model.getDescribedHash() != null);
            describedInfo.setText(loc.getValue(Objects.equals(hash, model.getDescribedHash()) ? J_L.MSG_DESCRIBED_CURRENT : J_L.MSG_DESCRIBED_OUTDATED));
            showParts(capabilities);

            DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
            long closeUps = model.getSnapshots().keySet().stream().filter(shot -> shot.startsWith(DescribedPart.SHOT)).count();
            snapshotInfo.setText(model.getSnapshots().isEmpty() || model.getSnapshotsAt() == null ? loc.getValue(J_L.MSG_NO_SNAPSHOTS)
                    : String.format(loc.getValue(J_L.MSG_SNAPSHOTS_TAKEN), format.format(model.getSnapshotsAt()), closeUps)
                    + (Objects.equals(hash, model.getSnapshotsHash()) ? "" : " " + loc.getValue(J_L.MSG_SNAPSHOTS_OUTDATED)));
            List<String> snapshots = List.copyOf(model.getSnapshots().values());
            if (!snapshots.equals(shownSnapshots)) {
                shownSnapshots = snapshots;
                snapshotThumbnails.removeAll();
                for (Map.Entry<String, String> shot : model.getSnapshots().entrySet()) {
                    thumbnail(snapshotThumbnails, shot.getValue(), shot.getKey(), null);
                }
            }
            List<String> references = List.copyOf(model.getReferences().keySet());
            if (!references.equals(shownReferences)) {
                shownReferences = references;
                referenceThumbnails.removeAll();
                for (Map.Entry<String, String> reference : model.getReferences().entrySet()) {
                    thumbnail(referenceThumbnails, reference.getKey(), reference.getValue(), () -> {
                        model = modelService.removeReference(model, reference.getKey());
                        show();
                    });
                }
            }
            showProposal(capabilities);
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The sentences of the parts described apart, a text area per state, editable. */
    private void showParts(ModelCapabilities capabilities) {
        partRows.removeAll();
        for (DescribedPart part : DescribedPart.of(model, capabilities)) {
            if (!part.group()) {
                partRows.add(wordsRow(part));
            }
            for (DescribedPart.State state : part.states()) {
                TextArea area = partArea(part.name() + " · " + stateName(part, state)
                        + (part.isDefault(state) ? " · " + loc.getValue(J_L.LABEL_DEFAULT_STATE) : ""));
                area.setValue(StringUtils.defaultString(part.sentence(model, state.id())));
                area.addValueChangeListener(e -> edit(stored -> part.setSentence(stored, state.id(), e.getValue())));
                partRows.add(area);
            }
        }
        partRows.setVisible(partRows.getComponentCount() > 0);
    }

    /** The words she is told for the shown and the hidden state of a toggle ("worn", "taken off"), two small fields. */
    private HorizontalLayout wordsRow(DescribedPart part) {
        HorizontalLayout row = new HorizontalLayout();
        row.setWidthFull();
        for (String stateId : new String[]{DescribedPart.SHOWN, DescribedPart.HIDDEN}) {
            boolean shown = DescribedPart.SHOWN.equals(stateId);
            TextField field = new TextField(part.name() + " · " + loc.getValue(shown ? J_L.LABEL_PART_SHOWN_WORDS : J_L.LABEL_PART_HIDDEN_WORDS));
            field.setPlaceholder(shown ? DescribedPart.SHOWN : DescribedPart.HIDDEN);
            field.setHelperText(loc.getValue(J_L.MSG_PART_WORDS_HINT));
            field.setValue(part.words(model, stateId));
            field.setValueChangeMode(ValueChangeMode.LAZY);
            field.setWidthFull();
            field.addValueChangeListener(e -> edit(stored -> part.setWords(stored, stateId, e.getValue())));
            row.add(field);
        }
        return row;
    }

    /** What a state is called here: the user's words or "shown" / "hidden" (localized) for a toggle, the user's name for a group's. */
    private String stateName(DescribedPart part, DescribedPart.State state) {
        if (part.group() || !state.name().equals(state.id())) {
            return state.name();
        }
        return loc.getValue(DescribedPart.SHOWN.equals(state.id()) ? J_L.LABEL_PART_SHOWN : J_L.LABEL_PART_HIDDEN);
    }

    /** A text area for a sentence of a part: small, growing to its text on demand. */
    private TextArea partArea(String label) {
        TextArea area = new TextArea(label);
        area.setWidthFull();
        ResizableTextArea.install(loc, area, PART_HEIGHT);
        return area;
    }

    private String name(ModelCapabilities.Toggle toggle) {
        return model.toggleName(toggle);
    }

    /** A thumbnail of an image of the model, with a button that removes it when it can be removed. */
    private void thumbnail(Div into, String resourceId, String name, Action remove) throws Exception {
        File file = modelService.imageFile(model, resourceId);
        if (file == null) {
            return;
        }
        // the file's own name in the URL: a shot's name may hold a toggle id ("part:key:Body/Hide Halo:shown")
        String label = label(name);
        Image image = new Image(DownloadHandler.forFile(file, file.getName()).inline(), label);
        image.setTitle(label);
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

    /** What a shot shows: "full front", or the part's name and its state for a close-up. */
    private String label(String shot) {
        if (!shot.startsWith(DescribedPart.SHOT)) {
            return shot.replace('-', ' ');
        }
        for (DescribedPart part : DescribedPart.of(model, ModelCapabilities.fromJson(model.getCapabilities()))) {
            for (DescribedPart.State state : part.states()) {
                if (part.shot(state.id()).equals(shot)) {
                    return part.name() + " · " + stateName(part, state);
                }
            }
        }
        return shot.substring(DescribedPart.SHOT.length());
    }

    /** The status of the request, and the proposal to review when there is one. */
    private void showProposal(ModelCapabilities capabilities) throws Exception {
        boolean running = appearanceService.isRunning(model);
        AI ai = appearanceService.model();
        proposeButton.setEnabled(!running && ai != null && !(model.getSnapshots().isEmpty() && model.getReferences().isEmpty()));
        AppearanceProposal proposal = running ? null : AppearanceProposal.fromJson(model.getProposal());
        if (running) {
            proposalStatus.setText(String.format(loc.getValue(J_L.MSG_PROPOSAL_RUNNING), ai == null ? "" : ai.getName(),
                    StringUtils.defaultString(model.getProposalProgress())));
        } else if (model.getProposalStatus() == ProposalStatus.FAILED) {
            proposalStatus.setText(String.format(loc.getValue(J_L.MSG_PROPOSAL_FAILED), model.getProposalError()));
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
        partChoices.clear();
        partAreas.clear();
        proposedToggles.removeAll();
        proposedParts.removeAll();
        if (proposal == null) {
            return;
        }
        proposedDescription.setValue(StringUtils.defaultString(proposal.description()));
        for (ModelCapabilities.Toggle toggle : capabilities == null ? List.<ModelCapabilities.Toggle>of() : capabilities.toggles()) {
            String proposed = proposal.toggles().get(toggle.id());
            String current = name(toggle);
            if (proposed != null && !proposed.equals(current)) {
                Checkbox choice = new Checkbox(current + " → " + proposed, true);
                choice.setTooltipText(toggle.key() == null ? toggle.mesh() : toggle.mesh() + " / " + toggle.key());
                toggleChoices.put(toggle.id(), choice);
                proposedNames.put(toggle.id(), proposed);
                proposedToggles.add(choice);
            }
        }
        for (DescribedPart part : DescribedPart.of(model, capabilities)) {
            Map<String, String> sentences = proposal.parts().get(part.id());
            if (sentences == null) {
                continue;
            }
            String proposed = part.group() ? null : proposal.toggles().get(part.id());
            Checkbox use = new Checkbox(StringUtils.defaultIfBlank(proposed, part.name()), true);
            proposedParts.add(use);
            List<TextArea> areas = new ArrayList<>();
            for (DescribedPart.State state : part.states()) {
                TextArea area = partArea(stateName(part, state));
                area.setValue(StringUtils.defaultString(sentences.get(state.id())));
                areas.add(area);
                proposedParts.add(area);
            }
            partChoices.put(part.id(), use);
            partAreas.put(part.id(), areas.toArray(TextArea[]::new));
        }
    }

    /** The parts the close-ups are of, in the order given to the viewer. */
    private List<ModelViewer.Part> parts() {
        ModelCapabilities capabilities = ModelCapabilities.fromJson(model.getCapabilities());
        List<ModelViewer.Part> parts = new ArrayList<>();
        pendingParts.clear();
        for (DescribedPart part : DescribedPart.of(model, capabilities)) {
            List<ModelViewer.Toggle> toggles = part.toggles().stream()
                    .map(toggle -> new ModelViewer.Toggle(toggle.mesh(), toggle.key(), toggle.hides(), null)).toList();
            List<List<Boolean>> states = part.states().stream()
                    .map(state -> part.toggles().stream().map(toggle -> state.shown().getOrDefault(toggle.id(), true)).toList()).toList();
            pendingParts.put(parts.size(), part);
            parts.add(new ModelViewer.Part(parts.size(), toggles, states));
        }
        return parts;
    }

    /** The bare state for the viewer: the default with the layers of her look (the auto description ones) off. */
    public static List<ModelViewer.Toggle> bareToggles(Model model) {
        ModelCapabilities capabilities = ModelCapabilities.fromJson(model.getCapabilities());
        return toggles(model, capabilities == null ? Map.of() : model.bareShown(capabilities));
    }

    /** Every toggle in its default state, for the viewer. */
    public static List<ModelViewer.Toggle> defaultToggles(Model model) {
        return toggles(model, Map.of());
    }

    /**
     * The toggles of the model for the viewer in a state.
     *
     * @param shown the toggle's id → whether it is shown; a toggle not in it is as by default
     */
    public static List<ModelViewer.Toggle> toggles(Model model, Map<String, Boolean> shown) {
        ModelCapabilities capabilities = ModelCapabilities.fromJson(model.getCapabilities());
        ToggleTree tree = ToggleTree.of(model, capabilities);
        return capabilities == null ? List.of() : capabilities.toggles().stream()
                .map(toggle -> new ModelViewer.Toggle(toggle.mesh(), toggle.key(), toggle.hides(),
                        shown.getOrDefault(toggle.id(), model.shownByDefault(toggle)), tree.parent(toggle.id()))).toList();
    }

    private void takeSnapshots() {
        beforeSnapshots.run();
        pendingShots.clear();
        snapshotButton.setEnabled(false);
        viewer.takeSnapshots(UploadHandler.inMemory((metadata, bytes) ->
                pendingShots.put(StringUtils.removeEndIgnoreCase(metadata.fileName(), ".jpg"), bytes)), defaultToggles(model), bareToggles(model), parts());
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
            for (Map.Entry<Integer, DescribedPart> part : new java.util.TreeMap<>(pendingParts).entrySet()) {
                List<DescribedPart.State> states = part.getValue().states();
                for (int i = 0; i < states.size(); i++) {
                    byte[] image = pendingShots.get("part-" + part.getKey() + "-" + i);
                    if (image != null) {
                        shots.put(part.getValue().shot(states.get(i).id()), image);
                    }
                }
            }
            pendingShots.clear();
            if (!shots.isEmpty()) {
                model = modelService.storeSnapshots(model, shots);
            }
            show();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** For tests: the viewer sent the snapshots (the close-ups named {@code part-<index>-<state index>}). */
    public void snapshotsTakenForTest(Map<String, byte[]> shots) {
        parts();
        pendingShots.putAll(shots);
        snapshotsTaken();
    }

    private void referenceUploaded(File file, String fileName) {
        try {
            model = modelService.addReference(model, file, fileName);
            show();
        } catch (IllegalArgumentException e) {
            Notification.warning(e.getMessage());
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** For tests: an image was uploaded. */
    public void referenceUploadedForTest(File file, String fileName) {
        referenceUploaded(file, fileName);
    }

    private void propose() {
        try {
            appearanceService.propose(model, null);
            listen();
            show();
        } catch (IllegalStateException e) {
            Notification.warning(e.getMessage());
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** Shows the progress of the running request and its end. */
    public void listen() {
        if (model == null || !appearanceService.isRunning(model)) {
            return;
        }
        UI ui = UI.getCurrent();
        stopListening.run();
        stopListening = appearanceService.listen(model, new AppearanceService.Listener() {
            @Override
            public void progress(Model changed) {
                ui.access(AppearancePanel.this::show);
            }

            @Override
            public void finished(Model changed) {
                ui.access(AppearancePanel.this::show);
            }
        });
    }

    /** Stops listening (the dialog closes). */
    public void stop() {
        stopListening.run();
    }

    private void useProposal() {
        try {
            Map<String, String> names = new LinkedHashMap<>();
            for (Map.Entry<String, Checkbox> choice : toggleChoices.entrySet()) {
                if (choice.getValue().getValue()) {
                    names.put(choice.getKey(), proposedNames.get(choice.getKey()));
                }
            }
            Map<String, Map<String, String>> parts = new LinkedHashMap<>();
            Map<String, DescribedPart> described = new LinkedHashMap<>();
            DescribedPart.of(model, ModelCapabilities.fromJson(model.getCapabilities())).forEach(part -> described.put(part.id(), part));
            for (Map.Entry<String, Checkbox> choice : partChoices.entrySet()) {
                DescribedPart part = described.get(choice.getKey());
                if (choice.getValue().getValue() && part != null) {
                    TextArea[] areas = partAreas.get(choice.getKey());
                    Map<String, String> sentences = new LinkedHashMap<>();
                    for (int i = 0; i < areas.length && i < part.states().size(); i++) {
                        sentences.put(part.states().get(i).id(), areas[i].getValue());
                    }
                    parts.put(choice.getKey(), sentences);
                }
            }
            appearanceService.apply(model, proposedDescription.getValue(), names, parts);
            modelChanged.run();
            show();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void discardProposal() {
        try {
            model = appearanceService.discard(model);
            show();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The proposal's description, for tests. */
    public TextArea getProposedDescription() {
        return proposedDescription;
    }

    /** The choices of the proposed toggle names (by the toggle's id), for tests. */
    public Map<String, Checkbox> getToggleChoices() {
        return toggleChoices;
    }

    /** The proposed sentences of the parts (by the part's id: one per state, in order; shown, hidden for a toggle), for tests. */
    public Map<String, TextArea[]> getPartAreas() {
        return partAreas;
    }

    /** Uses the proposal as reviewed, for tests. */
    public void useProposalForTest() {
        useProposal();
    }

    /** The description of what never changes, for tests. */
    public TextArea getAppearance() {
        return appearance;
    }

    /** The notes, for tests. */
    public TextArea getNotes() {
        return notes;
    }
}
