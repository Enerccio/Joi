package com.github.enerccio.joi.ui.animations;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.animations.AnimationFolder;
import com.github.enerccio.joi.domain.animations.AnimationFolder.Kind;
import com.github.enerccio.joi.domain.animations.AnimationFolders;
import com.github.enerccio.joi.domain.service.AnimationFolderService;
import com.github.enerccio.joi.domain.service.AnimationFolderService.Content;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.dialogs.ConfirmDialog;
import com.github.enerccio.marginalia.ui.dialogs.ListSelectDialog;
import com.github.enerccio.marginalia.ui.dialogs.TextInputDialog;
import com.github.enerccio.marginalia.ui.widgets.Notification;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The folders of a tab of the animation library (the Clips or the Animations tab), as Marginalia's book list has
 * them: the path of the folder open above the table (the tab's name for the top level, then each folder above to click
 * on, the folder itself in bold), the folders in it as the first rows of the table (opened from their name, with
 * rename, move and delete), New folder, and the choice of a folder to move items or a folder into. The rules are the
 * {@link AnimationFolderService}'s; this asks what they need (a name, what happens with the content) and says what the
 * service refused.
 */
@Configurable
public class LibraryFolders {

    /** A place to move into: a folder, or the top level (null). */
    private record Target(String folder, String label) {
    }

    @Autowired
    private Localization loc;

    @Autowired
    private AnimationFolderService folderService;

    private final Kind kind;
    private final String home;
    private final Runnable changed;
    private final FlexLayout breadcrumb = new FlexLayout();
    /** The uuid of the folder open, null for the top level. */
    private String current;

    /**
     * @param kind    clips or animations
     * @param home    the name of the top level (the tab's)
     * @param changed what runs when the folder open or the folders changed (the tab lists again)
     */
    public LibraryFolders(Kind kind, String home, Runnable changed) {
        this.kind = kind;
        this.home = home;
        this.changed = changed;
        breadcrumb.setAlignItems(FlexComponent.Alignment.CENTER);
        breadcrumb.addClassNames(CssStyles.FLEX_WRAP, CssStyles.GAP_S);
    }

    /** Where the table is: the path of the folder open, to click on. */
    public FlexLayout getBreadcrumb() {
        return breadcrumb;
    }

    /** The uuid of the folder open, null for the top level. */
    public String getCurrent() {
        return current;
    }

    /** The New folder button (in the folder open). */
    public Button newFolderButton() {
        return new Button(loc.getValue(L.LABEL_NEW_FOLDER), Solid.FOLDER_PLUS.create(), e -> create());
    }

    /** Goes into the folder (null: the top level). */
    public void open(String folder) {
        current = folder;
        changed.run();
    }

    /**
     * The rows of the folder open: its folders by name, then the items (those of the folder open, by name). The folder
     * open is checked first: one gone since is the top level.
     */
    public <T> List<FolderRow<T>> rows(List<T> items) throws Exception {
        AnimationFolders tree = folderService.tree(kind);
        current = tree.known(current);
        List<FolderRow<T>> rows = new ArrayList<>();
        for (AnimationFolder folder : tree.children(current)) {
            rows.add(FolderRow.ofFolder(folder));
        }
        for (T item : items) {
            rows.add(FolderRow.ofItem(item));
        }
        updateBreadcrumb(tree);
        return rows;
    }

    private void updateBreadcrumb(AnimationFolders tree) {
        breadcrumb.removeAll();
        Button top = new Button(home, VaadinIcon.HOME.create(), e -> open(null));
        top.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        breadcrumb.add(top);
        AnimationFolder folder = tree.find(current);
        if (folder == null) {
            return;
        }
        for (AnimationFolder above : tree.ancestors(current)) {
            breadcrumb.add(new Span("/"));
            Button button = new Button(above.getName(), e -> open(above.getUuid()));
            button.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
            breadcrumb.add(button);
        }
        breadcrumb.add(new Span("/"));
        Span name = new Span(folder.getName());
        name.addClassName(CssStyles.TEXT_BOLD);
        breadcrumb.add(name);
    }

