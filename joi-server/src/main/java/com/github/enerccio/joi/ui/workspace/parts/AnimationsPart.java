package com.github.enerccio.joi.ui.workspace.parts;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.animations.AnimationFolder.Kind;
import com.github.enerccio.joi.domain.animations.VrmaFiles;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.service.AnimationConversionService;
import com.github.enerccio.joi.domain.service.AnimationFolderService;
import com.github.enerccio.joi.domain.service.AnimationPackService;
import com.github.enerccio.joi.domain.service.AnimationService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.dialogs.AnimationDialog;
import com.github.enerccio.joi.ui.animations.FolderRow;
import com.github.enerccio.joi.ui.animations.LibraryFolders;
import com.github.enerccio.joi.ui.dialogs.AnimationPackDialog;
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
import com.vaadin.flow.component.icon.VaadinIcon;
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
 * with checkboxes (Delete selected), the name, the length, the tags, where it came from, the animations it is in and
 * when it was added, Open, New animation (of the clip, named after it, opened at once) and Delete (it leaves its
 * animations). Open shows the {@link AnimationDialog}. Packs of clips are the Animation packs tab. The clips are in
 * folders, as Marginalia's books ({@link LibraryFolders}): the path of the folder open above the table, its folders
 * first in the table, New folder, and Move to a folder for a clip or the ones checked; an upload goes into the folder
 * open.
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

    @Autowired
    private AnimationFolderService folderService;

    private Grid<FolderRow<Animation>> grid;
    private LibraryFolders folders;
    private Button deleteSelectedButton;
    private Button moveSelectedButton;
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
        deleteSelectedButton = new Button(loc.getValue(L.LABEL_DELETE_SELECTED), Solid.TRASH.create(), e -> deleteSelected());
        deleteSelectedButton.setThemeName("error");
        deleteSelectedButton.setEnabled(false);
        folders = new LibraryFolders(Kind.CLIPS, loc.getValue(J_L.LABEL_ANIMATIONS), this::refreshQuietly);
        moveSelectedButton = new Button(loc.getValue(J_L.LABEL_MOVE_TO_FOLDER), Solid.FOLDER_OPEN.create(), e -> moveSelected());
        moveSelectedButton.setEnabled(false);
        HorizontalLayout buttons = new HorizontalLayout(deleteSelectedButton, moveSelectedButton, folders.newFolderButton(), upload);
        buttons.setAlignItems(FlexComponent.Alignment.CENTER);
        // the buttons wrap under each other rather than run off a narrow window
        buttons.setWrap(true);
        buttons.setJustifyContentMode(FlexComponent.JustifyContentMode.END);
        HorizontalLayout header = new HorizontalLayout(title, buttons);
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
        grid = new Grid<>();
        grid.setSizeFull();
        grid.setSelectionMode(Grid.SelectionMode.MULTI);
        // folders are opened, not checked
        grid.setItemSelectableProvider(row -> !row.isFolder());
        grid.addSelectionListener(e -> {
            boolean any = !e.getAllSelectedItems().isEmpty();
            deleteSelectedButton.setEnabled(any);
            moveSelectedButton.setEnabled(any);
        });
        grid.addComponentColumn(this::nameCell).setHeader(loc.getValue(L.LABEL_NAME)).setFlexGrow(2);
        grid.addColumn(row -> row.isFolder() ? "" : AnimationDialog.length(row.item().getDuration()))
                .setHeader(loc.getValue(J_L.LABEL_ANIMATION_LENGTH)).setFlexGrow(0).setWidth("90px");
        grid.addColumn(row -> row.isFolder() ? "" : String.join(", ", row.item().getTags()))
                .setHeader(loc.getValue(J_L.LABEL_ANIMATION_TAGS)).setFlexGrow(1);
        grid.addColumn(row -> row.isFolder() ? "" : StringUtils.defaultString(row.item().getSource()))
                .setHeader(loc.getValue(J_L.LABEL_ANIMATION_SOURCE)).setFlexGrow(1);
        grid.addColumn(row -> row.isFolder() ? "" : packNames(row.item())).setHeader(loc.getValue(J_L.LABEL_IN_PACKS)).setFlexGrow(1);
        grid.addColumn(row -> row.isFolder() || row.item().getCreation() == null ? "" : format.format(row.item().getCreation()))
                .setHeader(loc.getValue(L.LABEL_CREATED)).setFlexGrow(0).setWidth(UIConstants.DATE_COLUMN_SIZE);
        grid.addComponentColumn(row -> row.isFolder() ? folders.folderTools(row.folder()) : actions(row.item()))
                .setHeader("").setFlexGrow(0).setWidth("240px");
        grid.addItemDoubleClickListener(e -> {
            if (e.getItem().isFolder()) {
                folders.open(e.getItem().folder().getUuid());
            } else {
                open(e.getItem().item());
            }
        });

        VerticalLayout layout = new VerticalLayout(header, hint, convertingInfo, converting, folders.getBreadcrumb(), grid);
        layout.setSizeFull();
        layout.setPadding(false);
        layout.setFlexGrow(1, grid);
        return layout;
    }

    private Component nameCell(FolderRow<Animation> row) {
        if (row.isFolder()) {
            return folders.folderName(row.folder());
        }
        HorizontalLayout name = new HorizontalLayout(VaadinIcon.FILM.create(), new Span(StringUtils.defaultString(row.item().getName())));
        name.setSpacing(true);
        name.setPadding(false);
        name.setAlignItems(FlexComponent.Alignment.CENTER);
        return name;
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
        Button packButton = new Button(Solid.LAYER_GROUP.create(), e -> createPack(clip));
        packButton.setTooltipText(loc.getValue(J_L.LABEL_CREATE_PACK_FROM_CLIP));
        Button moveButton = new Button(Solid.FOLDER_TREE.create(), e -> folders.moveItems(target -> moveClips(List.of(clip), target)));
        moveButton.setTooltipText(loc.getValue(J_L.LABEL_MOVE_TO_FOLDER));
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
        HorizontalLayout actions = new HorizontalLayout(openButton, packButton, moveButton, deleteButton);
        actions.setSpacing(true);
        return actions;
    }

    /** The clips checked in the table. */
    private List<Animation> selectedClips() {
        return grid.getSelectedItems().stream().filter(row -> !row.isFolder()).map(FolderRow::item).toList();
    }

    /** Moves the clips checked into the folder chosen. */
    private void moveSelected() {
        List<Animation> selected = selectedClips();
        if (!selected.isEmpty()) {
            folders.moveItems(target -> moveClips(selected, target));
        }
    }

    private void moveClips(List<Animation> clips, String folder) {
        try {
            folderService.moveClips(clips, folder);
            grid.deselectAll();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** Deletes the clips checked in the table, once confirmed (they leave their animations). */
    private void deleteSelected() {
        List<Animation> selected = selectedClips();
        if (selected.isEmpty()) {
            return;
        }
        ConfirmDialog.show(String.format(loc.getValue(J_L.MSG_CONFIRM_DELETE_CLIPS), selected.size()), () -> {
            try {
                for (Animation clip : selected) {
                    animationService.delete(clip, false);
                }
                grid.deselectAll();
                refresh();
            } catch (Exception ex) {
                UIUtils.internalServerError(loc, ex);
            }
        });
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

    /** Makes a new pack of the clip (named after it, with its description) and opens it. */
    private void createPack(Animation clip) {
        try {
            AnimationPackDialog dialog = new AnimationPackDialog(packService.createFrom(animationService.find(clip.getId())));
            dialog.setOnClose(this::refreshQuietly);
            dialog.create();
            dialog.open();
            refresh();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** Makes a new pack of the clip, for tests; returns its dialog. */
    AnimationPackDialog createPackForTest(Animation clip) throws Exception {
        AnimationPack pack = packService.createFrom(animationService.find(clip.getId()));
        AnimationPackDialog dialog = new AnimationPackDialog(pack);
        dialog.create();
        return dialog;
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
            folderService.moveClips(List.of(added), folders.getCurrent());
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
            // the clips go into the folder open when the file came
            String folder = folders.getCurrent();
            conversionService.convert(file, fileName, (added, error) -> ui.access(() -> converted(fileName, added, error, folder)));
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

    private void converted(String fileName, List<Animation> added, String error, String folder) {
        conversions.remove(fileName);
        showConversions();
        if (error != null) {
            Notification.error(String.format(loc.getValue(J_L.MSG_ANIMATION_CONVERSION_FAILED), fileName, error));
            return;
        }
        try {
            folderService.moveClips(added, folder);
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
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
            grid.setItems(folders.rows(folderService.clipsIn(folders.getCurrent())));
        }
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The grid of the folders and clips, for tests. */
    Grid<FolderRow<Animation>> getGrid() {
        return grid;
    }

    /** The folders of the tab, for tests. */
    LibraryFolders getFolders() {
        return folders;
    }

    /** Moves the clips into the folder (null: the top level) as Move to a folder does, for tests. */
    void moveForTest(List<Animation> clips, String folder) {
        moveClips(clips, folder);
        refreshQuietly();
    }

    /** What an upload does once the file is written, for tests. */
    void uploadedForTest(File file, String fileName) {
        uploaded(file, fileName);
    }

    /** What a finished conversion does (its clips go into the folder open when the file came), for tests. */
    void convertedForTest(String fileName, List<Animation> added, String folder) {
        converted(fileName, added, null, folder);
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
