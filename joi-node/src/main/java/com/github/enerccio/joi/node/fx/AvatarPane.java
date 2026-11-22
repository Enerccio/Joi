package com.github.enerccio.joi.node.fx;

import com.github.enerccio.joi.node.AvatarDirector;
import com.github.enerccio.joi.node.renderer.RendererServer;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Where her body is shown: the renderer page ({@link RendererServer}) plays what the {@link AvatarDirector} asks and
 * tells when an animation that does not loop is over. The page is shown here by Chromium ({@link ChromiumPane}), with
 * her state and the animation over it; without Chromium, only those, and Open in browser shows her body. On the
 * Looking Glass button she moves onto the Looking Glass ({@link LookingGlassWindow}, opened by the app): the page here
 * closes meanwhile (one renderer at a time) and comes back with her.
 */
class AvatarPane extends BorderPane {

    /** The names of her animations by id; replaced when she moves into another body. */
    private volatile Map<String, String> names;
    private final RendererServer renderer;
    private final Label state = new Label("…");
    private final Label animation = new Label();
    private Runnable onAnimationEnded = () -> {
    };
    /** The animation that plays now and does not loop, -1 for none. */
    private long waiting = -1;
    private ChromiumPane page;
    private final org.cef.CefClient chromium;
    private final StackPane stage = new StackPane();
    /** Shown instead of the page while she is on the Looking Glass. */
    private javafx.scene.Node away = new Label("She is on the Looking Glass");
    private final javafx.scene.control.ToggleButton lookingGlass = new javafx.scene.control.ToggleButton("On the Looking Glass");
    private Consumer<Boolean> onLookingGlass = on -> {
    };

    /** Her animations' names, of the body she is in now. */
    void names(Map<String, String> names) {
        this.names = names;
    }

    /** Offline the user can't move her onto the Looking Glass: the server could not be told, and she could not react. */
    void setOnline(boolean online) {
        lookingGlass.setDisable(!online || chromium == null);
    }

    /**
     * @param chromium the client to show the page with, null for none
     */
    AvatarPane(Map<String, String> names, RendererServer renderer, org.cef.CefClient chromium, Consumer<String> browse) {
        this.names = names;
        this.renderer = renderer;
        this.chromium = chromium;
        renderer.setOnEnded((id, problem) -> Platform.runLater(() -> ended(id, problem)));
        VBox shown = new VBox(4, state, animation);
        stage.setStyle("-fx-background-color: #111114;");
        away.setStyle("-fx-font-size: 18px; -fx-text-fill: #aaa;");
        StackPane.setAlignment(shown, Pos.TOP_LEFT);
        if (chromium != null) {
            page = new ChromiumPane(chromium, renderer.url().toString());
            stage.getChildren().add(page);
            shown.setAlignment(Pos.TOP_LEFT);
            shown.setPickOnBounds(false);
            shown.setStyle("-fx-padding: 8;");
            state.setStyle("-fx-font-size: 13px; -fx-text-fill: #ddd;");
        } else {
            shown.setAlignment(Pos.CENTER);
            state.setStyle("-fx-font-size: 24px; -fx-text-fill: white;");
        }
        stage.getChildren().add(shown);
        animation.setStyle("-fx-text-fill: #aaa;");
        Button open = new Button("Open in browser");
        open.setOnAction(e -> browse.accept(renderer.url().toString()));
        lookingGlass.setDisable(chromium == null);
        lookingGlass.setOnAction(e -> {
            // the app says where she is once it tried
            boolean wanted = lookingGlass.isSelected();
            lookingGlass.setSelected(!wanted);
            onLookingGlass.accept(wanted);
        });
        HBox buttons = new HBox(8, lookingGlass, open);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        setCenter(stage);
        setBottom(buttons);
    }

    /** Closes the page. */
    void close() {
        if (page != null) {
            page.close();
        }
    }

    /** The user wants her on the Looking Glass (true) or back here (false). */
    void setOnLookingGlass(Consumer<Boolean> onLookingGlass) {
        this.onLookingGlass = onLookingGlass;
    }

    /** What is shown instead of the page while she is on the Looking Glass (the tuning panel). */
    void setLookingGlassPanel(javafx.scene.Node panel) {
        if (page == null && stage.getChildren().getFirst() == away) {
            stage.getChildren().set(0, panel);
        }
        away = panel;
    }

    /** She is on the Looking Glass now (the page here closes), or back here. */
    void lookingGlass(boolean on) {
        lookingGlass.setSelected(on);
        if (chromium == null || on == (page == null)) {
            return;
        }
        if (on) {
            page.close();
            stage.getChildren().set(0, away);
            page = null;
        } else {
            page = new ChromiumPane(chromium, renderer.url().toString());
            stage.getChildren().set(0, page);
        }
    }

    void setOnAnimationEnded(Runnable onAnimationEnded) {
        this.onAnimationEnded = onAnimationEnded;
    }

    /** Her mood and its face, as the server says it ({@code mood} frame): the page shows it on her face. */
    void mood(com.github.enerccio.joi.protocol.node.MoodState mood) {
        renderer.mood(mood);
    }

    void state(AvatarDirector.State next) {
        // her eyes follow what she does: shut while she sleeps (slowly as she falls asleep), open again as she wakes up
        switch (next) {
            case SLEEPING -> renderer.eyes("closed", 0.4);
            case FALLING_ASLEEP -> renderer.eyes("closed", 3);
            case WAKING_UP -> renderer.eyes("auto", 1.5);
            default -> renderer.eyes("auto", 0.25);
        }
        state.setText(switch (next) {
            case IDLE -> "waiting";
            case SLEEPING -> "asleep";
            case WAKING_UP -> "waking up";
            case TALKING -> "talking";
            case FALLING_ASLEEP -> "falling asleep";
        });
    }

    /** A piece of her voice is coming: the page reads her mouth from it. */
    void speech(long number, String base64) {
        renderer.speech(number, base64);
    }

    /** The piece plays now. */
    void speechStarted(long number) {
        renderer.speechStarted(number);
    }

    /** Her speech is over or stopped. */
    void speechStopped() {
        renderer.speechStopped();
    }

    void play(String id, boolean loop) {
        animation.setText(id == null ? "(rest pose)" : names.getOrDefault(id, id) + (loop ? " ⟲" : ""));
        long played = renderer.play(id, loop);
        waiting = loop ? -1 : played;
    }

    private void ended(long id, String problem) {
        if (id != waiting) {
            return;
        }
        waiting = -1;
        if (problem != null) {
            animation.setText(animation.getText() + " (" + problem + ")");
        }
        onAnimationEnded.run();
    }
}
