package com.github.enerccio.joi.node.voice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UtteranceDetectorTest {

    /** A frame of speech: the fake gate reads the first sample. */
    static float[] speech() {
        float[] frame = new float[SpeechGate.FRAME];
        java.util.Arrays.fill(frame, 1f);
        return frame;
    }

    static float[] silence() {
        return new float[SpeechGate.FRAME];
    }

    static final SpeechGate GATE = new SpeechGate() {
        @Override
        public float probability(float[] frame) {
            return frame[0];
        }

        @Override
        public void reset() {
        }
    };

    /** A detector with a pause of 320 ms (10 frames), waiting 1 s (32 frames) and at most 3.2 s (100 frames). */
    private static UtteranceDetector detector() {
        return new UtteranceDetector(GATE, 320, 1000, 3200);
    }

    private static UtteranceDetector.Utterance feed(UtteranceDetector detector, int speechFrames, int silentFrames) {
        UtteranceDetector.Utterance result = null;
        for (int i = 0; i < speechFrames && result == null; i++) {
            result = detector.accept(speech());
        }
        for (int i = 0; i < silentFrames && result == null; i++) {
            result = detector.accept(silence());
        }
        return result;
    }

    @Test
    void aPauseEndsTheUtteranceAndTheSilenceAroundItIsCutOff() {
        UtteranceDetector detector = detector();
        // 5 frames of silence, 20 of speech, then the pause
        for (int i = 0; i < 5; i++) {
            assertThat(detector.accept(silence())).isNull();
        }
        UtteranceDetector.Utterance result = feed(detector, 20, 30);
        assertThat(result).isNotNull();
        assertThat(result.end()).isEqualTo(UtteranceDetector.End.PAUSE);
        assertThat(result.spoken()).isTrue();
        // all 5 silent frames before the speech fit in the pre-roll (8), and the tail is 6 frames after the speech
        assertThat(result.samples().length / SpeechGate.FRAME).isEqualTo(5 + 20 + UtteranceDetector.TAIL_FRAMES);
    }

    @Test
    void thePreRollIsLimited() {
        UtteranceDetector detector = detector();
        for (int i = 0; i < 20; i++) {
            detector.accept(silence());
        }
        UtteranceDetector.Utterance result = feed(detector, 10, 30);
        assertThat(result.samples().length / SpeechGate.FRAME)
                .isEqualTo(UtteranceDetector.PRE_ROLL_FRAMES + 10 + UtteranceDetector.TAIL_FRAMES);
    }

    @Test
    void aShortGapInsideTheSpeechDoesNotEndIt() {
        UtteranceDetector detector = detector();
        assertThat(feed(detector, 10, 5)).isNull();
        UtteranceDetector.Utterance result = feed(detector, 10, 30);
        assertThat(result.end()).isEqualTo(UtteranceDetector.End.PAUSE);
        // 10 + 5 + 10 frames of the recording, and the tail
        assertThat(result.samples().length / SpeechGate.FRAME).isEqualTo(10 + 5 + 10 + UtteranceDetector.TAIL_FRAMES);
    }

    @Test
    void nothingSaidForTooLongIsNoSpeech() {
        UtteranceDetector detector = detector();
        UtteranceDetector.Utterance result = feed(detector, 0, 100);
        assertThat(result.end()).isEqualTo(UtteranceDetector.End.NO_SPEECH);
        assertThat(result.spoken()).isFalse();
    }

    @Test
    void aNoiseTooShortForAWordIsForgottenAndTheWaitGoesOn() {
        UtteranceDetector detector = detector();
        // 2 frames of noise and the pause after it: not a word, so the detector goes on waiting...
        assertThat(feed(detector, 2, 12)).isNull();
        // ...and the next speech is a word
        UtteranceDetector.Utterance result = feed(detector, 12, 30);
        assertThat(result.end()).isEqualTo(UtteranceDetector.End.PAUSE);
        assertThat(result.samples().length / SpeechGate.FRAME).isEqualTo(UtteranceDetector.PRE_ROLL_FRAMES + 12 + UtteranceDetector.TAIL_FRAMES);
    }

    @Test
    void theUserCanEndItBeforeThePause() {
        UtteranceDetector detector = detector();
        assertThat(feed(detector, 10, 3)).isNull();
        UtteranceDetector.Utterance result = detector.finish();
        assertThat(result.end()).isEqualTo(UtteranceDetector.End.FINISHED);
        assertThat(result.spoken()).isTrue();
    }

    @Test
    void endingItBeforeAnyWordIsNoSpeech() {
        UtteranceDetector detector = detector();
        assertThat(feed(detector, 2, 2)).isNull();
        assertThat(detector.finish().end()).isEqualTo(UtteranceDetector.End.NO_SPEECH);
    }

    @Test
    void anUtteranceHasALongestLength() {
        UtteranceDetector detector = detector();
        UtteranceDetector.Utterance result = feed(detector, 200, 0);
        assertThat(result.end()).isEqualTo(UtteranceDetector.End.TOO_LONG);
        assertThat(result.spoken()).isTrue();
    }
}
