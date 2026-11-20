package com.github.enerccio.joi.node;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * A notification in the system's notification area: on macOS through {@code osascript} ({@code display notification}; the
 * text goes in as an argument, never through a shell), else the Java tray icon when the system has one.
 */
public final class DesktopScreen implements Notifications.Screen {

    @Override
    public void show(String title, String text) throws IOException {
        if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            // the script reads its two texts from the arguments: nothing the model wrote becomes script
            Process process = new ProcessBuilder("osascript", "-e", "on run argv", "-e",
                    "display notification (item 2 of argv) with title (item 1 of argv)", "-e", "end run", title, text)
                    .redirectErrorStream(true).start();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("osascript did not answer");
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("stopped");
            }
            if (process.exitValue() != 0) {
                throw new IOException("osascript failed: " + new String(process.getInputStream().readAllBytes()).strip());
            }
            return;
        }
        if (!java.awt.SystemTray.isSupported()) {
            throw new IOException("This system has no notification area");
        }
        java.awt.TrayIcon icon = new java.awt.TrayIcon(new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB), "Joi");
        try {
            java.awt.SystemTray.getSystemTray().add(icon);
            icon.displayMessage(title, text, java.awt.TrayIcon.MessageType.NONE);
            Thread.sleep(6000);
        } catch (java.awt.AWTException e) {
            throw new IOException(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            java.awt.SystemTray.getSystemTray().remove(icon);
        }
    }
}
