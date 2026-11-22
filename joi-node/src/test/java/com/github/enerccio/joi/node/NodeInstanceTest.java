package com.github.enerccio.joi.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** One node at a time: the lock of the home. */
class NodeInstanceTest {

    @TempDir
    Path home;

    @Test
    void aHomeWithNoLockFileHasNoNode() throws Exception {
        assertThat(NodeInstance.running(home)).isFalse();
        // looking does not take the lock or make the file
        assertThat(home.resolve(NodeInstance.LOCK_FILE)).doesNotExist();
    }

    @Test
    void aLockFileNobodyHoldsIsNoNode() throws Exception {
        // what a crashed node leaves: the file, and no lock (the system freed it)
        Files.createFile(home.resolve(NodeInstance.LOCK_FILE));
        assertThat(NodeInstance.running(home)).isFalse();
    }

    @Test
    void theNodeThatHoldsTheLockIsSeenRunning() throws Exception {
        // the lock is taken for the rest of the process, so this is the only test that takes it
        assertThat(NodeInstance.acquire(home)).isTrue();
        assertThat(home.resolve(NodeInstance.LOCK_FILE)).exists();
        assertThat(NodeInstance.running(home)).isTrue();
        // the same process asking again is the node it already is
        assertThat(NodeInstance.acquire(home)).isTrue();
    }

    @Test
    void anotherProcessIsRefusedWhileTheFirstHoldsIt() throws Exception {
        Path other = home.resolve("other");
        Path javaBin = Path.of(System.getProperty("java.home"), "bin", "java");
        // a second JVM holds the lock of its own home until its input ends: the first sees it running, a third cannot take it
        Process holder = new ProcessBuilder(javaBin.toString(), "-cp", System.getProperty("java.class.path"), Holder.class.getName(), other.toString())
                .redirectErrorStream(true).start();
        try {
            assertThat(holder.getInputStream().read()).as("the holder says it holds the lock").isEqualTo('!');
            assertThat(NodeInstance.running(other)).isTrue();
        } finally {
            holder.getOutputStream().close();
            assertThat(holder.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        assertThat(NodeInstance.running(other)).isFalse();
    }

    /** A node that does nothing but hold its lock until its input ends. */
    public static final class Holder {
        public static void main(String[] args) throws Exception {
            if (!NodeInstance.acquire(Path.of(args[0]))) {
                System.exit(3);
            }
            System.out.write('!');
            System.out.flush();
            System.in.read();
        }
    }
}