    /** The name cell of a folder row: its name with a folder icon, opening it. */
    public Component folderName(AnimationFolder folder) {
        Button open = new Button(folder.getName(), VaadinIcon.FOLDER.create(), e -> open(folder.getUuid()));
        open.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        return open;
    }

    /** The buttons of a folder row: rename, move and delete (it opens from its name). */
    public Component folderTools(AnimationFolder folder) {
        Button rename = new Button(Solid.PENCIL.create(), e -> rename(folder));
        rename.setTooltipText(loc.getValue(L.LABEL_RENAME));
        Button move = new Button(Solid.FOLDER_TREE.create(), e -> moveFolder(folder));
        move.setTooltipText(loc.getValue(J_L.LABEL_MOVE_FOLDER));
        Button delete = new Button(Solid.TRASH.create(), e -> delete(folder));
        delete.setThemeName("error tertiary");
        delete.setTooltipText(loc.getValue(J_L.LABEL_DELETE_FOLDER));
        HorizontalLayout actions = new HorizontalLayout(rename, move, delete);
        actions.setSpacing(true);
        return actions;
    }

    /** Asks for the name and makes the folder in the folder open. */
    public void create() {
        new TextInputDialog.Builder(loc.getValue(L.LABEL_NEW_FOLDER), name -> {
            try {
                folderService.create(kind, name, current);
                changed.run();
            } catch (Exception e) {
                refused(e);
            }
        }).inputMessageLabel(loc.getValue(L.LABEL_FOLDER_NAME)).messageRequired().showCancel(true).build().open();
    }

    private void rename(AnimationFolder folder) {
        new TextInputDialog.Builder(loc.getValue(L.LABEL_RENAME), name -> {
            try {
                folderService.rename(kind, folder.getUuid(), name);
                changed.run();
            } catch (Exception e) {
                refused(e);
            }
        }).inputMessageLabel(loc.getValue(L.LABEL_FOLDER_NAME)).initialValue(folder.getName()).messageRequired().showCancel(true)
                .build().open();
    }

