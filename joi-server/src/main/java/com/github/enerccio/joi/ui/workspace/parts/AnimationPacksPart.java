package com.github.enerccio.joi.ui.workspace.parts;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.animations.AnimationFolder.Kind;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.service.AnimationFolderService;
import com.github.enerccio.joi.domain.service.AnimationPackService;
import com.github.enerccio.joi.domain.service.JoiaService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.protocol.joia.JoiaFile;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.animations.FolderRow;
import com.github.enerccio.joi.ui.animations.LibraryFolders;
import com.github.enerccio.joi.ui.dialogs.AnimationPackDialog;
import com.github.enerccio.joi.ui.workspace.WorkspacePart;
import com.github.enerccio.marginalia.UIConstants;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.LLMInteract;
import com.github.enerccio.marginalia.ui.LLMInteraction;
import com.github.enerccio.marginalia.ui.dialogs.ConfirmDialog;
import com.github.enerccio.marginalia.ui.widgets.Notification;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.server.streams.DownloadHandler;
import com.vaadin.flow.server.streams.UploadHandler;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The animations of the user ({@link AnimationPack}, see {@code DESIGN.md} → Bodies, animations): a table with
 * checkboxes (Delete selected), the name, the description, how many steps and when it was added, Open and Delete (its
 * clips stay in the library); <i>Add animation</i> and Open show the {@link AnimationPackDialog} (steps of clips of the
 * library, previewed on a body). <i>Export selected</i> downloads the checked animations with their clips as a
 * {@code .joia}; <i>Import .joia</i> adds the animations of one, and its clips the library doesn't have yet. The
 * animations are in folders, as Marginalia's books ({@link LibraryFolders}); a new or imported one goes into the folder
 * open.
 */
@Configurable
public class AnimationPacksPart implements WorkspacePart {

    @Autowired
    private Localization loc;

    @Autowired
    private AnimationPackService packService;

    @Autowired
    private JoiaService joiaService;

    @Autowired
    private ModelService modelService;

    @Autowired
    private AnimationFolderService folderService;

    private Grid<FolderRow<AnimationPack>> grid;
    private LibraryFolders folders;
    private Button addButton;
    private Button deleteSelectedButton;
    private Button moveSelectedButton;
    private Button exportButton;
    private Anchor exportAnchor;
    private Upload importUpload;
    /** The animations checked, for the export's download (made outside the UI's lock). */
    private volatile List<AnimationPack> checked = List.of();

