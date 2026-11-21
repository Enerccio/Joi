package com.github.enerccio.joi.domain.bodies;

import com.github.enerccio.marginalia.domain.service.job.JobCallType;
import com.github.enerccio.marginalia.domain.service.job.JobStatus;
import com.github.enerccio.marginalia.domain.service.job.Jobs;
import org.apache.commons.lang3.StringUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Runs a Python script in headless Blender: the converters of models ({@code convert_avatar.py}) and of animation
 * clips ({@code convert_animation.py}). One Blender runs at a time on the server, whatever it converts: it is heavy.
 * <p>
 * Blender is {@code joi.blender} ({@code JOI_BLENDER}), else Blender.app on macOS, else {@code blender} on the PATH.
 */
public final class Blender {

    private static final String MAC_BLENDER = "/Applications/Blender.app/Contents/MacOS/Blender";

    /** Held while a Blender runs. */
    private static final ReentrantLock RUNNING = new ReentrantLock(true);

    private Blender() {
    }

    /** The Blender to run: the configured one, else Blender.app on macOS, else the one on the PATH. */
    public static String executable(String configured) {
        if (StringUtils.isNotBlank(configured)) {
            return configured.strip();
        }
        return new File(MAC_BLENDER).canExecute() ? MAC_BLENDER : "blender";
    }

    /** Copies a script of the classpath into the work folder, to run it. */
    public static Path script(Class<?> owner, String resource, Path work) throws IOException {
        Path script = work.resolve(resource.substring(resource.lastIndexOf('/') + 1));
        try (InputStream in = owner.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("no script " + resource);
            }
            Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
        }
        return script;
    }

    /**
     * Runs the script with its config ({@code Blender -b --factory-startup --python <script> -- <config>}), after any
     * Blender running now, and waits for it.
     *
     * @param lines told each line of its output
     * @return its exit code
     * @throws IllegalStateException when Blender is not found or does not finish in time
     */
    public static int run(String executable, Path script, Path config, Path work, Consumer<String> lines, long timeoutMinutes)
            throws IOException, InterruptedException {
        RUNNING.lockInterruptibly();
        try {
            Process process;
            try {
                process = new ProcessBuilder(executable, "-b", "--factory-startup", "--python-exit-code", "1",
                        "--python", script.toString(), "--", config.toString())
                        .redirectErrorStream(true).directory(work.toFile()).start();
            } catch (IOException e) {
                throw new IllegalStateException("Blender was not found (" + executable + "): install it with the VRM add-on, "
                        + "or set JOI_BLENDER to its executable", e);
            }
            // Blender is a call of the job of the conversion (outside a job it is not recorded); ending a call twice counts the first
            Jobs.Call call = Jobs.beginCall(JobCallType.OTHER, "blender", null);
            int exit = -1;
            try {
                try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = out.readLine()) != null) {
                        lines.accept(line);
                    }
                }
                if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                    process.destroyForcibly();
                    throw new IllegalStateException("Blender did not finish in " + timeoutMinutes + " minutes");
                }
                exit = process.exitValue();
                return exit;
            } catch (IOException | InterruptedException | RuntimeException e) {
                if (call != null) {
                    call.finish(JobStatus.FAILED, e.getClass().getSimpleName());
                }
                throw e;
            } finally {
                if (call != null) {
                    call.finish(exit == 0 ? JobStatus.COMPLETED : JobStatus.FAILED, exit == 0 ? null : "exit code " + exit);
                }
            }
        } finally {
            RUNNING.unlock();
        }
    }
}
