package com.github.enerccio.joi.node.voice;

import java.net.URI;
import java.util.List;

/**
 * A speech to text model the node can download and run: one archive (from the sherpa-onnx project's release of models)
 * with the files that matter in it. Its language is the node's voice language (English by default, see DESIGN.md → Voice).
 *
 * @param id       names its folder under {@code voice/} in the node's home
 * @param language the language it understands ({@code en})
 * @param label    what the user is told
 * @param url      the {@code .tar.bz2} to download
 * @param sha256   the checksum of that file; a download that differs is thrown away
 * @param size     the size of the download in bytes (for the progress and for telling the user)
 * @param files    the files taken out of the archive, by name
 */
public record SpeechModel(String id, String language, String label, URI url, String sha256, long size, List<String> files) {

    /** Moonshine tiny, English, quantized: small (about 30 MB), fast on the CPU. */
    public static final SpeechModel MOONSHINE_TINY_EN = new SpeechModel(
            "moonshine-tiny-en", "en", "English (Moonshine tiny)",
            URI.create("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
                    + "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27.tar.bz2"),
            "9ec31b342d8fa3240c3b81b8f82e1cf7e3ac467c93ca5a999b741d5887164f8d",
            29_858_559L,
            List.of("encoder_model.ort", "decoder_model_merged.ort", "tokens.txt"));

    /** The models the node knows (the user picks the one of their language, later). */
    public static final List<SpeechModel> ALL = List.of(MOONSHINE_TINY_EN);
}
