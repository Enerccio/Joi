package com.github.enerccio.joi.node.voice;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;

import java.nio.file.Path;

/**
 * Speech to text with a Moonshine model, run by sherpa-onnx on the CPU. The recognizer takes the whole utterance (it does
 * not stream), which is what push-to-talk has when the user is done. It runs one utterance at a time.
 */
public final class SherpaTranscriber implements Transcriber {

    private final OfflineRecognizer recognizer;

    /** @param dir the folder of the model ({@link SpeechModel#files}) */
    public SherpaTranscriber(Path dir) {
        OfflineMoonshineModelConfig moonshine = OfflineMoonshineModelConfig.builder()
                .setEncoder(dir.resolve("encoder_model.ort").toString())
                .setMergedDecoder(dir.resolve("decoder_model_merged.ort").toString())
                .build();
        OfflineModelConfig model = OfflineModelConfig.builder()
                .setMoonshine(moonshine)
                .setTokens(dir.resolve("tokens.txt").toString())
                .setNumThreads(2)
                .setProvider("cpu")
                .setDebug(false)
                .build();
        this.recognizer = new OfflineRecognizer(OfflineRecognizerConfig.builder().setOfflineModelConfig(model).build());
    }

    @Override
    public synchronized String transcribe(float[] samples) {
        OfflineStream stream = recognizer.createStream();
        try {
            stream.acceptWaveform(samples, SpeechGate.SAMPLE_RATE);
            recognizer.decode(stream);
            String text = recognizer.getResult(stream).getText();
            return text == null ? "" : text.strip();
        } finally {
            stream.release();
        }
    }

    @Override
    public synchronized void close() {
        recognizer.release();
    }
}
