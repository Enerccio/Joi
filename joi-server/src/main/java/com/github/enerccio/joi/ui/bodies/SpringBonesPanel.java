package com.github.enerccio.joi.ui.bodies;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.bodies.SpringBones;
import com.github.enerccio.joi.domain.bodies.SpringChain;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.widgets.Notification;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The spring bones of a model, a section of the model dialog: the chains of bones that swing (guessed from the bones'
 * names at the conversion), each with a box, how it moves and its bones; a chain can be started from another bone, the
 * guess made again, and the chains applied to the converted VRM. A box sways the preview to see them move.
 */
@Configurable
public class SpringBonesPanel {

    private static final Map<SpringChain.Preset, J_L> PRESETS = Map.of(
            SpringChain.Preset.HAIR, J_L.LABEL_SPRING_PRESET_HAIR,
            SpringChain.Preset.TAIL, J_L.LABEL_SPRING_PRESET_TAIL,
            SpringChain.Preset.EARS, J_L.LABEL_SPRING_PRESET_EARS,
            SpringChain.Preset.CLOTH, J_L.LABEL_SPRING_PRESET_CLOTH,
            SpringChain.Preset.STIFF, J_L.LABEL_SPRING_PRESET_STIFF);

    @Autowired
    private Localization loc;

    @Autowired
    private ModelService modelService;

    private final Supplier<Model> currentModel;
    private final Consumer<Model> stored;
    private final Consumer<Model> applied;
    private final ModelViewer viewer;

    private VerticalLayout layout;
    private Span hint;
    private VerticalLayout rows;
    private ComboBox<String> add;
    private Button guessButton;
    private Button applyButton;
    private Span status;
    private Checkbox sway;

    private SpringBones bones;
    private List<SpringChain> chains = new ArrayList<>();

    /**
     * @param currentModel the model of the dialog
     * @param stored       takes the model once the chains are stored on it (the dialog keeps it)
     * @param applied      takes the model once the chains are written into its VRM (the dialog keeps it and shows the
     *                     new file)
     */
    public SpringBonesPanel(Supplier<Model> currentModel, Consumer<Model> stored, Consumer<Model> applied, ModelViewer viewer) {
        this.currentModel = currentModel;
        this.stored = stored;
        this.applied = applied;
        this.viewer = viewer;
    }

    public Component create() {
        H4 title = new H4(loc.getValue(J_L.LABEL_SPRING_BONES));
        hint = new Span();
        hint.addClassName(CssStyles.HINT_TEXT);
        rows = new VerticalLayout();
        rows.setPadding(false);
        rows.setSpacing(false);

        add = new ComboBox<>(loc.getValue(J_L.LABEL_SPRING_ADD));
        add.setWidthFull();
        add.setClearButtonVisible(true);
        add.addValueChangeListener(e -> {
            if (e.isFromClient() && e.getValue() != null) {
                chains.add(new SpringChain(e.getValue(), bones.presetFor(e.getValue()), true));
                changed();
            }
        });

        guessButton = new Button(loc.getValue(J_L.LABEL_SPRING_GUESS), Solid.MAGIC.create(), e -> {
            chains = new ArrayList<>(bones.guess());
            changed();
        });
        applyButton = new Button(loc.getValue(J_L.LABEL_SPRING_APPLY), Solid.CHECK.create(), e -> apply());
        applyButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        sway = new Checkbox(loc.getValue(J_L.LABEL_SPRING_SWAY));
        sway.addValueChangeListener(e -> viewer.setSway(e.getValue()));
        HorizontalLayout buttons = new HorizontalLayout(guessButton, applyButton, sway);
        buttons.setAlignItems(FlexComponent.Alignment.BASELINE);
        buttons.addClassName(JoiCssStyles.SPRING_CHAIN);
        status = new Span();
        status.addClassName(CssStyles.HINT_TEXT);

        layout = new VerticalLayout(title, hint, rows, add, buttons, status);
        layout.setPadding(false);
        layout.setVisible(false);
        return layout;
    }