    /** Asks for the folder to move the folder into (not itself, nor a folder below it, nor where it is). */
    private void moveFolder(AnimationFolder folder) {
        try {
            AnimationFolders tree = folderService.tree(kind);
            Set<String> below = tree.withDescendants(folder.getUuid());
            String parent = tree.known(folder.getParent());
            chooseTarget(tree, uuid -> !below.contains(uuid) && !uuid.equals(parent), parent != null, target -> {
                try {
                    folderService.move(kind, folder.getUuid(), target);
                    changed.run();
                } catch (IllegalArgumentException e) {
                    Notification.warning(loc.getValue(J_L.MSG_FOLDER_INTO_ITSELF));
                } catch (Exception e) {
                    refused(e);
                }
            });
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /**
     * Asks for the folder to put items into (the top level among the choices, the folder open left out) and moves them.
     *
     * @param move moves the items into the folder chosen (null: the top level)
     */
    public void moveItems(Consumer<String> move) {
        try {
            AnimationFolders tree = folderService.tree(kind);
            String open = tree.known(current);
            chooseTarget(tree, uuid -> !uuid.equals(open), open != null, target -> {
                try {
                    move.accept(target);
                    changed.run();
                } catch (Exception e) {
                    UIUtils.internalServerError(loc, e);
                }
            });
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void chooseTarget(AnimationFolders tree, java.util.function.Predicate<String> allowed, boolean topLevel, Consumer<String> chosen) {
        List<Target> targets = targets(tree, allowed, topLevel);
        ListSelectDialog<Target> dialog = new ListSelectDialog<>(loc.getValue(J_L.LABEL_MOVE_TO_FOLDER), loc.getValue(J_L.LABEL_FOLDER),
                Target::label, target -> chosen.accept(target.folder()));
        dialog.create();
        dialog.setItems(targets);
        dialog.open();
    }

    /** The places to move into: the top level (when allowed) and the folders allowed, by path. */
    private List<Target> targets(AnimationFolders tree, java.util.function.Predicate<String> allowed, boolean topLevel) {
        List<Target> targets = new ArrayList<>();
        if (topLevel) {
            targets.add(new Target(null, loc.getValue(J_L.LABEL_TOP_LEVEL)));
        }
        for (AnimationFolder folder : tree.sortedByLabel(tree.all(), AnimationFolder::getParent, AnimationFolder::getName)) {
            if (allowed.test(folder.getUuid())) {
                targets.add(new Target(folder.getUuid(), tree.path(folder.getUuid())));
            }
        }
        return targets;
    }

    /**
     * Deletes the folder: an empty one after a plain confirmation; otherwise the user chooses between moving what is in
     * it to the folder above (the default) and deleting all of it, which asks a second time with the numbers.
     */
    private void delete(AnimationFolder folder) {
        try {
            Content content = folderService.describeContent(kind, folder.getUuid());
            if (content.items() == 0 && content.folders() == 0) {
                ConfirmDialog.show(loc.getValue(L.MSG_CONFIRM_DELETE), () -> deleteMoving(folder));
                return;
            }
            chooseDeletion(folder, content);
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void chooseDeletion(AnimationFolder folder, Content content) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(loc.getValue(J_L.LABEL_DELETE_FOLDER));
        dialog.setWidth("460px");
        dialog.setMaxWidth("100%");

        RadioButtonGroup<Boolean> choice = new RadioButtonGroup<>();
        choice.setItems(false, true);
        choice.setItemLabelGenerator(all -> loc.getValue(all ? J_L.LABEL_FOLDER_DELETE_CONTENT : J_L.LABEL_FOLDER_MOVE_CONTENT_UP));
        choice.setValue(false);
        J_L question = kind == Kind.CLIPS ? J_L.MSG_DELETE_CLIP_FOLDER_CONTENT : J_L.MSG_DELETE_ANIMATION_FOLDER_CONTENT;
        VerticalLayout layout = new VerticalLayout(
                new Span(String.format(loc.getValue(question), folder.getName(), content.items(), content.folders())), choice);
        layout.setPadding(false);
        dialog.add(layout);

        Button cancel = new Button(loc.getValue(L.LABEL_CANCEL), e -> dialog.close());
        Button delete = new Button(loc.getValue(L.LABEL_DELETE), e -> {
            dialog.close();
            if (Boolean.TRUE.equals(choice.getValue())) {
                J_L confirm = kind == Kind.CLIPS ? J_L.MSG_CONFIRM_DELETE_CLIP_FOLDER_CONTENT : J_L.MSG_CONFIRM_DELETE_ANIMATION_FOLDER_CONTENT;
                ConfirmDialog.show(String.format(loc.getValue(confirm), content.items(), content.folders()), () -> deleteWithContent(folder));
            } else {
                deleteMoving(folder);
            }
        });
        delete.setThemeName("error primary");
        HorizontalLayout footer = new HorizontalLayout(cancel, delete);
        footer.setWidthFull();
        footer.setJustifyContentMode(FlexComponent.JustifyContentMode.END);
        dialog.getFooter().add(footer);
        dialog.open();
    }

    private void deleteMoving(AnimationFolder folder) {
        try {
            folderService.delete(kind, folder.getUuid());
            changed.run();
        } catch (IllegalStateException e) {
            Notification.warning(loc.getValue(J_L.MSG_FOLDER_CLASH_ON_DELETE));
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void deleteWithContent(AnimationFolder folder) {
        try {
            folderService.deleteWithContent(kind, folder.getUuid());
            changed.run();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void refused(Exception e) {
        if (e instanceof IllegalArgumentException) {
            Notification.warning(loc.getValue(J_L.MSG_FOLDER_NAME_INVALID));
        } else if (e instanceof IllegalStateException) {
            Notification.warning(loc.getValue(J_L.MSG_FOLDER_NAME_TAKEN));
        } else {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The places a folder can be moved into, by path, for tests. */
    public List<String> folderTargetsForTest(AnimationFolder folder) throws Exception {
        AnimationFolders tree = folderService.tree(kind);
        Set<String> below = tree.withDescendants(folder.getUuid());
        String parent = tree.known(folder.getParent());
        return targets(tree, uuid -> !below.contains(uuid) && !uuid.equals(parent), parent != null).stream().map(Target::label).toList();
    }
}
