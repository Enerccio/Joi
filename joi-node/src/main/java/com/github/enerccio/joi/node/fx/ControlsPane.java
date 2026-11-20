package com.github.enerccio.joi.node.fx;

import com.github.enerccio.joi.protocol.http.BodyPart;
import com.github.enerccio.joi.protocol.http.BodyPartState;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The panel between the chat and her body: the node's controls in general, in sections ({@link #section}). The first
 * is her parts: what she wears and carries, which the user takes off and puts on here (a part of one toggle is a box,
 * a part of several states a choice). A change goes to {@code onSwitch}: the app holds it back with the others, tells
 * the server, which keeps it and lets her react, and shows it on her body once it is kept. Until then the control shows
 * what the user chose, and while a batch waits for the server the parts are locked ({@link #lockParts}); what is not
 * kept is shown as it is kept again ({@link #showPart}).
 */
final class ControlsPane extends ScrollPane {

    private final VBox sections = new VBox(14);
    private BiConsumer<BodyPart, BodyPartState> onSwitch = (part, state) -> {
    };
    private final Map<String, Consumer<String>> setters = new HashMap<>();
    private VBox partsBox;
    private boolean quiet;

    ControlsPane() {
        sections.setPadding(new Insets(12));
        setContent(sections);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setMinWidth(160);
    }

    /** Called with the part and the state the user put it in. */
    void setOnSwitch(BiConsumer<BodyPart, BodyPartState> onSwitch) {
        this.onSwitch = onSwitch;
    }

    /** A section of controls under a heading, after the ones before it. */
    void section(String title, Node content) {
        Label heading = new Label(title);
        heading.setStyle("-fx-font-weight: bold;");
        sections.getChildren().add(new VBox(6, heading, content));
    }

    /**
     * Her parts, each in the state it is in: a part with the states shown and hidden is a box (unchecked: taken off),
     * another a choice of its states. A part the server gave no states for (an older server) is left out.
     */
    void parts(List<BodyPart> parts) {
        VBox box = new VBox(8);
        partsBox = box;
        setters.clear();
        boolean any = false;
        for (BodyPart part : parts == null ? List.<BodyPart>of() : parts) {
            if (part.states() == null || part.states().size() < 2) {
                continue;
            }
            any = true;
            box.getChildren().add(control(part));
        }
        if (!any) {
            Label none = new Label("Her body has no parts to take off or put on.");
            none.setWrapText(true);
            none.setStyle("-fx-opacity: 0.6;");
            box.getChildren().add(none);
        }
        section("Her parts", box);
    }

    /** Locks the parts (a batch waits for the server) or frees them. */
    void lockParts(boolean locked) {
        if (partsBox != null) {
            partsBox.setDisable(locked);
        }
    }

    /** Shows the part in the state, without it being a change of the user. */
    void showPart(String partId, String stateId) {
        Consumer<String> setter = setters.get(partId);
        if (setter != null && stateId != null) {
            quiet = true;
            try {
                setter.accept(stateId);
            } finally {
                quiet = false;
            }
        }
    }

    private Node control(BodyPart part) {
        BodyPartState shown = state(part, "shown");
        BodyPartState hidden = state(part, "hidden");
        if (part.states().size() == 2 && shown != null && hidden != null) {
            CheckBox on = new CheckBox(part.name());
            on.setSelected("shown".equals(part.state()));
            on.setOnAction(e -> {
                if (!quiet) {
                    onSwitch.accept(part, on.isSelected() ? shown : hidden);
                }
            });
            setters.put(part.id(), stateId -> on.setSelected("shown".equals(stateId)));
            return on;
        }
        ChoiceBox<BodyPartState> choice = new ChoiceBox<>();
        choice.getItems().setAll(part.states());
        choice.setConverter(new StringConverter<>() {
            @Override
            public String toString(BodyPartState state) {
                return state == null ? "" : state.name() + (state.isDefault() ? " (default)" : "");
            }

            @Override
            public BodyPartState fromString(String text) {
                return null;
            }
        });
        choice.setValue(state(part, part.state()));
        choice.valueProperty().addListener((o, before, now) -> {
            if (now != null && !quiet) {
                onSwitch.accept(part, now);
            }
        });
        setters.put(part.id(), stateId -> choice.setValue(state(part, stateId)));
        Label name = new Label(part.name());
        HBox row = new HBox(8, name, choice);
        HBox.setHgrow(name, Priority.ALWAYS);
        name.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private static BodyPartState state(BodyPart part, String id) {
        return part.states().stream().filter(state -> state.id().equals(id)).findFirst().orElse(null);
    }
}
