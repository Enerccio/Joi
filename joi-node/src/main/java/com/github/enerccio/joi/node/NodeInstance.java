package com.github.enerccio.joi.node;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * One node at a time per home directory: the node holds a lock on {@code node.lock} in its home for as long as it runs. The operating
 * system lets go of it when the process ends, however it ends, so a node that crashed leaves nothing to clean up (a PID file would).
 * The server's tray icon checks it with {@link #running} before it starts a node, so it never starts a second one.
 */
public final class NodeInstance {

    /** The file in the node's home that is locked while a node runs. */
    public static final String LOCK_FILE = "node.lock";

    private static FileChannel held;
    private static FileLock lock;

    private NodeInstance() {
    }

    /**
     * Takes the lock of this home for the rest of the process.
     *
     * @return false when another node of this home runs
     */
    public static synchronized boolean acquire(Path home) throws IOException {
        if (lock != null) {
            return true;
        }
        Files.createDirectories(home);
        FileChannel channel = FileChannel.open(home.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock taken;
        try {
            taken = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            taken = null;
        }
        if (taken == null) {
            channel.close();
            return false;
        }
        held = channel;
        lock = taken;
        return true;
    }

    /** Whether a node of this home runs (it holds the lock). */
    public static boolean running(Path home) throws IOException {
        Path file = home.resolve(LOCK_FILE);
        if (!Files.exists(file)) {
            return false;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            try (FileLock probe = channel.tryLock()) {
                return probe == null;
            } catch (OverlappingFileLockException e) {
                return true;
            }
        }
    }
}
