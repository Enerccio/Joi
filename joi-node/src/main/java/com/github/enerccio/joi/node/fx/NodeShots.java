package com.github.enerccio.joi.node.fx;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Labeled;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The screenshot mode of the JavaFX node: the pictures of the manual that show the node (the counterpart of
 * {@code screenshots/} for the server's UI, see {@code screenshots/SCREENSHOTS.md}).
 *
 * <p>The node runs as always ({@link JoiNodeApp}, in this process) and also listens for commands on
 * {@code 127.0.0.1:<port>} (see {@link ShotCommands}), so a script outside it drives the app: finds a control by its text,
 * clicks it, types into a field, waits for a screen, writes a window as a PNG ({@code Node.snapshot}: no accessibility
 * permission and no screen recorder). The flows are {@code screenshots/node-flows/*.mjs}, next to the ones of the web UI.
 * The mode is only started by {@code Launcher shots}, never by a normal start. It is not headless: the window opens on the
 * screen (OpenJFX has no headless pipeline). The node's own settings are isolated with {@code JOI_CLIENT}, which
 * {@code screenshots/bin/start-node.sh} sets.
 *
 * <pre>
 * mvn -o -Pfx compile exec:exec@run -pl joi-node -Dnode.args="shots &lt;out dir&gt; &lt;port&gt;"
 * </pre>
 */
public final class NodeShots extends Application {

    /** The device pixel ratio of the pictures (2: sharp like on a Retina screen), {@code NODE_SHOT_SCALE}. */
    private static final double SCALE = Double.parseDouble(System.getenv().getOrDefault("NODE_SHOT_SCALE", "2"));

    private static File out;
    private static int port;

    private Stage stage;

    /** Starts the harness (called by {@link Launcher}). */
    static void run(String[] args) {
        out = new File(args.length > 0 ? args[0] : "screenshots/out");
        port = args.length > 1 ? Integer.parseInt(args[1]) : 8097;
        launch(NodeShots.class);
    }

    @Override
    public void start(Stage primary) throws Exception {
        this.stage = primary;
        out.mkdirs();
        new JoiNodeApp().start(primary);
        // the system menu bar of macOS is not part of the scene: a bar in the window can be photographed
        for (MenuBar bar : find(MenuBar.class)) {
            bar.setUseSystemMenuBar(false);
        }
        new ShotCommands(this, port).start();
        System.out.println("node shots on http://127.0.0.1:" + port);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // the scene graph
    // ----------------------------------------------------------------------------------------------------------------

    /** Runs on the FX thread and waits for the result. */
    <T> T fx(Supplier<T> job) {
        if (Platform.isFxApplicationThread()) {
            return job.get();
        }
        CompletableFuture<T> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                done.complete(job.get());
            } catch (Throwable e) {
                done.completeExceptionally(e);
            }
        });
        try {
            return done.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("the FX thread: " + e, e);
        }
    }

    /** Every node of the given type in all open windows (the windows of dialogs and menus too). */
    <T extends Node> List<T> find(Class<T> type) {
        return fx(() -> {
            List<T> found = new ArrayList<>();
            for (Window window : new ArrayList<>(Window.getWindows())) {
                if (window.getScene() != null) {
                    collect(window.getScene().getRoot(), type, found);
                }
            }
            return found;
        });
    }

    private static <T extends Node> void collect(Node node, Class<T> type, List<T> found) {
        if (type.isInstance(node)) {
            found.add(type.cast(node));
        }
        if (node instanceof Parent parent) {
            for (Node child : new ArrayList<>(parent.getChildrenUnmodifiable())) {
                collect(child, type, found);
            }
        }
    }

    /** A control with this text (a label, a button) or prompt text (a field), or null. */
    Node byText(String text) {
        return fx(() -> {
            for (Node node : find(Node.class)) {
                if (!node.isVisible() || node.getScene() == null) {
                    continue;
                }
                if (node instanceof Labeled labeled && text.equals(labeled.getText())) {
                    return node;
                }
                if (node instanceof TextInputControl field && text.equals(field.getPromptText())) {
                    return node;
                }
            }
            return null;
        });
    }

    /** Waits until a control with this text is on screen. */
    Node await(String text) {
        return await(text, 20);
    }

    Node await(String text, int seconds) {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            Node node = byText(text);
            if (node != null) {
                return node;
            }
            pause(200);
        }
        throw new IllegalStateException("no control with the text \"" + text + "\" after " + seconds + " s");
    }

    /** Waits until a control whose text starts with this appears; returns its whole text. */
    String awaitStarting(String start, int seconds) {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            String text = fx(() -> {
                for (Labeled labeled : find(Labeled.class)) {
                    if (labeled.isVisible() && labeled.getText() != null && labeled.getText().startsWith(start)) {
                        return labeled.getText();
                    }
                }
                return null;
            });
            if (text != null) {
                return text;
            }
            pause(200);
        }
        throw new IllegalStateException("no control with the text starting \"" + start + "\" after " + seconds + " s");
    }

    /** Presses the button with this text. */
    void click(String text) {
        Node node = await(text);
        fx(() -> {
            if (node instanceof ButtonBase button) {
                button.fire();
            } else {
                throw new IllegalStateException("\"" + text + "\" is not a button");
            }
            return null;
        });
        pause(300);
    }

    /** Types into the field with this prompt text (replaces what it holds). */
    void type(String prompt, String text) {
        Node node = await(prompt);
        fx(() -> {
            ((TextInputControl) node).setText(text);
            return null;
        });
        pause(150);
    }

    /** Sets the text of the editable combo box (the server address) that holds the first editor of the screen. */
    void typeInCombo(String text) {
        fx(() -> {
            for (ComboBoxBase<?> box : find(ComboBoxBase.class)) {
                if (box instanceof javafx.scene.control.ComboBox<?> combo && combo.isEditable()) {
                    combo.getEditor().setText(text);
                    return null;
                }
            }
            throw new IllegalStateException("no editable combo box");
        });
        pause(150);
    }

    /** Opens a menu of the menu bar ({@code path}: the menu, then its sub menus) and leaves it open; {@link #closeMenus()} closes. */
    void openMenu(String... path) {
        fx(() -> {
            for (MenuBar bar : find(MenuBar.class)) {
                ObservableList<Menu> menus = bar.getMenus();
                for (Menu menu : menus) {
                    if (path[0].equals(menu.getText())) {
                        Menu current = menu;
                        current.show();
                        for (int i = 1; i < path.length; i++) {
                            for (MenuItem item : current.getItems()) {
                                if (item instanceof Menu sub && path[i].equals(sub.getText())) {
                                    sub.show();
                                    current = sub;
                                    break;
                                }
                            }
                        }
                        return null;
                    }
                }
            }
            throw new IllegalStateException("no menu " + String.join(" > ", path));
        });
        pause(500);
    }

    void closeMenus() {
        fx(() -> {
            for (MenuBar bar : find(MenuBar.class)) {
                bar.getMenus().forEach(Menu::hide);
            }
            return null;
        });
        pause(200);
    }

    /** Fires an item of the menu bar by the texts along the way (a test of the menus; nothing is photographed). */
    void chooseMenu(String... path) {
        fx(() -> {
            for (MenuBar bar : find(MenuBar.class)) {
                for (Menu menu : bar.getMenus()) {
                    if (path[0].equals(menu.getText())) {
                        Menu current = menu;
                        for (int i = 1; i < path.length; i++) {
                            for (MenuItem item : current.getItems()) {
                                if (path[i].equals(item.getText())) {
                                    if (item instanceof Menu sub) {
                                        current = sub;
                                    } else {
                                        item.fire();
                                        return null;
                                    }
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            throw new IllegalStateException("no item " + String.join(" > ", path));
        });
        pause(400);
    }

    static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // pictures
    // ----------------------------------------------------------------------------------------------------------------

    /** The main window with the open menus and drop downs on it, as {@code <out>/<name>.png}. */
    File snap(String name) {
        pause(300);
        BufferedImage image = fx(() -> compose(stage, false));
        return write(name, image);
    }

    /** The topmost dialog (the newest window that is not the main one) alone, as {@code <out>/<name>.png}. */
    File snapDialog(String name) {
        pause(500);
        BufferedImage image = fx(() -> {
            List<Window> windows = new ArrayList<>(Window.getWindows());
            for (int i = windows.size() - 1; i >= 0; i--) {
                Window window = windows.get(i);
                if (window != stage && window.isShowing() && window.getScene() != null) {
                    return capture(window.getScene().getRoot());
                }
            }
            throw new IllegalStateException("no dialog is open");
        });
        return write(name, image);
    }

    /** True when a window besides the main one is open (a dialog, a menu). */
    boolean dialogOpen() {
        return fx(() -> Window.getWindows().stream().anyMatch(w -> w != stage && w.isShowing()
                && !(w instanceof javafx.stage.PopupWindow)));
    }

    private BufferedImage compose(Stage main, boolean dialogsToo) {
        BufferedImage base = capture(main.getScene().getRoot());
        Graphics2D g = base.createGraphics();
        for (Window window : Window.getWindows()) {
            if (window == main || !window.isShowing() || window.getScene() == null) {
                continue;
            }
            boolean popup = window instanceof javafx.stage.PopupWindow;
            if (!popup && !dialogsToo) {
                continue;
            }
            BufferedImage part = capture(window.getScene().getRoot());
            int x = (int) Math.round((window.getX() - main.getX() - main.getScene().getX()) * SCALE);
            int y = (int) Math.round((window.getY() - main.getY() - main.getScene().getY()) * SCALE);
            g.drawImage(part, x, y, null);
        }
        g.dispose();
        return base;
    }

    private static BufferedImage capture(Node root) {
        SnapshotParameters params = new SnapshotParameters();
        params.setTransform(new Scale(SCALE, SCALE));
        // a menu's window is bigger than its menu (the shadow): what is not painted stays transparent when it is put on the window
        params.setFill(javafx.scene.paint.Color.TRANSPARENT);
        WritableImage image = root.snapshot(params, null);
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        return out;
    }

    private File write(String name, BufferedImage image) {
        File file = new File(out, name + ".png");
        try {
            ImageIO.write(image, "png", file);
        } catch (IOException e) {
            throw new IllegalStateException("can't write " + file + ": " + e.getMessage(), e);
        }
        System.out.println("  saved " + file);
        return file;
    }

    Stage stage() {
        return stage;
    }

    Scene scene() {
        return fx(() -> stage.getScene());
    }
}
