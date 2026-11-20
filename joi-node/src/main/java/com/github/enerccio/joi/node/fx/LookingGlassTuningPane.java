package com.github.enerccio.joi.node.fx;

import com.github.enerccio.joi.node.renderer.LookingGlassTuning;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.ToDoubleFunction;

/**
 * Shown in her pane while she is on the Looking Glass: how she is rendered there ({@link LookingGlassTuning}), changed
 * live; each change goes to the page on the display and is kept.
 */
final class LookingGlassTuningPane extends GridPane {

    private final Consumer<LookingGlassTuning> onChange;
    private LookingGlassTuning tuning;
    private boolean setting;
    private final Runnable[] refresh = new Runnable[16];
    private int refreshes;

    LookingGlassTuningPane(LookingGlassTuning start, Consumer<LookingGlassTuning> onChange) {
        this.tuning = start == null ? new LookingGlassTuning() : start.copy();
        this.onChange = onChange;
        setHgap(8);
        setVgap(6);
        setPadding(new Insets(36, 12, 12, 12));
        setMaxWidth(460);
        setStyle("-fx-background-color: #111114;");
        ColumnConstraints names = new ColumnConstraints();
        ColumnConstraints controls = new ColumnConstraints();
        controls.setHgrow(Priority.ALWAYS);
        getColumnConstraints().addAll(names, controls, new ColumnConstraints(56));

        Label title = new Label("She is on the Looking Glass: tune how she is shown there");
        title.setStyle("-fx-text-fill: #ddd; -fx-font-size: 14px;");
        add(title, 0, 0, 3, 1);
        int row = 1;
        row = slider(row, "Depth", 0, 1.5, "%.2f", t -> t.depthiness, v -> tuning.depthiness = v);
        row = slider(row, "Focus (m)", -1, 1, "%+.2f", t -> t.focus, v -> tuning.focus = v);
        row = slider(row, "Zoom", 0.5, 2.5, "%.2f", t -> t.zoom, v -> tuning.zoom = v);
        row = slider(row, "Center", -0.5, 0.5, "%+.3f", t -> t.center, v -> tuning.center = v);
        row = slider(row, "Pitch", 0.98, 1.02, "%.4f", t -> t.pitch, v -> tuning.pitch = v);
        row = slider(row, "Max fps", 5, 60, "%.0f", t -> t.fps, v -> tuning.fps = (int) Math.round(v));

        CheckBox invert = new CheckBox("Views the other way round");
        CheckBox flipY = new CheckBox("Lenses from the top");
        invert.setOnAction(e -> {
            tuning.invert = invert.isSelected();
            changed();
        });
        flipY.setOnAction(e -> {
            tuning.flipY = flipY.isSelected();
            changed();
        });
        styled(invert);
        styled(flipY);
        add(invert, 1, row++, 2, 1);
        add(flipY, 1, row++, 2, 1);
        refresh(() -> {
            invert.setSelected(tuning.invert);
            flipY.setSelected(tuning.flipY);
        });

        ChoiceBox<Integer> quilt = new ChoiceBox<>();
        Arrays.stream(LookingGlassTuning.QUILTS).forEach(quilt.getItems()::add);
        quilt.setOnAction(e -> {
            if (quilt.getValue() != null) {
                tuning.quilt = quilt.getValue();
                changed();
            }
        });
        add(label("Quilt (px)"), 0, row);
        add(quilt, 1, row++);
        ChoiceBox<String> view = new ChoiceBox<>();
        view.getItems().addAll(LookingGlassTuning.VIEWS);
        view.setOnAction(e -> {
            if (view.getValue() != null) {
                tuning.view = view.getValue();
                changed();
            }
        });
        add(label("Show"), 0, row);
        add(view, 1, row++);
        refresh(() -> {
            quilt.setValue(tuning.quilt);
            view.setValue(tuning.view);
        });

        Button reset = new Button("Defaults");
        reset.setOnAction(e -> {
            tuning = new LookingGlassTuning();
            show();
            changed();
        });
        add(reset, 1, row);
        show();
    }

    private int slider(int row, String name, double min, double max, String format, ToDoubleFunction<LookingGlassTuning> get,
                       DoubleConsumer set) {
        Slider slider = new Slider(min, max, min);
        Label shown = label("");
        slider.valueProperty().addListener((o, a, b) -> {
            shown.setText(String.format(Locale.ROOT, format, b.doubleValue()));
            if (!setting) {
                set.accept(b.doubleValue());
                changed();
            }
        });
        add(label(name), 0, row);
        add(slider, 1, row);
        add(shown, 2, row);
        refresh(() -> slider.setValue(get.applyAsDouble(tuning)));
        return row + 1;
    }

    private void refresh(Runnable runnable) {
        refresh[refreshes++] = runnable;
    }

    /** The controls show the tuning. */
    private void show() {
        setting = true;
        try {
            for (int i = 0; i < refreshes; i++) {
                refresh[i].run();
            }
        } finally {
            setting = false;
        }
    }

    private void changed() {
        if (!setting) {
            onChange.accept(tuning.copy());
        }
    }

    private static Label label(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: #bbb;");
        return label;
    }

    private static void styled(CheckBox box) {
        box.setStyle("-fx-text-fill: #bbb;");
    }
}
