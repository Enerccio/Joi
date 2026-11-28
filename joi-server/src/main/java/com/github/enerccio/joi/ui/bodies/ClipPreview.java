package com.github.enerccio.joi.ui.bodies;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.animations.AnimationStep;
import com.github.enerccio.joi.domain.bodies.BodyState;
import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.service.AnimationPackService;
import com.github.enerccio.joi.domain.service.AnimationService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.server.VaadinSession;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The 3D preview of clips and animations (the clip and animation dialogs): the {@link ModelViewer} showing a body of
 * the user's assistants in the state of its parts, the choice of that body, and Pause and Stop. An animation's dialog
 * also gets Play (the whole animation), Finish (a looping step ends its round and the rest plays) and a strip of its
 * steps under the viewer, the one playing marked, with the rounds of a looping one; a step clicked there plays alone.
 * What plays goes on playing when the body changes. The body chosen last is the next preview's, for the session.
 */
@Configurable(preConstruction = true)
public class ClipPreview extends VerticalLayout {

    private static final String LAST_BODY = "joi.clip-preview-body";

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

    private final Map<Long, Body> bodies = new LinkedHashMap<>();
    private final Select<Long> bodySelect = new Select<>();
    private final ModelViewer viewer = new ModelViewer();
    private final Span info = new Span();
    private final Button pauseButton;
    private final Button playButton;
    private final Button finishButton;
    private final HorizontalLayout strip = new HorizontalLayout();
    private final List<Span> chips = new ArrayList<>();
    private List<Boolean> chipLoops = List.of();
    private boolean paused;
    private Consumer<Long> onBodyChosen = id -> {
    };
    private Runnable onPlay = () -> {
    };
    private IntConsumer onStepChosen = index -> {
    };

