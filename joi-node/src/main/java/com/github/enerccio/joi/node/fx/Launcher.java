package com.github.enerccio.joi.node.fx;

import javafx.application.Application;

/**
 * Starts the JavaFX node from the class path (a main class that is not an {@link Application}, so JavaFX may be on the
 * class path too): {@code --joi-home-dir <folder>} (anywhere) moves the node's home, see {@link com.github.enerccio.joi.node.NodeSettings#resolve}.
 * {@code --local <socket>} (and {@code --web <url>}) starts it for the server on this machine, as the server's desktop app does
 * ({@link com.github.enerccio.joi.node.LocalMode}). One node runs at a time per home ({@link com.github.enerccio.joi.node.NodeInstance}).
 * {@code preview} as the first argument starts {@link RendererPreviewApp} instead, {@code shots <out dir> <port>} the node with
 * the commands of the screenshot mode ({@link NodeShots}).
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        // exec:exec hands -Dnode.args over as one argument: "shots <out dir> <port>" is split here
        if (args.length == 1 && args[0].contains(" ")) {
            args = args[0].strip().split("\\s+");
        }
        if (args.length > 0 && args[0].equals("preview")) {
            Application.launch(RendererPreviewApp.class, java.util.Arrays.copyOfRange(args, 1, args.length));
        } else if (args.length > 0 && args[0].equals("shots")) {
            // the screenshot mode: the node plus the commands of a script (NodeShots)
            NodeShots.run(java.util.Arrays.copyOfRange(args, 1, args.length));
        } else {
            // one node at a time per home: a second one (a double click, the tray icon of the server pressed twice) says so and ends
            try {
                java.nio.file.Path home = com.github.enerccio.joi.node.NodeSettings.resolve(args, System.getenv(), System.getProperty("user.home"));
                if (!com.github.enerccio.joi.node.NodeInstance.acquire(home)) {
                    System.err.println("The Joi node already runs (" + home + ")");
                    System.exit(3);
                    return;
                }
            } catch (java.io.IOException e) {
                System.err.println("The Joi node could not take its lock: " + e.getMessage());
            }
            Application.launch(JoiNodeApp.class, args);
        }
    }
}
