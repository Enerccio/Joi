package com.github.enerccio.joi.node.voice;

/**
 * Speech to text. The node has one of these for the language it was set to ({@link SherpaTranscriber}: sherpa-onnx on the
 * desktop); on iOS it is Apple's recognizer, behind the same idea (see DESIGN.md → Voice). What it gives is text, which the
 * node says like typed words.
 */
public interface Transcriber extends AutoCloseable {

    /**
     * Turns speech into text.
     *
     * @param samples 16 kHz mono, -1 to 1: one utterance
     * @return what was said, trimmed; empty when the recognizer found no words
     */
    String transcribe(float[] samples);

    @Override
    void close();
}
