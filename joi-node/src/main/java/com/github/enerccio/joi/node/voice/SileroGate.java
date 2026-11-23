package com.github.enerccio.joi.node.voice;

import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.nio.file.Path;

/** The Silero voice activity detector, run by sherpa-onnx. */
public final class SileroGate implements SpeechGate {

    private final Vad vad;

    /** @param model the file {@code silero_vad.onnx} */
    public SileroGate(Path model) {
        SileroVadModelConfig silero = SileroVadModelConfig.builder()
                .setModel(model.toString())
                .setWindowSize(FRAME)
                .build();
        this.vad = new Vad(VadModelConfig.builder()
                .setSileroVadModelConfig(silero)
                .setSampleRate(SAMPLE_RATE)
                .setNumThreads(1)
                .setProvider("cpu")
                .setDebug(false)
                .build());
    }

    @Override
    public float probability(float[] frame) {
        return vad.compute(frame);
    }

    @Override
    public void reset() {
        vad.reset();
    }

    @Override
    public void close() {
        vad.release();
    }
}
