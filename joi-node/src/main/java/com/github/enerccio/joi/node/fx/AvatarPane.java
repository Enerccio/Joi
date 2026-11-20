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
 * her state and the animation over it; without Chromium, only those, and Open in browser shows her body.
 */
class AvatarPane extends BorderPane {

    private final Map<String, String> names;
    private final RendererServer renderer;
    private final Label state = new Label("…");
    private final Label animation = new Label();
    private Runnable onAnimationEnded = () -> {
    };
    /** The animation that plays now and does not loop, -1 for none. */
    private long waiting = -1;
    private ChromiumPane page;

    /**
     * @param chromium the client to show the page with, null for none
     */
    AvatarPane(Map<String, String> names, RendererServer renderer, org.cef.CefClient chromium, Consumer<String> browse) {
        this.names = names;
        this.renderer = renderer;
        renderer.setOnEnded((id, problem) -> Platform.runLater(() -> ended(id, problem)));
        VBox shown = new VBox(4, state, animation);
        StackPane stage = new StackPane();
        stage.setStyle("-fx-background-color: #111114;");
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
        HBox buttons = new HBox(8, open);
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
