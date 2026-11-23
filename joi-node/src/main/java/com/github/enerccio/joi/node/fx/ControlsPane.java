package com.github.enerccio.joi.node.fx;

import com.github.enerccio.joi.protocol.http.BodyOutfit;
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
    /** The section of her parts, replaced in its place when she moves into another body. */
    private Node partsSection;
    private boolean quiet;
    private Consumer<BodyOutfit> onOutfit = outfit -> {
    };
    private ChoiceBox<BodyOutfit> outfitChoice;
    /** The section of her outfits, replaced in its place when she moves into another body. */
    private Node outfitsSection;

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

    /** Called with the outfit the user chose to put on. */
    void setOnOutfit(Consumer<BodyOutfit> onOutfit) {
        this.onOutfit = onOutfit;
    }

    /**
     * Her outfits, the one she wears chosen (none when no outfit fits what she wears now). The section is not there when the body
     * has none, and after the parts when it has.
     */
    void outfits(List<BodyOutfit> outfits) {
        Node next = null;
        outfitChoice = null;
        if (outfits != null && !outfits.isEmpty()) {
            ChoiceBox<BodyOutfit> choice = new ChoiceBox<>();
            choice.getItems().setAll(outfits);
            choice.setConverter(new StringConverter<>() {
                @Override
                public String toString(BodyOutfit outfit) {
                    return outfit == null ? "" : outfit.name();
                }

                @Override
                public BodyOutfit fromString(String text) {
                    return null;
                }
            });
            BodyOutfit worn = outfits.stream().filter(BodyOutfit::worn).findFirst().orElse(null);
            choice.setValue(worn);
            Label description = new Label(worn == null ? "" : text(worn.description()));
            description.setWrapText(true);
            description.setStyle("-fx-opacity: 0.6;");
            choice.valueProperty().addListener((o, before, now) -> {
                if (now != null && !quiet) {
                    description.setText(text(now.description()));
                    onOutfit.accept(now);
                }
            });
            outfitChoice = choice;
            Label heading = new Label("Her outfit");
            heading.setStyle("-fx-font-weight: bold;");
            next = new VBox(6, heading, choice, description);
        }
        int at = outfitsSection == null ? -1 : sections.getChildren().indexOf(outfitsSection);
        if (at >= 0 && next != null) {
            sections.getChildren().set(at, next);
        } else if (at >= 0) {
            sections.getChildren().remove(at);
        } else if (next != null) {
            int parts = partsSection == null ? -1 : sections.getChildren().indexOf(partsSection);
            sections.getChildren().add(parts < 0 ? sections.getChildren().size() : parts + 1, next);
        }
        outfitsSection = next;
    }

    /** Shows the outfit as the one she wears (null: none), without it being a change of the user. */
    void showOutfit(BodyOutfit outfit) {
        if (outfitChoice != null) {
            quiet = true;
            try {
                outfitChoice.setValue(outfit);
            } finally {
                quiet = false;
            }
        }
    }

    private static String text(String text) {
        return text == null ? "" : text;
    }

    /** A section of controls under a heading, after the ones before it. */
    void section(String title, Node content) {
        Label heading = new Label(title);
        heading.setStyle("-fx-font-weight: bold;");
        sections.getChildren().add(new VBox(6, heading, content));
    }

    /** Puts the section of her parts where the one before it was (after the others when there is none). */
    private void partsSection(Node content) {
        Label heading = new Label("Her parts");
        heading.setStyle("-fx-font-weight: bold;");
        Node next = new VBox(6, heading, content);
        int at = partsSection == null ? -1 : sections.getChildren().indexOf(partsSection);
        if (at < 0) {
            sections.getChildren().add(next);
        } else {
            sections.getChildren().set(at, next);
        }
        partsSection = next;
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
        partsSection(box);
    }

    /**
     * The choice of when a change of her parts is shown on her body: as soon as the server kept it, or when her
     * reaction to it comes. Its own section, so that it stays free while a batch of changes is locked.
     *
     * @param withReaction whether it is shown with her reaction now
     * @param onChange     told the choice the user made
     */
    void partsOption(boolean withReaction, Consumer<Boolean> onChange) {
        CheckBox option = new CheckBox("Show changes with her reaction");
        option.setWrapText(true);
        option.setSelected(withReaction);
        option.setOnAction(e -> onChange.accept(option.isSelected()));
        Label help = new Label("Off: the change shows as soon as the server has it. On: it shows when she reacts to it, "
                + "which takes a few seconds longer; her personality may also refuse it.");
        help.setWrapText(true);
        help.setStyle("-fx-opacity: 0.6;");
        section("Her parts: when to show", new VBox(4, option, help));
    }

    /** Locks the parts (a batch waits for the server) or frees them. */
    void lockParts(boolean locked) {
        if (partsBox != null) {
            partsBox.setDisable(locked);
        }
        // an outfit sets parts too: not while a batch of them waits
        if (outfitChoice != null) {
            outfitChoice.setDisable(locked);
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
