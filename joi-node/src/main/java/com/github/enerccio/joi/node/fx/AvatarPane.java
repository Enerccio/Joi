package com.github.enerccio.joi.node.fx;

import com.github.enerccio.joi.node.AvatarDirector;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.Map;

/**
 * Where her body is shown, with Pop out (a window of its own) and Fullscreen (that window over the whole display: the
 * Looking Glass). A stub: it shows her state and the animation she would play; the three.js renderer comes once its
 * host is chosen (JavaFX's WebView has no WebGL). A non-looping animation "ends" after a moment, so the
 * {@link AvatarDirector} goes on.
 */
class AvatarPane extends BorderPane {

    /** How long a non-looping animation takes here, without a renderer. */
    private static final Duration ANIMATION = Duration.seconds(2);

    private final Map<String, String> names;
    private final Label state = new Label("…");
    private final Label animation = new Label();
    private final StackPane stage = new StackPane();
    private final PauseTransition ending = new PauseTransition(ANIMATION);
    private Runnable onAnimationEnded = () -> {
    };
    private Stage window;

    AvatarPane(Map<String, String> names) {
        this.names = names;
        state.setStyle("-fx-font-size: 24px;");
        VBox shown = new VBox(8, state, animation);
        shown.setAlignment(Pos.CENTER);
        stage.getChildren().add(shown);
        stage.setStyle("-fx-background-color: #111; -fx-text-fill: white;");
        state.setStyle("-fx-font-size: 24px; -fx-text-fill: white;");
        animation.setStyle("-fx-text-fill: #aaa;");
        Button popOut = new Button("Pop out");
        popOut.setOnAction(e -> popOut(false));
        Button fullscreen = new Button("Fullscreen");
        fullscreen.setOnAction(e -> popOut(true));
        HBox buttons = new HBox(8, popOut, fullscreen);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        setCenter(stage);
        setBottom(buttons);
        ending.setOnFinished(e -> onAnimationEnded.run());
    }

    void setOnAnimationEnded(Runnable onAnimationEnded) {
        this.onAnimationEnded = onAnimationEnded;
    }

    void state(AvatarDirector.State next) {
        state.setText(switch (next) {
            case IDLE -> "waiting";
            case SLEEPING -> "asleep";
            case WAKING_UP -> "waking up";
            case TALKING -> "talking";
            case FALLING_ASLEEP -> "falling asleep";
        });
    }

    void play(String id, boolean loop) {
        ending.stop();
        animation.setText(id == null ? "(rest pose)" : names.getOrDefault(id, id) + (loop ? " ⟲" : ""));
        if (!loop) {
            ending.playFromStart();
        }
    }

    /** Her body in a window of its own, over the whole display when asked; closing it brings her back here. */
    private void popOut(boolean full) {
        if (window == null) {
            window = new Stage();
            window.setTitle("Joi");
            setCenter(new Label("(in its own window)"));
            window.setScene(new Scene(stage, 800, 800));
            window.setOnHidden(e -> {
                window = null;
                setCenter(stage);
            });
        }
        window.setFullScreen(full);
        window.show();
    }
}
