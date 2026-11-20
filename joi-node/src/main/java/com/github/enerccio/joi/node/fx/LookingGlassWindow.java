package com.github.enerccio.joi.node.fx;

import com.github.enerccio.joi.node.LookingGlassBridge;
import javafx.collections.ListChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.cef.CefClient;

import java.io.IOException;

/**
 * Her body on a Looking Glass: a window of its own, full screen on the display, showing the renderer page that renders
 * for it ({@code RendererServer#lookingGlassUrl}) by Chromium off screen ({@link ChromiumPane}), at the display's own
 * pixels (the lenses need every pixel where it is). The page is only made once the window is on the display at its
 * scale: one made at another screen's scale would render twice the pixels. (A windowed Chromium in an AWT frame showed
 * only black while JavaFX runs the app, so it is off screen here too.) Call it on the UI thread.
 */
final class LookingGlassWindow {

    private final Stage stage;
    private final ChromiumPane page;
    private Runnable onClosed = () -> {
    };
    private boolean closing;
    /** Closes the window when its screen goes away (the display was unplugged). */
    private ListChangeListener<Screen> screens;

    private LookingGlassWindow(Stage stage, ChromiumPane page) {
        this.stage = stage;
        this.page = page;
    }

    /**
     * Opens the window on the display.
     *
     * @throws IOException when the display is not among the screens, or it is not shown at its own pixels
     */
    static LookingGlassWindow open(LookingGlassBridge.Device device, CefClient chromium, String url) throws IOException {
        Screen screen = screen(device);
        Rectangle2D bounds = screen.getBounds();
        ChromiumPane page = new ChromiumPane(chromium, url);
        StackPane root = new StackPane();
        root.setStyle("-fx-background-color: black;");
        Stage stage = new Stage(StageStyle.UNDECORATED);
        stage.setTitle("Joi on the Looking Glass");
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        stage.setWidth(bounds.getWidth());
        stage.setHeight(bounds.getHeight());
        stage.setScene(new Scene(root, bounds.getWidth(), bounds.getHeight(), Color.BLACK));
        stage.setFullScreenExitHint("");
        stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);
        Runnable place = () -> {
            if (root.getChildren().isEmpty() && stage.getOutputScaleX() == screen.getOutputScaleX()
                    && screen.getBounds().contains(stage.getX() + 1, stage.getY() + 1)) {
                root.getChildren().add(page);
            }
        };
        stage.outputScaleXProperty().addListener((o, a, b) -> place.run());
        stage.xProperty().addListener((o, a, b) -> place.run());
        stage.setOnShown(e -> place.run());
        LookingGlassWindow window = new LookingGlassWindow(stage, page);
        stage.setOnHidden(e -> window.closed());
        window.screens = change -> {
            if (!Screen.getScreens().contains(screen)) {
                stage.close();
            }
        };
        Screen.getScreens().addListener(window.screens);
        stage.show();
        // full screen hides the menu bar, which would cover the top of her views
        stage.setFullScreen(true);
        return window;
    }

    /** The screen that is the display: where Bridge says, at its pixels. */
    private static Screen screen(LookingGlassBridge.Device device) throws IOException {
        Screen found = null;
        for (Screen screen : Screen.getScreens()) {
            Rectangle2D bounds = screen.getBounds();
            boolean pixels = Math.round(bounds.getWidth() * screen.getOutputScaleX()) == device.width()
                    && Math.round(bounds.getHeight() * screen.getOutputScaleY()) == device.height();
            if (pixels && bounds.contains(device.x() + 1, device.y() + 1)) {
                return screen;
            }
            if (pixels && found == null) {
                found = screen;
            }
        }
        if (found == null) {
            throw new IOException("The Looking Glass " + device.serial() + " is not among the screens at "
                    + device.width() + "×" + device.height() + " (is it set to its own resolution?)");
        }
        return found;
    }

    /** Called once the window closed, by {@link #close} or not (the display went away). */
    void setOnClosed(Runnable onClosed) {
        this.onClosed = onClosed;
    }

    void close() {
        closing = true;
        stage.setFullScreen(false);
        stage.close();
    }

    /** Whether it was closed by {@link #close} (else it went away by itself). */
    boolean closedOnPurpose() {
        return closing;
    }

    private void closed() {
        Screen.getScreens().removeListener(screens);
        page.close();
        onClosed.run();
    }
}