    @Override
    public Component create() throws Exception {
        H4 title = new H4(loc.getValue(J_L.LABEL_ANIMATION_PACKS));
        folders = new LibraryFolders(Kind.ANIMATIONS, loc.getValue(J_L.LABEL_ANIMATION_PACKS), this::refreshQuietly);
        addButton = new Button(loc.getValue(J_L.LABEL_ADD_ANIMATION_PACK), Solid.PLUS.create(), e -> open(newPack()));
        addButton.setThemeName("primary");
        deleteSelectedButton = new Button(loc.getValue(L.LABEL_DELETE_SELECTED), Solid.TRASH.create(), e -> deleteSelected());
        deleteSelectedButton.setThemeName("error");
        deleteSelectedButton.setEnabled(false);
        moveSelectedButton = new Button(loc.getValue(J_L.LABEL_MOVE_TO_FOLDER), Solid.FOLDER_OPEN.create(), e -> moveSelected());
        moveSelectedButton.setEnabled(false);
        exportButton = new Button(loc.getValue(J_L.LABEL_EXPORT_JOIA), Solid.FILE_EXPORT.create());
        exportAnchor = new Anchor(exportHandler(), "");
        exportAnchor.getElement().setAttribute("download", true);
        exportAnchor.setRouterIgnore(true);
        exportAnchor.add(exportButton);
        exportAnchor.setEnabled(false);
        exportButton.setEnabled(false);
        File folder = modelService.uploadFolder();
        importUpload = new Upload(UploadHandler.toFile((metadata, file) -> imported(file, metadata.fileName()),
                metadata -> ModelFiles.uploadFile(folder, metadata.fileName())));
        importUpload.setAcceptedFileTypes("." + JoiaFile.EXTENSION);
        importUpload.setUploadButton(new Button(loc.getValue(J_L.LABEL_IMPORT_JOIA), Solid.FILE_IMPORT.create()));
        HorizontalLayout buttons = new HorizontalLayout(deleteSelectedButton, moveSelectedButton, exportAnchor, importUpload,
                folders.newFolderButton(), addButton);
        buttons.setAlignItems(FlexComponent.Alignment.CENTER);
        // the buttons wrap under each other rather than run off a narrow window
        buttons.setWrap(true);
        buttons.setJustifyContentMode(FlexComponent.JustifyContentMode.END);
        HorizontalLayout header = new HorizontalLayout(title, buttons);
        header.setWidthFull();
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);

        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        grid = new Grid<>();
        grid.setSelectionMode(Grid.SelectionMode.MULTI);
        // folders are opened, not checked
        grid.setItemSelectableProvider(row -> !row.isFolder());
        grid.addSelectionListener(e -> {
            boolean any = !e.getAllSelectedItems().isEmpty();
            checked = e.getAllSelectedItems().stream().filter(row -> !row.isFolder()).map(FolderRow::item).toList();
            deleteSelectedButton.setEnabled(any);
            moveSelectedButton.setEnabled(any);
            exportButton.setEnabled(any);
            exportAnchor.setEnabled(any);
        });
        grid.setSizeFull();
        grid.addComponentColumn(this::nameCell)
                .setHeader(loc.getValue(L.LABEL_NAME))
                .setFlexGrow(1);
        grid.addColumn(row -> row.isFolder() ? "" : StringUtils.abbreviate(StringUtils.defaultString(row.item().getDescription()), 120))
                .setHeader(loc.getValue(L.LABEL_DESCRIPTION))
                .setFlexGrow(2);
        grid.addColumn(row -> row.isFolder() ? "" : String.valueOf(row.item().steps().size()))
                .setHeader(loc.getValue(J_L.LABEL_ANIMATION_STEPS))
                .setFlexGrow(0)
                .setWidth("90px");
        grid.addColumn(row -> row.isFolder() || row.item().getCreation() == null ? "" : format.format(row.item().getCreation()))
                .setHeader(loc.getValue(L.LABEL_CREATED))
                .setFlexGrow(0)
                .setWidth(UIConstants.DATE_COLUMN_SIZE);
        grid.addComponentColumn(row -> row.isFolder() ? folders.folderTools(row.folder()) : actions(row.item()))
                .setHeader("")
                .setFlexGrow(0)
                .setWidth(UIConstants.TOOL_COLUMN_SIZE_GIANT);
        grid.addItemDoubleClickListener(e -> {
            if (e.getItem().isFolder()) {
                folders.open(e.getItem().folder().getUuid());
            } else {
                open(e.getItem().item());
            }
        });

