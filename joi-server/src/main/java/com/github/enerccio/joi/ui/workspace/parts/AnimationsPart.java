package com.github.enerccio.joi.ui.workspace.parts;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.animations.VrmaFiles;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.service.AnimationConversionService;
import com.github.enerccio.joi.domain.service.AnimationPackService;
import com.github.enerccio.joi.domain.service.AnimationService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.dialogs.AnimationDialog;
import com.github.enerccio.joi.ui.workspace.WorkspacePart;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.UIConstants;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.LLMInteract;
import com.github.enerccio.marginalia.ui.LLMInteraction;
import com.github.enerccio.marginalia.ui.dialogs.ConfirmDialog;
import com.github.enerccio.marginalia.ui.widgets.Notification;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.server.streams.UploadHandler;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.io.File;
import java.nio.file.Files;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The user's library of animation clips ({@link Animation}, see {@code DESIGN.md} → Bodies, animations): the upload
 * (a VRMA is added as it is; an FBX or a BVH is converted with Blender, a clip per take, in the background) and a table
 * with the name, the length, the tags, where it came from, the packs it is in and when it was added, Open and Delete
 * (it leaves its packs). Open shows the {@link AnimationDialog}. Packs of clips are the Animation packs tab.
 */
@Configurable
public class AnimationsPart implements WorkspacePart {

    @Autowired
    private Localization loc;

    @Autowired
    private AnimationService animationService;

    @Autowired
    private AnimationPackService packService;

    @Autowired
    private AnimationConversionService conversionService;

    @Autowired
    private ModelService modelService;

    private Grid<Animation> grid;
    private Upload upload;
    private Button uploadButton;
    private ProgressBar converting;
    private Span convertingInfo;
    /** The files being converted now. */
    private final List<String> conversions = new ArrayList<>();

    @Override
    public Component create() throws Exception {
        H4 title = new H4(loc.getValue(J_L.LABEL_ANIMATIONS));
        File folder = modelService.uploadFolder();
        UploadHandler handler = UploadHandler.toFile((metadata, file) -> uploaded(file, metadata.fileName()),
                metadata -> ModelFiles.uploadFile(folder, metadata.fileName()));
        upload = new Upload(handler);
        upload.setAcceptedFileTypes("." + VrmaFiles.EXTENSION, ".fbx", ".bvh");
        uploadButton = new Button(loc.getValue(J_L.LABEL_UPLOAD_ANIMATIONS), Solid.UPLOAD.create());
        uploadButton.setThemeName("primary");
        upload.setUploadButton(uploadButton);
        HorizontalLayout header = new HorizontalLayout(title, upload);
        header.setWidthFull();
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);
        Span hint = new Span(loc.getValue(J_L.MSG_ANIMATIONS_HINT));
        hint.addClassName(CssStyles.HINT_TEXT);
        converting = new ProgressBar();
        converting.setIndeterminate(true);
        converting.setVisible(false);
        convertingInfo = new Span();
        convertingInfo.addClassName(CssStyles.HINT_TEXT);
        convertingInfo.setVisible(false);

        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        grid = new Grid<>(Animation.class, false);
        grid.setSizeFull();
        grid.addColumn(Animation::getName).setHeader(loc.getValue(L.LABEL_NAME)).setFlexGrow(2);
        grid.addColumn(clip -> AnimationDialog.length(clip.getDuration()))
                .setHeader(loc.getValue(J_L.LABEL_ANIMATION_LENGTH)).setFlexGrow(0).setWidth("90px");
        grid.addColumn(clip -> String.join(", ", clip.getTags()))
                .setHeader(loc.getValue(J_L.LABEL_ANIMATION_TAGS)).setFlexGrow(1);
        grid.addColumn(clip -> StringUtils.defaultString(clip.getSource()))
                .setHeader(loc.getValue(J_L.LABEL_ANIMATION_SOURCE)).setFlexGrow(1);
        grid.addColumn(this::packNames).setHeader(loc.getValue(J_L.LABEL_IN_PACKS)).setFlexGrow(1);
        grid.addColumn(clip -> clip.getCreation() == null ? "" : format.format(clip.getCreation()))
                .setHeader(loc.getValue(L.LABEL_CREATED)).setFlexGrow(0).setWidth(UIConstants.DATE_COLUMN_SIZE);
        grid.addComponentColumn(this::actions).setHeader("").setFlexGrow(0).setWidth(UIConstants.TOOL_COLUMN_SIZE_HUGE);
        grid.addItemDoubleClickListener(e -> open(e.getItem()));