    /**
     * @param body the body to show first, null for the one chosen last (else the first)
     */
    public ClipPreview(Long body) {
        Map<Long, String> names = new LinkedHashMap<>();
        try {
            for (Body candidate : packService.previewBodies()) {
                bodies.put(candidate.getId(), candidate);
                Joi joi = joiService.find(candidate.getJoi().getId());
                names.put(candidate.getId(), joiService.name(joi) + ": " + StringUtils.defaultIfBlank(candidate.getName(), "?"));
            }
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
        bodySelect.setLabel(loc.getValue(J_L.LABEL_PREVIEW_BODY));
        bodySelect.setItems(new ArrayList<>(bodies.keySet()));
        bodySelect.setItemLabelGenerator(id -> id == null ? "" : names.getOrDefault(id, ""));
        Object last = VaadinSession.getCurrent() == null ? null : VaadinSession.getCurrent().getAttribute(LAST_BODY);
        Long initial = body != null && bodies.containsKey(body) ? body
                : last instanceof Long id && bodies.containsKey(id) ? id
                : bodies.keySet().stream().findFirst().orElse(null);
        bodySelect.setValue(initial);
        bodySelect.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                if (VaadinSession.getCurrent() != null) {
                    VaadinSession.getCurrent().setAttribute(LAST_BODY, e.getValue());
                }
                onBodyChosen.accept(e.getValue());
            }
            showBody();
        });

        playButton = new Button(loc.getValue(J_L.LABEL_PLAY_ANIMATION), Solid.PLAY.create(), e -> onPlay.run());
        playButton.setThemeName("primary");
        playButton.setVisible(false);
        finishButton = new Button(loc.getValue(J_L.LABEL_FINISH_ANIMATION), Solid.FORWARD_STEP.create(), e -> finish());
        finishButton.setTooltipText(loc.getValue(J_L.MSG_FINISH_ANIMATION_HINT));
        finishButton.setVisible(false);
        pauseButton = new Button(Solid.PAUSE.create(), e -> setPaused(!paused));
        pauseButton.setTooltipText(loc.getValue(J_L.LABEL_PAUSE));
        Button stopButton = new Button(Solid.STOP.create(), e -> stop());
        stopButton.setTooltipText(loc.getValue(L.LABEL_STOP));
        info.addClassName(CssStyles.HINT_TEXT);
        HorizontalLayout header = new HorizontalLayout(bodySelect, playButton, finishButton, pauseButton, stopButton, info);
        header.setAlignItems(FlexComponent.Alignment.BASELINE);
        header.setWrap(true);

        viewer.setSizeFull();
        viewer.addInfoListener(e -> showBodyState());
        viewer.addErrorListener(e -> info.setText(e.getMessage()));
        viewer.addAnimationStartedListener(e -> info.setText(""));
        viewer.addAnimationErrorListener(e -> info.setText(e.getMessage()));
        viewer.addAnimationStepListener(e -> markStep(e.getIndex(), e.getRepeats()));
        viewer.addAnimationFinishedListener(e -> markStep(-1, 0));
        viewer.addStepRotatedListener(e -> {
            if (soloStep >= 0 && e.getIndex() == 0) {
                onStepRotated.accept(e.getRotation());
            }
        });
        viewer.addStepTranslatedListener(e -> {
            if (soloStep >= 0 && e.getIndex() == 0) {
                onStepTranslated.accept(e.getTranslation());
            }
        });
        strip.addClassName(JoiCssStyles.ANIMATION_STRIP);
        strip.setWidthFull();
        strip.setVisible(false);

        add(header, viewer, strip);
        setPadding(false);
        setSpacing(false);
        addClassName(JoiCssStyles.MODEL_VIEWER);
        setFlexGrow(1, viewer);
        if (bodies.isEmpty()) {
            info.setText(loc.getValue(J_L.MSG_NO_PREVIEW_BODIES));
        }
        showBody();
    }

    /** What runs when the user chooses another body (a pack remembers it). */
    public void setOnBodyChosen(Consumer<Long> onBodyChosen) {
        this.onBodyChosen = onBodyChosen == null ? id -> {
        } : onBodyChosen;
    }

    /** What Play runs (it plays the whole animation); shows Play, Finish and the strip of steps. */
    public void setOnPlay(Runnable onPlay) {
        this.onPlay = onPlay == null ? () -> {
        } : onPlay;
        playButton.setVisible(onPlay != null);
        finishButton.setVisible(onPlay != null);
        strip.setVisible(onPlay != null);
    }

    /** What runs when a step is clicked in the strip (its position in the steps shown). */
    public void setOnStepChosen(IntConsumer onStepChosen) {
        this.onStepChosen = onStepChosen == null ? index -> {
        } : onStepChosen;
    }

    /** Shows the steps of the animation in the strip, by their clips' names. */
    public void setSteps(List<AnimationPackService.Step> steps) {
        strip.removeAll();
        chips.clear();
        List<Boolean> loops = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            AnimationPackService.Step step = steps.get(i);
            if (i > 0) {
                Span arrow = new Span("→");
                arrow.addClassName(JoiCssStyles.ANIMATION_STRIP_ARROW);
                strip.add(arrow);
            }
            Span chip = new Span();
            chip.addClassName(JoiCssStyles.ANIMATION_STEP);
            int position = i;
            chip.addClickListener(e -> onStepChosen.accept(position));
            chips.add(chip);
            loops.add(step.step().loop());
            strip.add(chip);
        }
        chipLoops = loops;
        for (int i = 0; i < chips.size(); i++) {
            chips.get(i).setText(chipText(steps.get(i).clip().getName(), loops.get(i), 0));
        }
        chipNames = steps.stream().map(step -> StringUtils.defaultString(step.clip().getName())).toList();
        // the steps were shown again (a step changed): the one playing stays marked
        markStep(markedIndex, markedRepeats);
    }

    private int markedIndex = -1;
    private int markedRepeats;

    private List<String> chipNames = List.of();

    private static String chipText(String name, boolean loop, int repeats) {
        return (loop ? "⟲ " : "") + StringUtils.defaultString(name) + (loop && repeats > 1 ? " ×" + repeats : "");
    }

    /** Marks the step playing (its position), -1 for none. */
    private void markStep(int index, int repeats) {
        markedIndex = index;
        markedRepeats = repeats;
        for (int i = 0; i < chips.size(); i++) {
            boolean current = i == index && soloStep < 0 || i == soloStep && index >= 0;
            chips.get(i).getElement().getClassList().set(JoiCssStyles.ANIMATION_STEP_PLAYING, current);
            chips.get(i).setText(chipText(chipNames.get(i), chipLoops.get(i), current ? repeats : 0));
        }
    }

    /** The step played alone (its position), -1 when the whole animation plays. */
    private int soloStep = -1;

    /** Plays the clip alone, in a loop, to see it; null stops. */
    public void play(Animation clip) {
        try {
            File file = clip == null ? null : animationService.file(clip);
            if (file == null) {
                stop();
                return;
            }
            setPaused(false);
            soloStep = -1;
            viewer.playAnimation(file, true, 1, clip.playsInPlace());
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** Plays a step of the animation alone (its position in the strip), in a loop, as it is set (speed, in place, reversed, turned). */
    public void playStep(int position, AnimationPackService.Step step) {
        try {
            File file = animationService.file(step.clip());
            if (file == null) {
                stop();
                return;
            }
            setPaused(false);
            soloStep = position;
            viewer.playSequence(List.of(playStep(file, step.step().withLoop(true))), false);
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The face the animations played from now on show (see {@link ModelViewer#setAnimationFace}). */
    public void setFace(String eyes, String expression) {
        viewer.setAnimationFace(eyes, expression);
    }

    /** Plays the whole animation: its steps in order, starting again after the last when it loops. */
    public void playSequence(List<AnimationPackService.Step> steps, boolean loop) {
        try {
            List<ModelViewer.PlayStep> play = new ArrayList<>();
            for (AnimationPackService.Step step : steps) {
                File file = animationService.file(step.clip());
                if (file != null) {
                    play.add(playStep(file, step.step()));
                }
            }
            if (play.isEmpty()) {
                stop();
                return;
            }
            setPaused(false);
            soloStep = -1;
            viewer.playSequence(play, loop);
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private static ModelViewer.PlayStep playStep(File file, AnimationStep step) {
        return new ModelViewer.PlayStep(file, step.loop(), step.speedOrDefault(), step.inPlace(), step.reverse(), step.rotation(),
                step.translation(), step.blend());
    }

    /**
     * Shows the rings to turn the step played alone (on) or hides them; a turn dragged there goes to
     * {@link #setOnStepRotated}.
     */
    public void setRotationTool(boolean on) {
        viewer.setRotationTool(on ? 0 : -1);
    }

    /** What runs when the step played alone was turned in the viewer (its new quaternion, null for none). */
    public void setOnStepRotated(Consumer<List<Double>> onStepRotated) {
        this.onStepRotated = onStepRotated == null ? rotation -> {
        } : onStepRotated;
    }

    private Consumer<List<Double>> onStepRotated = rotation -> {
    };

    /**
     * Shows the arrows to move the step played alone (on, in place of the rings) or hides them; a move dragged there
     * goes to {@link #setOnStepTranslated}.
     */
    public void setTranslationTool(boolean on) {
        viewer.setTranslationTool(on ? 0 : -1);
    }

    /** What runs when the step played alone was moved in the viewer (its new offset in metres). */
    public void setOnStepTranslated(Consumer<List<Double>> onStepTranslated) {
        this.onStepTranslated = onStepTranslated == null ? translation -> {
        } : onStepTranslated;
    }

    private Consumer<List<Double>> onStepTranslated = translation -> {
    };

    /** Lets a looping step end its round and the rest play once. */
    public void finish() {
        viewer.finishAnimation();
    }

    /** Stops the animation and puts her back at rest. */
    public void stop() {
        viewer.stopAnimation();
        setPaused(false);
        markStep(-1, 0);
    }

    /** The viewer, for the changes to the clip playing (loop, speed, in place). */
    public ModelViewer getViewer() {
        return viewer;
    }

    /** The choice of the body. */
    public Select<Long> getBodySelect() {
        return bodySelect;
    }

    private void setPaused(boolean paused) {
        this.paused = paused;
        viewer.setAnimationPaused(paused);
        pauseButton.setIcon(paused ? Solid.PLAY.create() : Solid.PAUSE.create());
        pauseButton.setTooltipText(loc.getValue(paused ? J_L.LABEL_PLAY : J_L.LABEL_PAUSE));
    }

    private Body body() {
        return bodySelect.getValue() == null ? null : bodies.get(bodySelect.getValue());
    }

    private void showBody() {
        try {
            Body body = body();
            Model model = body == null || body.getModel() == null ? null : modelService.find(body.getModel().getId());
            File file = model == null ? null : modelService.bodyFile(model);
            if (file == null) {
                viewer.clear();
            } else {
                info.setText("");
                viewer.show(file, modelService.bodyFormat(model), null);
            }
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void showBodyState() {
        try {
            Body body = body();
            if (body != null && body.getModel() != null) {
                Model model = modelService.find(body.getModel().getId());
                viewer.setToggles(AppearancePanel.toggles(model, BodyState.toggles(model, body.partStateMap())));
            }
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }
}
