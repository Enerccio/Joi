package com.github.enerccio.joi.node.voice;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceInputTest {

    /** What the input gives: frames of speech ({@code true}) or silence, then it waits (like a microphone) until closed. */
    private static final class ScriptedInput implements AudioInput {
        private final Iterator<Boolean> frames;
        private final AtomicBoolean closed = new AtomicBoolean();

        ScriptedInput(Boolean... frames) {
            this.frames = List.of(frames).iterator();
        }

        @Override
        public float[] read() {
            if (closed.get()) {
                return null;
            }
            if (frames.hasNext()) {
                return frames.next() ? UtteranceDetectorTest.speech() : UtteranceDetectorTest.silence();
            }
            // the user has said what they say: silence goes on, at the pace of a microphone
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return closed.get() ? null : UtteranceDetectorTest.silence();
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static Boolean[] script(int silence, int speech) {
        List<Boolean> frames = new ArrayList<>();
        for (int i = 0; i < silence; i++) {
            frames.add(false);
        }
        for (int i = 0; i < speech; i++) {
            frames.add(true);
        }
        return frames.toArray(new Boolean[0]);
    }

    /** Collects what the listener is told, and lets the test wait for the end of the recording. */
    private static final class Events implements VoiceInput.Listener {
        final List<String> events = new ArrayList<>();
        final CountDownLatch over = new CountDownLatch(1);

        @Override
        public synchronized void listening() {
            events.add("listening");
        }

        @Override
        public synchronized void transcribing() {
            events.add("transcribing");
        }

        @Override
        public synchronized void heard(String text) {
            events.add("heard:" + text);
            over.countDown();
        }

        @Override
        public synchronized void nothing(String why) {
            events.add("nothing:" + why);
            over.countDown();
        }

        @Override
        public synchronized void failed(String message) {
            events.add("failed:" + message);
            over.countDown();
        }

        void await() throws InterruptedException {
            assertThat(over.await(5, TimeUnit.SECONDS)).as("the recording ended").isTrue();
        }
    }

    private static VoiceInput voice(VoiceInput.Inputs inputs, AtomicInteger transcribed) {
        Transcriber transcriber = new Transcriber() {
            @Override
            public String transcribe(float[] samples) {
                transcribed.incrementAndGet();
                return samples.length > 0 ? "  hello there " : "";
            }

            @Override
            public void close() {
            }
        };
        // a pause of 320 ms (10 frames), 1 s to begin, 3.2 s at most
        return new VoiceInput(inputs, () -> UtteranceDetectorTest.GATE, () -> transcriber, () -> 320, 1000, 3200);
    }

    @Test
    void whatTheUserSaysIsHeardAfterThePauseAndTheMicrophoneIsLetGo() throws Exception {
        ScriptedInput input = new ScriptedInput(script(3, 20));
        AtomicInteger transcribed = new AtomicInteger();
        VoiceInput voice = voice(() -> input, transcribed);
        Events events = new Events();
        assertThat(voice.start(events)).isTrue();
        events.await();
        assertThat(events.events).containsExactly("listening", "transcribing", "heard:hello there");
        assertThat(input.closed).isTrue();
        assertThat(voice.state()).isEqualTo(VoiceInput.State.IDLE);
    }

    @Test
    void theUserCanEndItBeforeThePause() throws Exception {
        // speech, then silence that never reaches the pause: only the user's finish ends it
        ScriptedInput input = new ScriptedInput(script(0, 10));
        VoiceInput voice = new VoiceInput(() -> input, () -> UtteranceDetectorTest.GATE, () -> new Transcriber() {
            @Override
            public String transcribe(float[] samples) {
                return "now";
            }

            @Override
            public void close() {
            }
        }, () -> 60_000, 1000, 600_000);
        Events events = new Events();
        voice.start(events);
        Thread.sleep(150);
        voice.finish();
        events.await();
        assertThat(events.events).containsExactly("listening", "transcribing", "heard:now");
    }

    @Test
    void silenceIsNothingAndNothingIsTranscribed() throws Exception {
        AtomicInteger transcribed = new AtomicInteger();
        VoiceInput voice = voice(() -> new ScriptedInput(), transcribed);
        Events events = new Events();
        voice.start(events);
        events.await();
        assertThat(events.events).containsExactly("listening", "nothing:I heard nothing.");
        assertThat(transcribed).hasValue(0);
    }

    @Test
    void cancellingUsesNothing() throws Exception {
        AtomicInteger transcribed = new AtomicInteger();
        ScriptedInput input = new ScriptedInput(script(0, 10));
        VoiceInput voice = new VoiceInput(() -> input, () -> UtteranceDetectorTest.GATE, () -> new Transcriber() {
            @Override
            public String transcribe(float[] samples) {
                transcribed.incrementAndGet();
                return "never";
            }

            @Override
            public void close() {
            }
        }, () -> 60_000, 1000, 600_000);
        Events events = new Events();
        voice.start(events);
        Thread.sleep(100);
        voice.cancel();
        events.await();
        assertThat(events.events).containsExactly("listening", "nothing:Cancelled.");
        assertThat(transcribed).hasValue(0);
    }

    @Test
    void aMicrophoneThatCannotBeOpenedFailsTheRecording() throws Exception {
        VoiceInput voice = voice(() -> {
            throw new IOException("The microphone cannot be opened: denied");
        }, new AtomicInteger());
        Events events = new Events();
        voice.start(events);
        events.await();
        assertThat(events.events).containsExactly("failed:The microphone cannot be opened: denied");
        assertThat(voice.state()).isEqualTo(VoiceInput.State.IDLE);
    }

    @Test
    void modelsThatCannotBeLoadedFailTheRecordingBeforeTheMicrophoneOpens() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        VoiceInput voice = new VoiceInput(() -> {
            opened.incrementAndGet();
            return new ScriptedInput();
        }, () -> {
            throw new IllegalStateException("no model");
        }, () -> null, () -> 320, 1000, 3200);
        Events events = new Events();
        voice.start(events);
        events.await();
        assertThat(events.events).containsExactly("failed:The speech model could not be loaded: no model");
        assertThat(opened).hasValue(0);
    }

    @Test
    void onlyOneRecordingRunsAtATime() throws Exception {
        ScriptedInput input = new ScriptedInput(script(0, 5));
        VoiceInput voice = new VoiceInput(() -> input, () -> UtteranceDetectorTest.GATE, () -> new Transcriber() {
            @Override
            public String transcribe(float[] samples) {
                return "x";
            }

            @Override
            public void close() {
            }
        }, () -> 60_000, 1000, 600_000);
        Events first = new Events();
        assertThat(voice.start(first)).isTrue();
        assertThat(voice.start(new Events())).isFalse();
        voice.cancel();
        first.await();
    }

    @Test
    void closingWhileTheUserTalksLetsTheModelsGoOnlyWhenTheRecordingEnds() throws Exception {
        ScriptedInput input = new ScriptedInput(script(3, 0));
        AtomicBoolean gateClosed = new AtomicBoolean();
        AtomicBoolean closedWhileUsed = new AtomicBoolean();
        CountDownLatch reading = new CountDownLatch(1);
        // the recording holds on in the model until the test has closed the input
        CountDownLatch proceed = new CountDownLatch(1);
        SpeechGate gate = new SpeechGate() {
            @Override
            public float probability(float[] frame) {
                if (gateClosed.get()) {
                    // the native model would be freed under the recording
                    closedWhileUsed.set(true);
                }
                reading.countDown();
                try {
                    proceed.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return frame[0];
            }

            @Override
            public void reset() {
            }

            @Override
            public void close() {
                gateClosed.set(true);
            }
        };
        Transcriber transcriber = new Transcriber() {
            @Override
            public String transcribe(float[] samples) {
                return "";
            }

            @Override
            public void close() {
            }
        };
        VoiceInput voice = new VoiceInput(() -> input, () -> gate, () -> transcriber, () -> 320, 1000, 3200);
        Events events = new Events();
        assertThat(voice.start(events)).isTrue();
        assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();

        voice.close();
        assertThat(gateClosed).as("not while the recording uses it").isFalse();
        proceed.countDown();
        events.await();
        assertThat(closedWhileUsed).isFalse();
        assertThat(gateClosed).as("the recording let it go when it ended").isTrue();
        assertThat(voice.start(new Events())).as("a closed input starts nothing").isFalse();
    }
}