        VerticalLayout layout = new VerticalLayout(header, hint, convertingInfo, converting, grid);
        layout.setSizeFull();
        layout.setPadding(false);
        layout.setFlexGrow(1, grid);
        return layout;
    }

    private String packNames(Animation clip) {
        try {
            List<String> names = new ArrayList<>();
            for (AnimationPack pack : packService.packsWith(clip)) {
                names.add(StringUtils.defaultIfBlank(pack.getName(), loc.getValue(J_L.LABEL_NEW_ANIMATION_PACK)));
            }
            return String.join(", ", names);
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
            return "";
        }
    }

    private Component actions(Animation clip) {
        Button openButton = new Button(Solid.FOLDER_OPEN.create(), e -> open(clip));
        openButton.setTooltipText(loc.getValue(L.LABEL_OPEN));
        Button deleteButton = new Button(Solid.TRASH.create(), e ->
                ConfirmDialog.show(loc.getValue(J_L.MSG_CONFIRM_DELETE_CLIP), () -> {
                    try {
                        animationService.delete(clip, false);
                        refresh();
                    } catch (Exception ex) {
                        UIUtils.internalServerError(loc, ex);
                    }
                }));
        deleteButton.setThemeName("error tertiary");
        deleteButton.setTooltipText(loc.getValue(L.LABEL_DELETE));
        HorizontalLayout actions = new HorizontalLayout(openButton, deleteButton);
        actions.setSpacing(true);
        return actions;
    }

    private void open(Animation clip) {
        try {
            AnimationDialog dialog = new AnimationDialog(animationService.find(clip.getId()));
            dialog.setOnClose(this::refreshQuietly);
            dialog.create();
            dialog.open();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** A file was uploaded: a VRMA joins the library, an FBX or a BVH is converted first. */
    private void uploaded(File file, String fileName) {
        upload.clearFileList();
        if (AnimationConversionService.isConvertible(fileName)) {
            convert(file, fileName);
            return;
        }
        try {
            if (!VrmaFiles.isVrma(fileName)) {
                Files.deleteIfExists(file.toPath());
                Notification.error(loc.getValue(J_L.MSG_NOT_A_VRMA));
                return;
            }
            Animation added = animationService.add(file, fileName);
            refresh();
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
        }
    }

    private void convert(File file, String fileName) {
        try {
            UI ui = UI.getCurrent();
            conversions.add(fileName);
            showConversions();
            conversionService.convert(file, fileName, (added, error) -> ui.access(() -> converted(fileName, added, error)));
        } catch (IllegalArgumentException e) {
            conversions.remove(fileName);
            showConversions();
            Notification.error(loc.getValue(J_L.MSG_NOT_A_VRMA));
        } catch (Exception e) {
            conversions.remove(fileName);
            showConversions();
            UIUtils.internalServerError(loc, e);
        }
    }

    private void converted(String fileName, List<Animation> added, String error) {
        conversions.remove(fileName);
        showConversions();
        if (error != null) {
            Notification.error(String.format(loc.getValue(J_L.MSG_ANIMATION_CONVERSION_FAILED), fileName, error));
            return;
        }
        refreshQuietly();
        Notification.success(String.format(loc.getValue(J_L.MSG_ANIMATION_CONVERTED), fileName, added.size()));
    }

    private void showConversions() {
        boolean any = !conversions.isEmpty();
        converting.setVisible(any);
        convertingInfo.setVisible(any);
        convertingInfo.setText(any ? String.format(loc.getValue(J_L.MSG_CONVERTING_ANIMATION), String.join(", ", conversions)) : "");
    }

    @Override
    public void refresh() throws Exception {
        if (grid != null) {
            grid.setItems(animationService.findAllByName());
        }
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The grid of the clips, for tests. */
    Grid<Animation> getGrid() {
        return grid;
    }

    /** What an upload does once the file is written, for tests. */
    void uploadedForTest(File file, String fileName) {
        uploaded(file, fileName);
    }

    /** The files being converted now, for tests. */
    List<String> getConversions() {
        return conversions;
    }

    @Override
    public Map<String, LLMInteraction> getInteraction() {
        return LLMInteract.dialogButtons(uploadButton);
    }
}
