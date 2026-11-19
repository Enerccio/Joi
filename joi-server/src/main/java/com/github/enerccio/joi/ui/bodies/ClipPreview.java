package com.github.enerccio.joi.ui.bodies;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
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
import java.util.function.Consumer;

/**
 * The 3D preview of animation clips (the clip and pack dialogs): the {@link ModelViewer} showing a body of the user's
 * assistants in the state of its parts, the choice of that body, and Pause and Stop. A clip played goes on playing when
 * the body changes. The body chosen last is the next preview's, for the session.
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
    private boolean paused;
    private Consumer<Long> onBodyChosen = id -> {
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

        pauseButton = new Button(Solid.PAUSE.create(), e -> setPaused(!paused));
        pauseButton.setTooltipText(loc.getValue(J_L.LABEL_PAUSE));
        Button stopButton = new Button(Solid.STOP.create(), e -> stop());
        stopButton.setTooltipText(loc.getValue(L.LABEL_STOP));
        info.addClassName(CssStyles.HINT_TEXT);
        HorizontalLayout header = new HorizontalLayout(bodySelect, pauseButton, stopButton, info);
        header.setAlignItems(FlexComponent.Alignment.BASELINE);
        header.setWrap(true);

        viewer.setSizeFull();
        viewer.addInfoListener(e -> showBodyState());
        viewer.addErrorListener(e -> info.setText(e.getMessage()));
        viewer.addAnimationStartedListener(e -> info.setText(""));
        viewer.addAnimationErrorListener(e -> info.setText(e.getMessage()));

        add(header, viewer);
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

    /** Plays the clip as it is set (loop, speed, in place); null stops. */
    public void play(Animation clip) {
        try {
            File file = clip == null ? null : animationService.file(clip);
            if (file == null) {
                stop();
                return;
            }
            setPaused(false);
            viewer.playAnimation(file, clip.loops(), clip.speedOrDefault(), clip.playsInPlace());
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** Stops the clip and puts her back at rest. */
    public void stop() {
        viewer.stopAnimation();
        setPaused(false);
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
