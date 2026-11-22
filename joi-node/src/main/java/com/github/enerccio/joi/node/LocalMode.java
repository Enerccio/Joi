package com.github.enerccio.joi.node;

import java.nio.file.Path;
import java.util.Map;

/**
 * The node started by the desktop app of the server on this machine: it is told the server's local socket and the address of the
 * server's web UI, and needs no server address, no token and no pairing. {@code --local <socket>} and {@code --web <url>}, or the
 * environment variables {@code JOI_LOCAL_SOCKET} and {@code JOI_WEB_URL}; the flags win.
 *
 * @param socket the Unix domain socket of the server
 * @param web    where the user makes the first account and manages the assistant ({@code http://127.0.0.1:8765}), null if not known
 */
public record LocalMode(Path socket, String web) {

    /** The mode these arguments and this environment ask for, null for the usual node. */
    public static LocalMode parse(String[] args, Map<String, String> env) {
        String socket = env.get("JOI_LOCAL_SOCKET");
        String web = env.get("JOI_WEB_URL");
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--local") && i + 1 < args.length) {
                socket = args[++i];
            } else if (args[i].startsWith("--local=")) {
                socket = args[i].substring("--local=".length());
            } else if (args[i].equals("--web") && i + 1 < args.length) {
                web = args[++i];
            } else if (args[i].startsWith("--web=")) {
                web = args[i].substring("--web=".length());
            }
        }
        if (socket == null || socket.isBlank()) {
            return null;
        }
        return new LocalMode(Path.of(socket.strip()).toAbsolutePath(), web == null || web.isBlank() ? null : web.strip());
    }

    /** The address of the server as the node keeps it ({@link ServerClient#UNIX}). */
    public String server() {
        return ServerClient.UNIX + socket;
    }
}
