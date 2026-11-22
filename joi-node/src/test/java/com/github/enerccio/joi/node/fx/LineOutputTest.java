package com.github.enerccio.joi.node.fx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The output line of her voice, on lines that behave: a healthy one, and one on a device that is gone (writes that never return). */
class LineOutputTest {

    @TempDir
    Path folder;

    /** A silent wav of that length (24 kHz, mono, 16 bit). */
    private Path wav(double seconds) throws Exception {
        AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 24000, 16, 1, 2, 24000, false);
        int frames = (int) (24000 * seconds);
        Path file = folder.resolve("piece-" + System.nanoTime() + ".wav");
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(new byte[frames * 2]), format, frames)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, file.toFile());
        }
        return file;
    }

    /** What a fake line did. */
    private static final class Fake {
        final AtomicInteger flushed = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final CountDownLatch gone = new CountDownLatch(1);
        volatile boolean open = true;
    }

    /** A line whose device is gone ({@code dead}: write and drain wait until it is closed) or one that takes everything at once. */
    private static SourceDataLine line(Fake fake, boolean dead, AudioFormat format) {
        return (SourceDataLine) Proxy.newProxyInstance(LineOutputTest.class.getClassLoader(), new Class<?>[]{SourceDataLine.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "write":
                    if (dead) {
                        fake.gone.await();
                        return 0;
                    }
                    return args[2];
                case "drain":
                    if (dead) {
                        fake.gone.await();
                    }
                    return null;
                case "flush":
                    fake.flushed.incrementAndGet();
                    return null;
                case "close":
                    fake.open = false;
                    fake.closed.incrementAndGet();
                    fake.gone.countDown();
                    return null;
                case "isOpen":
                case "isRunning":
                    return method.getName().equals("isRunning") || fake.open;
                case "getFormat":
                    return format;
                default:
                    Class<?> type = method.getReturnType();
                    return type == boolean.class ? Boolean.FALSE : type == int.class ? (Object) 0 : type == long.class ? (Object) 0L : null;
            }
        });
    }

    @Test
    void aHealthyLinePlaysThePieceAndSaysWhenItStartedAndEnded() throws Exception {
        Fake fake = new Fake();
        LineOutput output = new LineOutput((format, bytes) -> line(fake, false, format), 500);
        CountDownLatch ended = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        long[] at = new long[1];
        long before = System.currentTimeMillis();
        output.play(LineOutput.open(wav(0.2)), time -> at[0] = time, ended::countDown, failed::countDown);

        assertThat(ended.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(failed.getCount()).isEqualTo(1);
        // the time is wall-clock milliseconds, taken when the sound was written
        assertThat(at[0]).isBetween(before, System.currentTimeMillis());
        output.close();
    }

    @Test
    void aLineOnADeviceThatIsGoneIsGivenUpOnSoThePieceCanBePlayedAnotherWay() throws Exception {
        Fake fake = new Fake();
        LineOutput output = new LineOutput((format, bytes) -> line(fake, true, format), 400);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        output.play(LineOutput.open(wav(0.1)), time -> started.countDown(), ended::countDown, failed::countDown);

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        // its length (0.1 s) and the stall limit (0.4 s) pass, the line is closed and the piece fails: the caller plays it another way
        assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ended.getCount()).isEqualTo(1);
        assertThat(fake.closed.get()).isGreaterThanOrEqualTo(1);
        output.close();
    }

    @Test
    void stoppingHerDoesNotWaitBehindAPieceThatIsStuck() throws Exception {
        Fake fake = new Fake();
        // a long stall limit: only the stop can get the line moving
        LineOutput output = new LineOutput((format, bytes) -> line(fake, true, format), 60_000);
        CountDownLatch started = new CountDownLatch(1);
        output.play(LineOutput.open(wav(0.1)), time -> started.countDown(), () -> {
        }, () -> {
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        output.cancel();

        // the flush happened at once, on the caller's thread, while the piece's own thread is stuck in the write
        assertThat(fake.flushed.get()).isEqualTo(1);
        output.close();
    }
}
