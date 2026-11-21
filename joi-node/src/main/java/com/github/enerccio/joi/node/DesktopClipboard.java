package com.github.enerccio.joi.node;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The clipboard of this machine without a window toolkit of the node's own (the terminal node): macOS through {@code pbcopy},
 * Linux through {@code wl-copy}, {@code xclip} or {@code xsel} (the text stays on the clipboard after the program ends), elsewhere
 * the Java clipboard when the system has a desktop. The text goes in through the program's input, never as an argument.
 */
public final class DesktopClipboard implements ClipboardOffers.Clipboard {

    private static final List<List<String>> LINUX = List.of(List.of("wl-copy"), List.of("xclip", "-selection", "clipboard"),
            List.of("xsel", "--clipboard", "--input"));

    @Override
    public void copy(String text) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            pipe(List.of("pbcopy"), text);
            return;
        }
        if (os.contains("linux")) {
            for (List<String> command : LINUX) {
                try {
                    pipe(command, text);
                    return;
                } catch (NotInstalled e) {
                    // the next one
                }
            }
        }
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            throw new IOException("This system has no clipboard the node can reach");
        }
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text), null);
    }

    private static final class NotInstalled extends IOException {
        NotInstalled(String message) {
            super(message);
        }
    }

    private static void pipe(List<String> command, String text) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        // pbcopy takes the encoding from the locale
        builder.environment().put("LC_CTYPE", "UTF-8");
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new NotInstalled(command.getFirst() + " is not installed");
        }
        try (var out = process.getOutputStream()) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        try {
            // wl-copy and xclip stay as the owner of the selection until something else is copied: they do not end, so only the start is waited for
            if (process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() != 0) {
                throw new IOException(command.getFirst() + " failed: " + new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("stopped");
        }
    }
}