        VerticalLayout layout = new VerticalLayout(header, folders.getBreadcrumb(), grid);
        layout.setSizeFull();
        layout.setPadding(false);
        layout.setFlexGrow(1, grid);
        return layout;
    }

    private Component nameCell(FolderRow<AnimationPack> row) {
        if (row.isFolder()) {
            return folders.folderName(row.folder());
        }
        HorizontalLayout name = new HorizontalLayout(VaadinIcon.FILM.create(),
                new Span(StringUtils.defaultIfBlank(row.item().getName(), loc.getValue(J_L.LABEL_NEW_ANIMATION_PACK))));
        name.setSpacing(true);
        name.setPadding(false);
        name.setAlignItems(FlexComponent.Alignment.CENTER);
        return name;
    }

    /** A new animation, in the folder open (stored once it has a name or a clip). */
    private AnimationPack newPack() {
        AnimationPack pack = new AnimationPack();
        pack.setFolder(folders.getCurrent());
        return pack;
    }

    private Component actions(AnimationPack pack) {
        Button openButton = new Button(Solid.FOLDER_OPEN.create(), e -> open(pack));
        openButton.setTooltipText(loc.getValue(L.LABEL_OPEN));
        Button moveButton = new Button(Solid.FOLDER_TREE.create(), e -> folders.moveItems(target -> moveAnimations(List.of(pack), target)));
        moveButton.setTooltipText(loc.getValue(J_L.LABEL_MOVE_TO_FOLDER));

        Button deleteButton = new Button(Solid.TRASH.create(), e ->
                ConfirmDialog.show(loc.getValue(L.MSG_CONFIRM_DELETE), () -> {
                    try {
                        packService.delete(packService.find(pack.getId()), false);
                        refresh();
                    } catch (Exception ex) {
                        UIUtils.internalServerError(loc, ex);
                    }
                }));
        deleteButton.setThemeName("error tertiary");
        deleteButton.setTooltipText(loc.getValue(L.LABEL_DELETE));

        HorizontalLayout actions = new HorizontalLayout(openButton, moveButton, deleteButton);
        actions.setSpacing(true);
        return actions;
    }

    /** Moves the animations checked into the folder chosen. */
    private void moveSelected() {
        List<AnimationPack> selected = checked;
        if (!selected.isEmpty()) {
            folders.moveItems(target -> moveAnimations(selected, target));
        }
    }

    private void moveAnimations(List<AnimationPack> animations, String folder) {
        try {
            folderService.moveAnimations(animations, folder);
            grid.deselectAll();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** Deletes the animations checked in the table, once confirmed (their clips stay). */
    private void deleteSelected() {
        List<AnimationPack> selected = new ArrayList<>(checked);
        if (selected.isEmpty()) {
            return;
        }
        ConfirmDialog.show(String.format(loc.getValue(J_L.MSG_CONFIRM_DELETE_ANIMATIONS), selected.size()), () -> {
            try {
                for (AnimationPack pack : selected) {
                    packService.delete(packService.find(pack.getId()), false);
                }
                grid.deselectAll();
                refresh();
            } catch (Exception ex) {
                UIUtils.internalServerError(loc, ex);
            }
        });
    }

    /** The {@code .joia} of the checked animations, written while it is streamed. */
    private DownloadHandler exportHandler() {
        return event -> {
            List<AnimationPack> animations = checked;
            event.setFileName(joiaService.fileName(animations));
            event.setContentType(JoiaFile.CONTENT_TYPE);
            try {
                joiaService.export(animations, event.getOutputStream());
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            }
        };
    }

    /** A {@code .joia} was uploaded: its animations are added, with the clips the library doesn't have. */
    private void imported(File file, String fileName) {
        importUpload.clearFileList();
        if (!JoiaFile.isJoia(fileName)) {
            FileUtils.deleteQuietly(file);
            Notification.error(String.format(loc.getValue(J_L.MSG_NOT_A_JOIA), fileName));
            return;
        }
        try {
            JoiaService.Imported imported = joiaService.importFile(file);
            folderService.moveAnimations(imported.animations(), folders.getCurrent());
            refresh();
            Notification.success(String.format(loc.getValue(J_L.MSG_JOIA_IMPORTED), imported.animations().size(),
                    imported.clipsAdded(), imported.clipsLinked()));
            if (imported.clipsFailed() > 0) {
                Notification.error(String.format(loc.getValue(J_L.MSG_JOIA_CLIPS_FAILED), imported.clipsFailed()));
            }
        } catch (JoiaService.ForeignClipException e) {
            Notification.error(loc.getValue(J_L.MSG_JOIA_FOREIGN_CLIP));
        } catch (IOException e) {
            Notification.error(String.format(loc.getValue(J_L.MSG_NOT_A_JOIA), e.getMessage()));
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** What an import does once the file is written, for tests. */
    void importedForTest(File file, String fileName) {
        imported(file, fileName);
    }

    /** Checks animations in the table, for tests; the export then has them in the order given (a set's order is the hashes', and changes from run to run). */
    void checkForTest(List<AnimationPack> animations) {
        java.util.Set<FolderRow<AnimationPack>> rows = new java.util.LinkedHashSet<>();
        animations.forEach(pack -> rows.add(FolderRow.ofItem(pack)));
        grid.asMultiSelect().setValue(rows);
        checked = List.copyOf(animations);
    }

    /** What the export's download writes, for tests. */
    void exportForTest(OutputStream out) throws Exception {
        joiaService.export(checked, out);
    }

    /** Shows the dialog of the pack, a new one (not stored until it has a name or a clip) or a stored one. */
    private void open(AnimationPack pack) {
        try {
            AnimationPackDialog dialog = new AnimationPackDialog(pack.getId() == null ? pack : packService.find(pack.getId()));
            dialog.setOnClose(this::refreshQuietly);
            dialog.create();
            dialog.open();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    @Override
    public void refresh() throws Exception {
        if (grid != null) {
            grid.setItems(folders.rows(folderService.animationsIn(folders.getCurrent())));
        }
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The grid of the folders and packs, for tests. */
    Grid<FolderRow<AnimationPack>> getGrid() {
        return grid;
    }

    /** The folders of the tab, for tests. */
    LibraryFolders getFolders() {
        return folders;
    }

    /** A new animation as Add animation makes it (in the folder open), for tests. */
    AnimationPack newPackForTest() {
        return newPack();
    }

    @Override
    public Map<String, LLMInteraction> getInteraction() {
        return LLMInteract.dialogButtons(addButton);
    }
}
