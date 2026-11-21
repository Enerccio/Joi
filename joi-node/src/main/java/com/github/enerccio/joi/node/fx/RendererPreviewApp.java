package com.github.enerccio.joi.node.fx;

import com.github.enerccio.joi.node.NodeSettings;
import com.github.enerccio.joi.node.renderer.BodyFiles;
import com.github.enerccio.joi.node.renderer.RendererServer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Her body in Chromium in a window, from the node's cache, without a server: for development. Shows the newest cached
 * {@code .joim} and {@code .joia}, and loops the animation of uuid {@code JOI_PREVIEW_PLAY} when set; with
 * {@code JOI_PREVIEW_SHOT}, saves the pane's frame there as a PNG after 15 s.
 * <p>
 * {@code mvn -o -Pfx compile exec:exec@run -pl joi-node -Dnode.args=preview}
 */
public class RendererPreviewApp extends Application {

    private RendererServer renderer;
    private ChromiumPane pane;

    @Override
    public void start(Stage stage) throws Exception {
        NodeSettings settings = NodeSettings.standard(getParameters().getRaw().toArray(String[]::new));
        renderer = new RendererServer();
        renderer.show(BodyFiles.unpack(newest(settings.cache(), ".joim"), newest(settings.cache(), ".joia"),
                settings.cache().resolve("unpacked")));
        String play = System.getenv("JOI_PREVIEW_PLAY");
        if (play != null) {
            renderer.play(play, true);
        }
        System.out.println(renderer.url());
        Thread.ofPlatform().daemon().start(() -> {
            try {
                var client = Chromium.client(settings.dir());
                Platform.runLater(() -> {
                    pane = new ChromiumPane(client, renderer.url().toString());
                    stage.setScene(new Scene(pane, 600, 800));
                    stage.setTitle("Joi renderer");
                    stage.show();
                    String shot = System.getenv("JOI_PREVIEW_SHOT");
                    if (shot != null) {
                        javafx.animation.PauseTransition later = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(15));
                        later.setOnFinished(e -> pane.saveFrame(Path.of(shot)));
                        later.play();
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                Platform.exit();
            }
        });
    }

    @Override
    public void stop() {
        if (pane != null) {
            pane.close();
        }
        renderer.close();
        Chromium.dispose();
        Runtime.getRuntime().halt(0);
    }

    private static Path newest(Path dir, String extension) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> file.getFileName().toString().endsWith(extension))
                    .max(Comparator.comparing(file -> file.toFile().lastModified())).orElse(null);
        }
    }
}
