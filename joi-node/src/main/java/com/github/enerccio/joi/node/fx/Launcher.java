package com.github.enerccio.joi.node.fx;

import javafx.application.Application;

/**
 * Starts the JavaFX node from the class path (a main class that is not an {@link Application}, so JavaFX may be on the
 * class path too): {@code preview} as the first argument starts {@link RendererPreviewApp} instead.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("preview")) {
            Application.launch(RendererPreviewApp.class);
        } else {
            Application.launch(JoiNodeApp.class, args);
        }
    }
}
