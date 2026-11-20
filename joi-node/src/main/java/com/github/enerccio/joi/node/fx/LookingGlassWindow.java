package com.github.enerccio.joi.node.fx;

import com.github.enerccio.joi.node.LookingGlassBridge;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Her body on a Looking Glass: the renderer page that renders for it ({@code RendererServer#lookingGlassUrl}), full
 * screen on the display in the user's own Chrome, an app window with a profile of the node's
 * ({@code looking-glass-chrome}). Chrome draws straight to the screen. Rendered off screen here (JCEF, as the pane),
 * every frame went through the CPU three times and held up the whole machine; and JCEF's own window shows nothing on
 * macOS (its view is placed inside Java's window, which draws over it). The display must be shown at its own pixels:
 * the lenses need every pixel where it is. Call it on the UI thread; its callback runs there too.
 */
final class LookingGlassWindow {

    /** Where Chrome is usually installed, when the node's settings don't say. */
    private static final List<String> BROWSERS = List.of(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            System.getProperty("user.home") + "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Chromium.app/Contents/MacOS/Chromium");

    private final Process process;
    private Runnable onClosed = () -> {
    };
    private boolean closing;
    private boolean quitting;
    /** Closes the window when its screen goes away (the display was unplugged). */
    private ListChangeListener<Screen> screens;
    private Thread hook;

    private LookingGlassWindow(Process process) {
        this.process = process;
    }

    /**
     * Opens the window on the display.
     *
     * @param browser Chrome's executable, null to look where it usually is
     * @throws IOException when there is no Chrome, the display is not among the screens at its own pixels, or Chrome
     *                     does not start
     */
    static LookingGlassWindow open(LookingGlassBridge.Device device, Path nodeDir, String browser, String url) throws IOException {
        Screen screen = screen(device);
        Rectangle2D bounds = screen.getBounds();
        List<String> command = new ArrayList<>();
        command.add(browser(browser));
        command.add("--user-data-dir=" + nodeDir.resolve("looking-glass-chrome"));
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        command.add("--disable-extensions");
        // nothing else running in it: no syncing, updates of its parts, translation or models of its own
        command.add("--disable-background-networking");
        command.add("--disable-component-update");
        command.add("--disable-sync");
        command.add("--disable-features=Translate,OptimizationHints,OptimizationGuideModelDownloading,MediaRouter");
        String debug = System.getenv("JOI_LOOKING_GLASS_DEBUG_PORT");
        if (debug != null) {
            command.add("--remote-debugging-port=" + Integer.parseInt(debug));
        }
        command.add("--window-position=" + Math.round(bounds.getMinX()) + "," + Math.round(bounds.getMinY()));
        command.add("--window-size=" + Math.round(bounds.getWidth()) + "," + Math.round(bounds.getHeight()));
        // full screen hides the menu bar, which would cover the top of her views
        command.add("--start-fullscreen");
        command.add("--app=" + url);
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(nodeDir.resolve("looking-glass-chrome.log").toFile()).start();
        LookingGlassWindow window = new LookingGlassWindow(process);
        window.screens = change -> {
            if (!Screen.getScreens().contains(screen)) {
                window.stop();
            }
        };
        Screen.getScreens().addListener(window.screens);
        // Chrome goes with the node when it is stopped, not only when it quits
        window.hook = new Thread(process::destroy, "Joi Looking Glass Chrome");
        Runtime.getRuntime().addShutdownHook(window.hook);
        process.onExit().thenRun(() -> Platform.runLater(window::closed));
        return window;
    }

    private static String browser(String configured) throws IOException {
        if (configured != null) {
            if (!Files.isExecutable(Path.of(configured))) {
                throw new IOException("The browser for the Looking Glass is not there: " + configured);
            }
            return configured;
        }
        for (String candidate : BROWSERS) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        throw new IOException("Google Chrome is not installed (it shows her on the Looking Glass; another Chrome or "
                + "Chromium can be set as lookingGlassBrowser in node.json)");
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

    /** Called once the window closed, by {@link #close} or not (the display went away, Chrome was quit). */
    void setOnClosed(Runnable onClosed) {
        this.onClosed = onClosed;
    }

    void close() {
        closing = true;
        stop();
    }

    /** Whether it was closed by {@link #close} (else it went away by itself). */
    boolean closedOnPurpose() {
        return closing;
    }

    /** Quits Chrome as the app quits: nothing is remembered or told, she stays on the Looking Glass for the next start. */
    void quit() {
        quitting = true;
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /** Asks Chrome to quit; it is killed when it doesn't. */
    private void stop() {
        process.destroy();
        process.onExit().orTimeout(3, TimeUnit.SECONDS).exceptionally(error -> {
            process.destroyForcibly();
            return null;
        });
    }

    private void closed() {
        Screen.getScreens().removeListener(screens);
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // the node is stopping
        }
        if (!quitting) {
            onClosed.run();
        }
    }
}
