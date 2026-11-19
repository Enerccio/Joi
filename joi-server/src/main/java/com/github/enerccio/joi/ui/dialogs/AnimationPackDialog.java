package com.github.enerccio.joi.ui.dialogs;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.animations.VrmaFiles;
import com.github.enerccio.joi.domain.bodies.BodyState;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.service.AnimationPackService;
import com.github.enerccio.joi.domain.service.AnimationService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.joi.ui.bodies.AppearancePanel;
import com.github.enerccio.joi.ui.bodies.ModelViewer;
import com.github.enerccio.joi.ui.widgets.EditorSplit;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.dialogs.ConfirmDialog;
import com.github.enerccio.marginalia.ui.widgets.Notification;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.server.streams.UploadHandler;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One animation pack ({@link AnimationPack}), full screen: on the left its name and description, the upload of clips
 * (VRMA) and the table of its clips; the clip chosen in the table is edited under it (its name, description, tags,
 * whether it loops, its speed) and plays on the right, in the {@link ModelViewer 3D preview} of the body the user
 * previews the pack on (any body of their assistants, in the state of its parts; the pack remembers it). Changes are
 * stored at once; a new pack is stored once it gets a name or a clip.
 */
@Configurable
public class AnimationPackDialog extends Dialog {

    @Autowired
    private Localization loc;

    @Autowired
    private AnimationPackService packService;

    @Autowired
    private AnimationService animationService;

    @Autowired
    private ModelService modelService;

    @Autowired
    private JoiService joiService;

    private AnimationPack pack;
    private Runnable onClose = () -> {
    };
    /** The bodies the clips can be previewed on, by id. */
    private final Map<Long, Body> bodies = new LinkedHashMap<>();
    private final Map<Long, String> bodyNames = new LinkedHashMap<>();
    private TextField nameField;
    private TextArea descriptionField;
    private Upload upload;
    private Grid<Animation> clips;
    private VerticalLayout clipEditor;
    private Span noClip;
    private TextField clipName;
    private TextArea clipDescription;
    private TextField clipTags;
    private Checkbox clipLoop;
    private NumberField clipSpeed;
    private Span clipInfo;
    private Animation clip;
    private Select<Long> bodySelect;
    private Button pauseButton;
    private Button stopButton;
    private boolean paused;
    private ModelViewer viewer;
    private Span previewInfo;
    /** While the editor is filled from a clip, its fields don't store. */
    private boolean filling;

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
        for (Body body : packService.previewBodies()) {
            bodies.put(body.getId(), body);
            Joi joi = joiService.find(body.getJoi().getId());
            bodyNames.put(body.getId(), joiService.name(joi) + ": " + StringUtils.defaultIfBlank(body.getName(), "?"));
        }
        updateTitle();

