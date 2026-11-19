package com.github.enerccio.joi.ui.dialogs;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.animations.AnimationStep;
import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.service.AnimationPackService;
import com.github.enerccio.joi.domain.service.AnimationPackService.Step;
import com.github.enerccio.joi.domain.service.AnimationService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.joi.ui.bodies.ClipPreview;
import com.github.enerccio.joi.ui.widgets.EditorSplit;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * One animation ({@link AnimationPack}), full screen: on the left its name, its description, whether it starts again
 * after its last step, and its steps (clips chosen from the user's library, the Clips tab), each with whether it loops,
 * its speed and in place, edited in the table, moved up and down, opened and removed. On the right the
 * {@link ClipPreview} of the body it is previewed on (remembered): the whole animation plays when the dialog opens and
 * on Play, Finish ends a looping step and plays the rest, and a step chosen in the table or the strip plays alone, in a
 * loop. A clip added appends its description to the animation's. Changes are stored at once; a new animation is stored
 * once it gets a name or a clip.
 */
@Configurable
public class AnimationPackDialog extends Dialog {

    @Autowired
    private Localization loc;

    @Autowired
    private AnimationPackService packService;

    @Autowired
    private AnimationService animationService;

    private AnimationPack pack;
    private Runnable onClose = () -> {
    };
    private TextField nameField;
    private TextArea descriptionField;
    private Checkbox loopField;
    private MultiSelectComboBox<Animation> choice;
    private Button addButton;
    private Span emptyLibrary;
    private Grid<Step> steps;
    private List<Step> shown = List.of();
    private ClipPreview preview;
    /** Set while the table is filled, so its selection plays nothing. */
    private boolean filling;

    /**
     * @param pack the stored animation, or a new one
     */
    public AnimationPackDialog(AnimationPack pack) {
        this.pack = pack;
    }

    /** What runs when the dialog closes (the table of animations refreshes). */
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
        nameField.setValue(StringUtils.defaultString(pack.getName()));
        nameField.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                storePack(false);
            }
        });
        descriptionField = new TextArea(loc.getValue(L.LABEL_DESCRIPTION));
        descriptionField.setWidthFull();
        descriptionField.setValue(StringUtils.defaultString(pack.getDescription()));
        descriptionField.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                storePack(false);
            }
        });
        loopField = new Checkbox(loc.getValue(J_L.LABEL_LOOP_ANIMATION));
        loopField.setTooltipText(loc.getValue(J_L.MSG_LOOP_ANIMATION_HINT));
        loopField.setValue(pack.loops());
        loopField.addValueChangeListener(e -> {
            storePack(false);
            playAll();
        });

        H4 stepsTitle = new H4(loc.getValue(J_L.LABEL_ANIMATION_STEPS));
        Span hint = new Span(loc.getValue(J_L.MSG_ANIMATION_PACK_HINT));
        hint.addClassName(CssStyles.HINT_TEXT);
        choice = new MultiSelectComboBox<>(loc.getValue(J_L.LABEL_CHOOSE_CLIPS));
        choice.setItemLabelGenerator(clip -> StringUtils.defaultString(clip.getName()));
        choice.setWidthFull();
        addButton = new Button(loc.getValue(J_L.LABEL_ADD_CLIPS), Solid.PLUS.create(), e -> addChosen());
        HorizontalLayout adding = new HorizontalLayout(choice, addButton);
        adding.setWidthFull();
        adding.setAlignItems(FlexComponent.Alignment.BASELINE);
        adding.setFlexGrow(1, choice);
        emptyLibrary = new Span(loc.getValue(J_L.MSG_NO_CLIPS_YET));
        emptyLibrary.addClassName(CssStyles.HINT_TEXT);

        steps = new Grid<>(Step.class, false);
        steps.setWidthFull();
        steps.setAllRowsVisible(true);
        steps.addThemeVariants(GridVariant.LUMO_COMPACT);
        steps.addColumn(step -> step.index() + 1).setHeader("#").setFlexGrow(0).setWidth("44px");
        steps.addColumn(step -> step.clip().getName()).setHeader(loc.getValue(J_L.LABEL_CLIP))
                .setFlexGrow(1).setWidth("140px").setTooltipGenerator(step -> step.clip().getName());
        steps.addColumn(step -> AnimationDialog.length(step.clip().getDuration()))
                .setHeader(loc.getValue(J_L.LABEL_ANIMATION_LENGTH)).setFlexGrow(0).setWidth("72px");
        steps.addComponentColumn(this::loopCell).setHeader(loc.getValue(J_L.LABEL_ANIMATION_LOOP)).setFlexGrow(0).setWidth("64px");
        steps.addComponentColumn(this::speedCell).setHeader(loc.getValue(J_L.LABEL_SPEED)).setFlexGrow(0).setWidth("90px");
        steps.addComponentColumn(this::inPlaceCell).setHeader(loc.getValue(J_L.LABEL_ANIMATION_IN_PLACE)).setFlexGrow(0).setWidth("84px");
        steps.addComponentColumn(this::stepActions).setHeader("").setFlexGrow(0).setWidth("168px");
        steps.addSelectionListener(e -> {
            if (!filling) {
                e.getFirstSelectedItem().ifPresent(this::playStep);
            }
        });
        steps.addItemDoubleClickListener(e -> openClip(e.getItem().clip()));

        VerticalLayout sidebar = new VerticalLayout(nameField, descriptionField, loopField, stepsTitle, hint, adding, emptyLibrary, steps);
        sidebar.setPadding(false);
        sidebar.addClassName(JoiCssStyles.MODEL_SIDEBAR);

        AnimationPack stored = pack.getId() == null ? null : pack;
        preview = new ClipPreview(stored == null || packService.previewBody(stored) == null ? null : stored.getPreviewBodyId());
        preview.setOnBodyChosen(id -> storePack(false));
        preview.setOnPlay(this::playAll);
        preview.setOnStepChosen(position -> {
            if (position >= 0 && position < shown.size()) {
                steps.select(shown.get(position));
            }
        });
        add(EditorSplit.create(sidebar, preview, "joi.animation-dialog-steps.split", 55));

        Button exitButton = new Button(loc.getValue(L.LABEL_EXIT), e -> close());
        exitButton.setThemeName("primary");
        getFooter().add(exitButton);
        addOpenedChangeListener(e -> {
            if (!e.isOpened()) {
                onClose.run();
            }
        });
        refreshSteps(-1);
        playAll();
        nameField.focus();
    }

    private Component loopCell(Step step) {
        Checkbox loop = new Checkbox(step.step().loop());
        loop.addValueChangeListener(e -> updateStep(step, s -> s.withLoop(Boolean.TRUE.equals(e.getValue()))));
        return loop;
    }

    private Component speedCell(Step step) {
        NumberField speed = new NumberField();
        speed.setMin(0.1);
        speed.setMax(4);
        speed.setStep(0.1);
        speed.setWidthFull();
        speed.setValue(step.step().speedOrDefault());
        speed.addValueChangeListener(e -> {
            Double value = e.getValue();
            updateStep(step, s -> s.withSpeed(value == null || value <= 0 ? 1 : value));
        });
        return speed;
    }

    private Component inPlaceCell(Step step) {
        Checkbox inPlace = new Checkbox(step.step().inPlace());
        inPlace.setTooltipText(loc.getValue(J_L.MSG_ANIMATION_IN_PLACE_HINT));
        inPlace.addValueChangeListener(e -> updateStep(step, s -> s.withInPlace(Boolean.TRUE.equals(e.getValue()))));
        return inPlace;
    }

    private Component stepActions(Step step) {
        Button up = new Button(Solid.ARROW_UP.create(), e -> moveStep(step, -1));
        up.setTooltipText(loc.getValue(L.LABEL_MOVE_UP));
        up.setEnabled(step.index() > 0);
        Button down = new Button(Solid.ARROW_DOWN.create(), e -> moveStep(step, 1));
        down.setTooltipText(loc.getValue(L.LABEL_MOVE_DOWN));
        down.setEnabled(step.index() < pack.steps().size() - 1);
        Button open = new Button(Solid.FOLDER_OPEN.create(), e -> openClip(step.clip()));
        open.setTooltipText(loc.getValue(L.LABEL_OPEN));
        Button remove = new Button(Solid.MINUS.create(), e -> {
            try {
                pack = packService.removeStep(pack, step.index());
                refreshSteps(-1);
                playAll();
            } catch (Exception ex) {
                UIUtils.internalServerError(loc, ex);
            }
        });
        remove.setTooltipText(loc.getValue(J_L.LABEL_REMOVE_FROM_PACK));
        for (Button button : List.of(up, down, open, remove)) {
            button.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        }
        remove.addThemeVariants(ButtonVariant.LUMO_ERROR);
        HorizontalLayout actions = new HorizontalLayout(up, down, open, remove);
        actions.setSpacing(true);
        actions.setAlignItems(FlexComponent.Alignment.CENTER);
        return actions;
    }

    /** Stores a change of a step; the step plays alone, as it is now set. */
    private void updateStep(Step step, UnaryOperator<AnimationStep> change) {
        try {
            AnimationStep current = pack.steps().get(step.index());
            pack = packService.updateStep(pack, step.index(), change.apply(current));
            refreshSteps(step.index());
            selected().ifPresent(this::playStep);
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void moveStep(Step step, int delta) {
        try {
            pack = packService.moveStep(pack, step.index(), delta);
            refreshSteps(step.index() + delta);
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private java.util.Optional<Step> selected() {
        return steps.getSelectedItems().stream().findFirst();
    }

    private void playStep(Step step) {
        preview.playStep(shown.indexOf(step), step);
    }

    /** Plays the whole animation (no step chosen in the table). */
    private void playAll() {
        filling = true;
        try {
            steps.deselectAll();
        } finally {
            filling = false;
        }
        preview.playSequence(shown, pack.loops());
    }

    /** Adds a step per clip chosen (the animation is stored first when new). */
    private void addChosen() {
        try {
            List<Animation> chosen = new ArrayList<>(choice.getSelectedItems());
            if (chosen.isEmpty()) {
                return;
            }
            chosen.sort((a, b) -> StringUtils.compareIgnoreCase(a.getName(), b.getName()));
            if (pack.getId() == null) {
                storePack(true);
            }
            pack = packService.addClips(pack, chosen);
            descriptionField.setValue(StringUtils.defaultString(pack.getDescription()));
            choice.clear();
            refreshSteps(-1);
            playAll();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void openClip(Animation clip) {
        try {
            AnimationDialog dialog = new AnimationDialog(animationService.find(clip.getId()));
            dialog.setOnClose(() -> {
                try {
                    refreshSteps(selected().map(Step::index).orElse(-1));
                } catch (Exception e) {
                    UIUtils.internalServerError(loc, e);
                }
            });
            dialog.create();
            dialog.open();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /**
     * Stores the animation's name, description, loop and preview body.
     *
     * @param always whether to store a new animation without a name too (clips are added to it)
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
            stored.setLoop(Boolean.TRUE.equals(loopField.getValue()) ? true : null);
            stored.setPreviewBodyId(preview == null ? null : preview.getBodySelect().getValue());
            pack = packService.save(stored);
            updateTitle();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /**
     * The steps into the table and the strip, the library into the choice.
     *
     * @param select the index of the step to choose again without playing it, -1 for none
     */
    private void refreshSteps(int select) {
        try {
            shown = packService.steps(pack);
            filling = true;
            try {
                steps.setItems(shown);
                shown.stream().filter(step -> step.index() == select).findFirst().ifPresent(steps::select);
            } finally {
                filling = false;
            }
            preview.setSteps(shown);
            List<Animation> library = animationService.findAllByName();
            choice.setItems(library);
            emptyLibrary.setVisible(library.isEmpty());
            choice.setEnabled(!library.isEmpty());
            addButton.setEnabled(!library.isEmpty());
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void updateTitle() {
        setHeaderTitle(StringUtils.isBlank(pack.getName()) ? loc.getValue(J_L.LABEL_NEW_ANIMATION_PACK) : pack.getName());
    }

    /** The animation as stored last (no id while a new one is not stored), for tests. */
    public AnimationPack getPack() {
        return pack;
    }

    /** The table of the steps, for tests. */
    public Grid<Step> getSteps() {
        return steps;
    }

    /** The choice of clips to add, for tests. */
    public MultiSelectComboBox<Animation> getChoice() {
        return choice;
    }

    /** Adds the clips chosen, for tests. */
    public void addChosenForTest() {
        addChosen();
    }

    /** The name field, for tests. */
    public TextField getNameField() {
        return nameField;
    }

    /** The description field, for tests. */
    public TextArea getDescriptionField() {
        return descriptionField;
    }

    /** Whether the animation starts again after its last step, for tests. */
    public Checkbox getLoopField() {
        return loopField;
    }

    /** Changes a step as its cells do, for tests. */
    public void updateStepForTest(int index, UnaryOperator<AnimationStep> change) {
        shown.stream().filter(step -> step.index() == index).findFirst().ifPresent(step -> updateStep(step, change));
    }

    /** Moves a step as its arrows do, for tests. */
    public void moveStepForTest(int index, int delta) {
        shown.stream().filter(step -> step.index() == index).findFirst().ifPresent(step -> moveStep(step, delta));
    }

    /** The preview, for tests. */
    public ClipPreview getPreview() {
        return preview;
    }
}
