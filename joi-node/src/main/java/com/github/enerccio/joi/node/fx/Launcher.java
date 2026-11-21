package com.github.enerccio.joi.node.fx;

import javafx.application.Application;

/**
 * Starts the JavaFX node from the class path (a main class that is not an {@link Application}, so JavaFX may be on the
 * class path too): {@code preview} as the first argument starts {@link RendererPreviewApp} instead, {@code shots <out dir> <port>} the node with
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
            Application.launch(RendererPreviewApp.class);
        } else if (args.length > 0 && args[0].equals("shots")) {
            // the screenshot mode: the node plus the commands of a script (NodeShots)
            NodeShots.run(java.util.Arrays.copyOfRange(args, 1, args.length));
        } else {
            Application.launch(JoiNodeApp.class, args);
        }
    }
}