        nameField = new TextField(loc.getValue(L.LABEL_NAME));
        nameField.setWidthFull();
        nameField.setValue(StringUtils.defaultString(pack.getName()));
        nameField.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                storePack();
            }
        });

        descriptionField = new TextArea(loc.getValue(L.LABEL_DESCRIPTION));
        descriptionField.setWidthFull();
        descriptionField.setValue(StringUtils.defaultString(pack.getDescription()));
        descriptionField.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                storePack();
            }
        });

        File folder = modelService.uploadFolder();
        UploadHandler handler = UploadHandler.toFile((metadata, file) -> uploaded(file, metadata.fileName()),
                metadata -> ModelFiles.uploadFile(folder, metadata.fileName()));
        upload = new Upload(handler);
        upload.setAcceptedFileTypes("." + VrmaFiles.EXTENSION);
        upload.setWidthFull();
        upload.setUploadButton(new Button(loc.getValue(J_L.LABEL_UPLOAD_ANIMATIONS), Solid.UPLOAD.create()));
        Span hint = new Span(loc.getValue(J_L.MSG_ANIMATION_PACK_HINT));
        hint.addClassName(CssStyles.HINT_TEXT);

        H4 clipsTitle = new H4(loc.getValue(J_L.LABEL_CLIPS));
        clips = new Grid<>(Animation.class, false);
        clips.setWidthFull();
        clips.setAllRowsVisible(true);
        clips.addColumn(Animation::getName).setHeader(loc.getValue(L.LABEL_NAME)).setFlexGrow(1);
        clips.addColumn(animation -> length(animation.getDuration()))
                .setHeader(loc.getValue(J_L.LABEL_ANIMATION_LENGTH)).setFlexGrow(0).setWidth("90px");
        clips.addColumn(animation -> String.join(", ", animation.getTags()))
                .setHeader(loc.getValue(J_L.LABEL_ANIMATION_TAGS)).setFlexGrow(1);
        clips.addComponentColumn(this::clipActions).setHeader("").setFlexGrow(0).setWidth("70px");
        clips.addSelectionListener(e -> select(e.getFirstSelectedItem().orElse(null)));

        noClip = new Span(loc.getValue(J_L.MSG_NO_CLIP_SELECTED));
        noClip.addClassName(CssStyles.HINT_TEXT);
        clipEditor = createClipEditor();

        VerticalLayout sidebar = new VerticalLayout(nameField, descriptionField, upload, hint, clipsTitle, clips, noClip, clipEditor);
        sidebar.setPadding(false);
        sidebar.addClassName(JoiCssStyles.MODEL_SIDEBAR);

        viewer = new ModelViewer();
        viewer.setSizeFull();
        previewInfo = new Span();
        previewInfo.addClassName(CssStyles.HINT_TEXT);
        viewer.addInfoListener(e -> showBodyState());
        viewer.addErrorListener(e -> previewInfo.setText(e.getMessage()));
        viewer.addAnimationStartedListener(e -> previewInfo.setText(""));
        viewer.addAnimationErrorListener(e -> previewInfo.setText(e.getMessage()));

        bodySelect = new Select<>();
        bodySelect.setLabel(loc.getValue(J_L.LABEL_PREVIEW_BODY));
        bodySelect.setItems(new ArrayList<>(bodies.keySet()));
        bodySelect.setItemLabelGenerator(id -> id == null ? "" : bodyNames.getOrDefault(id, ""));
        bodySelect.setEmptySelectionAllowed(false);
        Body chosen = packService.previewBody(pack);
        Long initial = chosen != null ? chosen.getId() : bodies.keySet().stream().findFirst().orElse(null);
        bodySelect.setValue(initial);
        bodySelect.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                storePack();
            }
            showBody();
        });

        pauseButton = new Button(Solid.PAUSE.create(), e -> setPaused(!paused));
        pauseButton.setTooltipText(loc.getValue(J_L.LABEL_PLAY));
        stopButton = new Button(Solid.STOP.create(), e -> clips.deselectAll());
        stopButton.setTooltipText(loc.getValue(L.LABEL_STOP));
        HorizontalLayout previewHeader = new HorizontalLayout(bodySelect, pauseButton, stopButton, previewInfo);
        previewHeader.setAlignItems(FlexComponent.Alignment.BASELINE);
        previewHeader.setWrap(true);
        VerticalLayout previewColumn = new VerticalLayout(previewHeader, viewer);
        previewColumn.setPadding(false);
        previewColumn.setSpacing(false);
        previewColumn.addClassName(JoiCssStyles.MODEL_VIEWER);
        previewColumn.setFlexGrow(1, viewer);
        if (bodies.isEmpty()) {
            previewInfo.setText(loc.getValue(J_L.MSG_NO_PREVIEW_BODIES));
        }

        add(EditorSplit.create(sidebar, previewColumn, "joi.animation-pack-dialog.split"));

        Button exitButton = new Button(loc.getValue(L.LABEL_EXIT), e -> close());
        exitButton.setThemeName("primary");
        getFooter().add(exitButton);

        addOpenedChangeListener(e -> {
            if (!e.isOpened()) {
                onClose.run();
            }
        });
        refreshClips();
        select(null);
        showBody();
        nameField.focus();
    }

    private VerticalLayout createClipEditor() {
        H4 title = new H4(loc.getValue(J_L.LABEL_CLIP));
        clipName = new TextField(loc.getValue(L.LABEL_NAME));
        clipName.setWidthFull();
        clipName.addValueChangeListener(e -> storeClip());
        clipDescription = new TextArea(loc.getValue(L.LABEL_DESCRIPTION));
        clipDescription.setWidthFull();
        clipDescription.addValueChangeListener(e -> storeClip());
        clipTags = new TextField(loc.getValue(J_L.LABEL_ANIMATION_TAGS));
        clipTags.setWidthFull();
        clipTags.setHelperText(loc.getValue(J_L.MSG_ANIMATION_TAGS_HINT));
        clipTags.addValueChangeListener(e -> storeClip());
        clipLoop = new Checkbox(loc.getValue(J_L.LABEL_ANIMATION_LOOP));
        clipLoop.addValueChangeListener(e -> {
            if (!filling) {
                viewer.setAnimationLoop(Boolean.TRUE.equals(e.getValue()));
            }
            storeClip();
        });
        clipSpeed = new NumberField(loc.getValue(J_L.LABEL_SPEED));
        clipSpeed.setMin(0.1);
        clipSpeed.setMax(4);
        clipSpeed.setStep(0.1);
        clipSpeed.setStepButtonsVisible(true);
        clipSpeed.addValueChangeListener(e -> {
            if (!filling) {
                viewer.setAnimationSpeed(speed());
            }
            storeClip();
        });
        clipInfo = new Span();
        clipInfo.addClassName(CssStyles.HINT_TEXT);
        HorizontalLayout playback = new HorizontalLayout(clipLoop, clipSpeed);
        playback.setAlignItems(FlexComponent.Alignment.BASELINE);
        playback.addClassName(JoiCssStyles.ANIMATION_PLAYBACK);
        VerticalLayout editor = new VerticalLayout(title, clipName, clipDescription, clipTags, playback, clipInfo);
        editor.setPadding(false);
        return editor;
    }

    private Component clipActions(Animation animation) {
        Button delete = new Button(Solid.TRASH.create(), e ->
                ConfirmDialog.show(loc.getValue(L.MSG_CONFIRM_DELETE), () -> {
                    try {
                        animationService.delete(animation, false);
                        if (clip != null && clip.getId().equals(animation.getId())) {
                            clips.deselectAll();
                        }
                        refreshClips();
                    } catch (Exception ex) {
                        UIUtils.internalServerError(loc, ex);
                    }
                }));
        delete.setThemeName("error tertiary");
        delete.setTooltipText(loc.getValue(L.LABEL_DELETE));
        return delete;
    }

    /** A clip was uploaded: added to the pack (stored first when new). */
    private void uploaded(File file, String fileName) {
        try {
            if (!VrmaFiles.isVrma(fileName)) {
                Files.deleteIfExists(file.toPath());
                Notification.error(loc.getValue(J_L.MSG_NOT_A_VRMA));
                return;
            }
            if (pack.getId() == null) {
                storePack(true);
            }
            Animation added = animationService.add(pack, file, fileName);
            refreshClips();
            clips.select(added);
            Notification.success(String.format(loc.getValue(J_L.MSG_ANIMATION_ADDED), added.getName()));
        } catch (IllegalArgumentException e) {
            Notification.error(loc.getValue(J_L.MSG_NOT_A_VRMA));
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

    /** The clip chosen in the table (null for none): into the editor, and played on the preview. */
    private void select(Animation animation) {
        clip = animation;
        clipEditor.setVisible(animation != null);
        noClip.setVisible(animation == null);
        if (animation == null) {
            viewer.stopAnimation();
            setPaused(false);
            return;
        }
        filling = true;
        try {
            clipName.setValue(StringUtils.defaultString(animation.getName()));
            clipDescription.setValue(StringUtils.defaultString(animation.getDescription()));
            clipTags.setValue(String.join(", ", animation.getTags()));
            clipLoop.setValue(animation.loops());
            clipSpeed.setValue(animation.speedOrDefault());
        } finally {
            filling = false;
        }
        clipInfo.setText(String.format(loc.getValue(J_L.MSG_ANIMATION_INFO), length(animation.getDuration()),
                animation.getBones().size(), Boolean.TRUE.equals(animation.getExpressions()) ? loc.getValue(J_L.MSG_ANIMATION_EXPRESSIONS) : ""));
        play();
    }

    private void play() {
        try {
            File file = clip == null ? null : animationService.file(clip);
            if (file == null) {
                viewer.stopAnimation();
                return;
            }
            setPaused(false);
            viewer.playAnimation(file, Boolean.TRUE.equals(clipLoop.getValue()), speed());
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void setPaused(boolean paused) {
        this.paused = paused;
        if (viewer != null) {
            viewer.setAnimationPaused(paused);
        }
        pauseButton.setIcon(paused ? Solid.PLAY.create() : Solid.PAUSE.create());
        pauseButton.setTooltipText(loc.getValue(paused ? J_L.LABEL_PLAY : J_L.LABEL_PAUSE));
    }

    private double speed() {
        Double value = clipSpeed.getValue();
        return value == null || value <= 0 ? 1 : value;
    }

    /** Stores the pack's name, description and preview body; a new pack only once it has a name. */
    private void storePack() {
        storePack(false);
    }

    /**
     * @param always whether to store a new pack without a name too (a clip is added to it)
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
            stored.setPreviewBodyId(bodySelect == null ? null : bodySelect.getValue());
            pack = packService.save(stored);
            updateTitle();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** Stores the clip being edited. */
    private void storeClip() {
        if (filling || clip == null) {
            return;
        }
        try {
            Animation stored = animationService.find(clip.getId());
            stored.setName(StringUtils.defaultIfBlank(StringUtils.strip(clipName.getValue()), stored.getFileName()));
            stored.setDescription(StringUtils.defaultIfBlank(clipDescription.getValue(), null));
            stored.setTags(tags(clipTags.getValue()));
            stored.setLoop(Boolean.TRUE.equals(clipLoop.getValue()));
            stored.setSpeed(speed() == 1 ? null : speed());
            clip = animationService.save(stored);
            clips.getDataProvider().refreshItem(clip);
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The tags of a comma-separated text: trimmed, lower case, each once. */
    static List<String> tags(String text) {
        List<String> tags = new ArrayList<>();
        if (text != null) {
            Arrays.stream(text.split(","))
                    .map(tag -> tag.strip().toLowerCase(Locale.ROOT))
                    .filter(tag -> !tag.isEmpty() && !tags.contains(tag))
                    .forEach(tags::add);
        }
        return tags;
    }

    private static String length(Double seconds) {
        return seconds == null ? "" : String.format(Locale.ROOT, "%.1f s", seconds);
    }

    private void refreshClips() throws Exception {
        clips.setItems(animationService.findByPack(pack));
    }

    /** Shows the body chosen for the preview; the clip playing goes on on it. */
    private void showBody() {
        try {
            Body body = bodySelect.getValue() == null ? null : bodies.get(bodySelect.getValue());
            Model model = body == null || body.getModel() == null ? null : modelService.find(body.getModel().getId());
            File file = model == null ? null : modelService.bodyFile(model);
            if (file == null) {
                viewer.clear();
                if (!bodies.isEmpty()) {
                    previewInfo.setText("");
                }
            } else {
                previewInfo.setText("");
                viewer.show(file, modelService.bodyFormat(model), null);
            }
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The preview body in the state of its parts. */
    private void showBodyState() {
        try {
            Body body = bodySelect.getValue() == null ? null : bodies.get(bodySelect.getValue());
            if (body != null && body.getModel() != null) {
                Model model = modelService.find(body.getModel().getId());
                viewer.setToggles(AppearancePanel.toggles(model, BodyState.toggles(model, body.partStateMap())));
            }
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void updateTitle() {
        setHeaderTitle(StringUtils.isBlank(pack.getName()) ? loc.getValue(J_L.LABEL_NEW_ANIMATION_PACK) : pack.getName());
    }

    /** The pack as stored last (no id while a new one is not stored), for tests. */
    public AnimationPack getPack() {
        return pack;
    }

    /** What an upload does once the file is written, for tests. */
    void uploadedForTest(File file, String fileName) {
        uploaded(file, fileName);
    }

    /** The table of clips, for tests. */
    public Grid<Animation> getClips() {
        return clips;
    }

    /** The name field, for tests. */
    public TextField getNameField() {
        return nameField;
    }

    /** The choice of the preview body, for tests. */
    public Select<Long> getBodySelect() {
        return bodySelect;
    }

    /** The name of the clip being edited, for tests. */
    public TextField getClipName() {
        return clipName;
    }

    /** The tags of the clip being edited, for tests. */
    public TextField getClipTags() {
        return clipTags;
    }

    /** Whether the clip being edited loops, for tests. */
    public Checkbox getClipLoop() {
        return clipLoop;
    }
}