    /** Shows the chains of the model; hidden when it has no converted VRM 1.0. */
    public void show() {
        Model model = currentModel.get();
        bones = null;
        try {
            File vrm = model.getId() == null ? null : modelService.convertedFile(model);
            if (vrm == null) {
                layout.setVisible(false);
                return;
            }
            layout.setVisible(true);
            try {
                bones = SpringBones.read(vrm);
            } catch (IOException e) {
                showUnavailable(String.format(loc.getValue(J_L.MSG_SPRING_READ_ERROR), e.getMessage()));
                return;
            }
            if (!bones.isVrm1()) {
                showUnavailable(loc.getValue(J_L.MSG_SPRING_BONES_UNAVAILABLE));
                return;
            }
            List<SpringChain> stored = SpringChain.listFromJson(model.getSpringChains());
            chains = new ArrayList<>(stored == null ? bones.guess() : stored);
            render();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void showUnavailable(String text) {
        hint.setText(text);
        rows.removeAll();
        for (Component component : List.of(add, guessButton, applyButton, sway, status)) {
            component.setVisible(false);
        }
    }

    private void render() {
        hint.setText(loc.getValue(chains.isEmpty() ? J_L.MSG_SPRING_BONES_NONE : J_L.MSG_SPRING_BONES_HINT));
        for (Component component : List.of(add, guessButton, applyButton, sway, status)) {
            component.setVisible(true);
        }
        rows.removeAll();
        for (SpringChain chain : chains) {
            rows.add(row(chain));
        }
        List<String> roots = chains.stream().map(SpringChain::root).toList();
        add.setItems(bones.candidates().stream().filter(bone -> !roots.contains(bone)).toList());
        add.clear();
        boolean current = Objects.equals(SpringChain.toJson(chains), currentModel.get().getAppliedSpringChains());
        status.setText(loc.getValue(current ? J_L.MSG_SPRING_APPLIED : J_L.MSG_SPRING_NOT_APPLIED));
    }

    private Component row(SpringChain chain) {
        Checkbox enabled = new Checkbox(chain.root(), chain.enabled());
        enabled.addValueChangeListener(e -> replace(chain, chain.withEnabled(e.getValue())));
        Select<SpringChain.Preset> preset = new Select<>();
        preset.setItems(SpringChain.Preset.values());
        preset.setItemLabelGenerator(p -> loc.getValue(PRESETS.get(p)));
        preset.setValue(chain.preset());
        preset.addValueChangeListener(e -> {
            if (e.getValue() != null) {
                replace(chain, chain.withPreset(e.getValue()));
            }
        });
        Span count = new Span(String.format(loc.getValue(J_L.MSG_SPRING_BONE_COUNT), bones.bones(chain, chains)));
        count.addClassName(CssStyles.HINT_TEXT);
        Button remove = new Button(Solid.TIMES.create(), e -> {
            chains.remove(chain);
            changed();
        });
        remove.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        remove.setTooltipText(loc.getValue(L.LABEL_DELETE));
        HorizontalLayout row = new HorizontalLayout(enabled, preset, count, remove);
        row.setWidthFull();
        row.addClassName(JoiCssStyles.SPRING_CHAIN);
        return row;
    }

    private void replace(SpringChain before, SpringChain after) {
        int index = chains.indexOf(before);
        if (index >= 0) {
            chains.set(index, after);
            changed();
        }
    }

    /** Keeps the chains on the model (not yet in its VRM) and shows them anew. */
    private void changed() {
        try {
            Model model = modelService.find(currentModel.get().getId());
            model.setSpringChains(SpringChain.toJson(chains));
            stored.accept(modelService.save(model));
            render();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** Writes the chains into the converted VRM; the preview shows the new file. */
    private void apply() {
        try {
            Model model = currentModel.get();
            model.setSpringChains(SpringChain.toJson(chains));
            applied.accept(modelService.applySprings(model));
            render();
        } catch (IOException | IllegalStateException e) {
            Notification.error(e.getMessage());
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** Whether the section is shown (the model has a converted VRM). */
    public boolean isShown() {
        return layout.isVisible();
    }

    /** The chains shown, for tests. */
    public List<SpringChain> getChains() {
        return List.copyOf(chains);
    }

    /** The box that switches the chain, for tests. */
    public Checkbox getChainBox(String root) {
        return rows.getChildren().map(row -> (Checkbox) row.getChildren().findFirst().orElseThrow())
                .filter(box -> box.getLabel().equals(root)).findFirst().orElse(null);
    }

    /** The choice of how the chain moves, for tests. */
    @SuppressWarnings("unchecked")
    public Select<SpringChain.Preset> getPresetSelect(String root) {
        return rows.getChildren().filter(row -> ((Checkbox) row.getChildren().findFirst().orElseThrow()).getLabel().equals(root))
                .map(row -> (Select<SpringChain.Preset>) row.getChildren().skip(1).findFirst().orElseThrow()).findFirst().orElse(null);
    }

    public ComboBox<String> getAddBox() {
        return add;
    }

    public Button getApplyButton() {
        return applyButton;
    }

    public Button getGuessButton() {
        return guessButton;
    }

    public Span getStatus() {
        return status;
    }
}
