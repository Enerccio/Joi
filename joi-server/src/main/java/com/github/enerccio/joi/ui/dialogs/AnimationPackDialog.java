package com.github.enerccio.joi.ui.dialogs;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.animations.AnimationFace;
import com.github.enerccio.joi.domain.animations.AnimationStep;
import com.github.enerccio.joi.domain.animations.StepRotation;
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
import com.vaadin.flow.component.select.Select;
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
 * its speed, in place, reversed and blend in, edited in the table; under it the rotation of the step chosen, in
 * degrees or with rings in the preview, moved up and down, opened and removed. On the right the
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
    private Select<String> eyesField;
    private Select<String> expressionField;
    private MultiSelectComboBox<Animation> choice;
    private Button addButton;
    private Span emptyLibrary;
    private Grid<Step> steps;
    private List<Step> shown = List.of();
    private ClipPreview preview;
    private VerticalLayout rotationPanel;
    private final NumberField[] rotationFields = new NumberField[3];
    private Button rotateButton;
    /** Whether the rings to turn the step chosen are shown in the preview. */
    private boolean rotating;
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

        eyesField = faceSelect(J_L.LABEL_FACE_EYES, AnimationFace.EYES, pack.getEyes(), value -> switch (value) {
            case "closed" -> J_L.FACE_EYES_CLOSED;
            case "half" -> J_L.FACE_EYES_HALF;
            default -> J_L.FACE_EYES_WIDE;
        });
        expressionField = faceSelect(J_L.LABEL_FACE_EXPRESSION, AnimationFace.EXPRESSIONS, pack.getExpression(), value -> switch (value) {
            case "happy" -> J_L.FACE_EXPRESSION_HAPPY;
            case "sad" -> J_L.FACE_EXPRESSION_SAD;
            case "angry" -> J_L.FACE_EXPRESSION_ANGRY;
            case "relaxed" -> J_L.FACE_EXPRESSION_RELAXED;
            default -> J_L.FACE_EXPRESSION_SURPRISED;
        });
        HorizontalLayout face = new HorizontalLayout(eyesField, expressionField);
        face.setWidthFull();
        Span faceHint = new Span(loc.getValue(J_L.MSG_FACE_HINT));
        faceHint.addClassName(CssStyles.HINT_TEXT);

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
        steps.addComponentColumn(this::reverseCell).setHeader(loc.getValue(J_L.LABEL_ANIMATION_REVERSE)).setFlexGrow(0).setWidth("80px");
        steps.addComponentColumn(this::blendCell).setHeader(loc.getValue(J_L.LABEL_ANIMATION_BLEND)).setFlexGrow(0).setWidth("90px");
        steps.addComponentColumn(this::stepActions).setHeader("").setFlexGrow(0).setWidth("168px");
        steps.addSelectionListener(e -> {
            if (!filling) {
                e.getFirstSelectedItem().ifPresent(this::playStep);
                showRotation();
            }
        });
        createRotationPanel();
        steps.addItemDoubleClickListener(e -> openClip(e.getItem().clip()));

        VerticalLayout sidebar = new VerticalLayout(nameField, descriptionField, loopField, face, faceHint, stepsTitle, hint, adding, emptyLibrary, steps,
                rotationPanel);
        sidebar.setPadding(false);
        sidebar.addClassName(JoiCssStyles.MODEL_SIDEBAR);

        AnimationPack stored = pack.getId() == null ? null : pack;
        preview = new ClipPreview(stored == null || packService.previewBody(stored) == null ? null : stored.getPreviewBodyId());
        preview.setOnBodyChosen(id -> storePack(false));
        preview.setOnPlay(this::playAll);
        preview.setOnStepRotated(this::storeRotation);
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

    private Component reverseCell(Step step) {
        Checkbox reverse = new Checkbox(step.step().reverse());
        reverse.setTooltipText(loc.getValue(J_L.MSG_ANIMATION_REVERSE_HINT));
        reverse.addValueChangeListener(e -> updateStep(step, s -> s.withReverse(Boolean.TRUE.equals(e.getValue()))));
        return reverse;
    }

    private Component blendCell(Step step) {
        NumberField blend = new NumberField();
        blend.setMin(0);
        blend.setMax(AnimationStep.MAX_BLEND);
        blend.setStep(0.1);
        blend.setWidthFull();
        blend.setValue(step.step().blend());
        blend.setTooltipText(loc.getValue(J_L.MSG_ANIMATION_BLEND_HINT));
        blend.addValueChangeListener(e -> {
            Double value = e.getValue();
            updateStep(step, s -> s.withBlend(value == null ? 0 : value));
        });
        return blend;
    }

    /** The rotation of the step chosen: degrees around X, Y and Z, the rings in the preview, and Reset. */
    private void createRotationPanel() {
        Span title = new Span(loc.getValue(J_L.LABEL_STEP_ROTATION));
        title.addClassName(JoiCssStyles.STEP_ROTATION_TITLE);
        String[] axes = {"X°", "Y°", "Z°"};
        for (int i = 0; i < 3; i++) {
            NumberField field = new NumberField(axes[i]);
            field.setMin(-180);
            field.setMax(180);
            // the rings turn by any angle: shown to a tenth of a degree
            field.setStep(0.1);
            field.addClassName(JoiCssStyles.STEP_ROTATION_FIELD);
            field.addValueChangeListener(e -> {
                if (e.isFromClient()) {
                    double[] degrees = new double[3];
                    for (int axis = 0; axis < 3; axis++) {
                        Double value = rotationFields[axis].getValue();
                        degrees[axis] = value == null ? 0 : value;
                    }
                    rotateTo(degrees[0], degrees[1], degrees[2]);
                }
            });
            rotationFields[i] = field;
        }
        rotateButton = new Button(loc.getValue(J_L.LABEL_ROTATE_IN_PREVIEW), Solid.ROTATE.create(), e -> setRotating(!rotating));
        Button reset = new Button(loc.getValue(J_L.LABEL_RESET_ROTATION), Solid.ARROW_ROTATE_LEFT.create(),
                e -> selected().ifPresent(step -> updateStep(step, s -> s.withRotation(null))));
        HorizontalLayout fields = new HorizontalLayout(rotationFields[0], rotationFields[1], rotationFields[2], rotateButton, reset);
        fields.setAlignItems(FlexComponent.Alignment.BASELINE);
        fields.setWrap(true);
        Span hint = new Span(loc.getValue(J_L.MSG_STEP_ROTATION_HINT));
        hint.addClassName(CssStyles.HINT_TEXT);
        rotationPanel = new VerticalLayout(title, fields, hint);
        rotationPanel.setPadding(false);
        rotationPanel.setSpacing(false);
        rotationPanel.setVisible(false);
    }

    /** Turns the step chosen to the angles (degrees around X, Y, Z) and plays it so. */
    private void rotateTo(double x, double y, double z) {
        selected().ifPresent(step -> updateStep(step, s -> s.withRotation(StepRotation.fromDegrees(x, y, z))));
    }

    /** Shows the rotation of the step chosen (none chosen: hides it and the rings). */
    private void showRotation() {
        java.util.Optional<Step> step = selected();
        rotationPanel.setVisible(step.isPresent());
        if (step.isEmpty()) {
            setRotating(false);
            return;
        }
        double[] degrees = StepRotation.toDegrees(step.get().step().rotation());
        for (int i = 0; i < 3; i++) {
            rotationFields[i].setValue(Math.round(degrees[i] * 10) / 10.0);
        }
    }

    private void setRotating(boolean on) {
        rotating = on && selected().isPresent();
        rotateButton.setThemeName(rotating ? "primary" : "");
        preview.setRotationTool(rotating);
    }

    /** The step chosen was turned with the rings: stored, without playing it again (it plays on, turned). */
    private void storeRotation(List<Double> rotation) {
        selected().ifPresent(step -> {
            try {
                pack = packService.updateStep(pack, step.index(), step.step().withRotation(rotation));
                refreshSteps(step.index());
                showRotation();
            } catch (Exception e) {
                UIUtils.internalServerError(loc, e);
            }
        });
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
            showRotation();
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

    /**
     * A choice of the face this animation forces: "her own" (empty), or one of the values.
     *
     * @param label  the label
     * @param values the values to choose from
     * @param value  what the animation has now, null for her own
     * @param text   the text of a value
     */
    private Select<String> faceSelect(J_L label, List<String> values, String value, java.util.function.Function<String, J_L> text) {
        Select<String> select = new Select<>();
        select.setLabel(loc.getValue(label));
        List<String> items = new ArrayList<>();
        items.add("");
        items.addAll(values);
        select.setItems(items);
        select.setItemLabelGenerator(item -> item.isEmpty() ? loc.getValue(J_L.FACE_OWN) : loc.getValue(text.apply(item)));
        select.setValue(value == null ? "" : value);
        select.addValueChangeListener(e -> {
            storePack(false);
            playAll();
        });
        return select;
    }

    /** Plays the whole animation (no step chosen in the table). */
    private void playAll() {
        filling = true;
        try {
            steps.deselectAll();
        } finally {
            filling = false;
        }
        showRotation();
        // what the fields say, not the saved animation: a value taken out does not clear the field of the one saved before
        preview.setFace(StringUtils.defaultIfBlank(eyesField.getValue(), null), StringUtils.defaultIfBlank(expressionField.getValue(), null));
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
            stored.setEyes(eyesField.getValue());
            stored.setExpression(expressionField.getValue());
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

    /** The eyes field, for tests. */
    public Select<String> getEyesField() {
        return eyesField;
    }

    /** The expression field, for tests. */
    public Select<String> getExpressionField() {
        return expressionField;
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

    /** The rotation of the step chosen, for tests. */
    public VerticalLayout getRotationPanel() {
        return rotationPanel;
    }

    /** The degrees around X, Y and Z of the step chosen, for tests. */
    public NumberField[] getRotationFields() {
        return rotationFields;
    }

    /** Types the angles of the step chosen, for tests. */
    public void rotateForTest(double x, double y, double z) {
        rotateTo(x, y, z);
    }

    /** Turns the step chosen as the rings in the preview do, for tests. */
    public void rotateWithRingsForTest(List<Double> rotation) {
        storeRotation(rotation);
    }

    /** The preview, for tests. */
    public ClipPreview getPreview() {
        return preview;
    }
}
