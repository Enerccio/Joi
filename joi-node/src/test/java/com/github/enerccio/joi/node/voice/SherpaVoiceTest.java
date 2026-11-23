package com.github.enerccio.joi.node.voice;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real models, on a recording of speech: runs only when the models are installed and a recording is given, so the
 * default build needs neither. It is how to try the voice input without a microphone:
 * {@code JOI_VOICE_TEST_HOME=~/.joi-dev-client JOI_VOICE_TEST_WAV=speech.wav mvn test -pl joi-node -Dtest=SherpaVoiceTest}
 * (a 16 bit mono WAV of any sample rate; the models are put there by the node's Download button).
 */
class SherpaVoiceTest {

    private static Path wav() {
        String env = System.getenv("JOI_VOICE_TEST_WAV");
        Assumptions.assumeTrue(env != null && Files.isRegularFile(Path.of(env)), "JOI_VOICE_TEST_WAV is not set");
        return Path.of(env);
    }

    private static VoiceModels models() {
        String env = System.getenv("JOI_VOICE_TEST_HOME");
        Assumptions.assumeTrue(env != null, "JOI_VOICE_TEST_HOME is not set");
        VoiceModels models = new VoiceModels(Path.of(env.replaceFirst("^~", System.getProperty("user.home"))));
        Assumptions.assumeTrue(models.installed(SpeechModel.MOONSHINE_TINY_EN), "the speech models are not installed there");
        return models;
    }

    /** The recording as 16 kHz mono floats (resampled linearly: enough for a test). */
    private static float[] read(Path file) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat format = in.getFormat();
            assertThat(format.getSampleSizeInBits()).isEqualTo(16);
            assertThat(format.getChannels()).isEqualTo(1);
            byte[] bytes = in.readAllBytes();
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(format.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
            float[] source = new float[bytes.length / 2];
            for (int i = 0; i < source.length; i++) {
                source[i] = buffer.getShort() / 32768f;
            }
            double ratio = format.getSampleRate() / (double) SpeechGate.SAMPLE_RATE;
            float[] out = new float[(int) (source.length / ratio)];
            for (int i = 0; i < out.length; i++) {
                double at = i * ratio;
                int low = (int) at;
                int high = Math.min(low + 1, source.length - 1);
                out[i] = (float) (source[low] + (source[high] - source[low]) * (at - low));
            }
            return out;
        }
    }

    /** The recording followed by two seconds of silence, as the frames a microphone gives. */
    private static AudioInput microphoneHearing(float[] speech) {
        int silence = 2 * SpeechGate.SAMPLE_RATE;
        float[] all = new float[speech.length + silence];
        System.arraycopy(speech, 0, all, 0, speech.length);
        int[] at = {0};
        return new AudioInput() {
            @Override
            public float[] read() {
                if (at[0] + SpeechGate.FRAME > all.length) {
                    return null;
                }
                float[] frame = new float[SpeechGate.FRAME];
                System.arraycopy(all, at[0], frame, 0, SpeechGate.FRAME);
                at[0] += SpeechGate.FRAME;
                return frame;
            }

            @Override
            public void close() {
            }
        };
    }

    @Test
    void theDetectorFindsTheSpeechAndTheRecognizerReadsIt() throws Exception {
        VoiceModels models = models();
        float[] speech = read(wav());

        SileroGate gate = new SileroGate(models.vad());
        UtteranceDetector detector = new UtteranceDetector(gate, 1200, 8000, 60000);
        UtteranceDetector.Utterance utterance = null;
        float[] all = new float[speech.length + 3 * SpeechGate.SAMPLE_RATE];
        System.arraycopy(speech, 0, all, 0, speech.length);
        for (int at = 0; at + SpeechGate.FRAME <= all.length && utterance == null; at += SpeechGate.FRAME) {
            float[] frame = new float[SpeechGate.FRAME];
            System.arraycopy(all, at, frame, 0, SpeechGate.FRAME);
            utterance = detector.accept(frame);
        }
        gate.close();
        assertThat(utterance).isNotNull();
        assertThat(utterance.end()).isEqualTo(UtteranceDetector.End.PAUSE);
        assertThat(utterance.samples().length).isBetween((int) (speech.length * 0.7), speech.length + SpeechGate.SAMPLE_RATE);

        try (SherpaTranscriber transcriber = new SherpaTranscriber(models.dir(SpeechModel.MOONSHINE_TINY_EN))) {
            String text = transcriber.transcribe(utterance.samples());
            System.out.println("Heard: " + text);
            assertThat(text).isNotBlank();
        }
    }

    @Test
    void theWholeRecordingRunsThroughTheVoiceInput() throws Exception {
        VoiceModels models = models();
        float[] speech = read(wav());
        VoiceInput voice = new VoiceInput(() -> microphoneHearing(speech), () -> new SileroGate(models.vad()),
                () -> new SherpaTranscriber(models.dir(SpeechModel.MOONSHINE_TINY_EN)), () -> 1200, 8000, 60000);
        java.util.concurrent.CompletableFuture<String> heard = new java.util.concurrent.CompletableFuture<>();
        voice.start(new VoiceInput.Listener() {
            @Override
            public void listening() {
            }

            @Override
            public void transcribing() {
            }

            @Override
            public void heard(String text) {
                heard.complete(text);
            }

            @Override
            public void nothing(String why) {
                heard.completeExceptionally(new AssertionError("nothing: " + why));
            }

            @Override
            public void failed(String message) {
                heard.completeExceptionally(new AssertionError("failed: " + message));
            }
        });
        String text = heard.get(60, java.util.concurrent.TimeUnit.SECONDS);
        System.out.println("Heard through the voice input: " + text);
        assertThat(text).isNotBlank();
        voice.close();
    }

    @Test
    void silenceIsNotSpeech() throws Exception {
        VoiceModels models = models();
        SileroGate gate = new SileroGate(models.vad());
        UtteranceDetector detector = new UtteranceDetector(gate, 1200, 2000, 60000);
        UtteranceDetector.Utterance utterance = null;
        for (int i = 0; i < 200 && utterance == null; i++) {
            utterance = detector.accept(new float[SpeechGate.FRAME]);
        }
        gate.close();
        assertThat(utterance).isNotNull();
        assertThat(utterance.end()).isEqualTo(UtteranceDetector.End.NO_SPEECH);
    }
}
