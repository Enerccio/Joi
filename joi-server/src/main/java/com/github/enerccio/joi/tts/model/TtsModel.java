package com.github.enerccio.joi.tts.model;

import java.util.List;
import java.util.UUID;

/**
 * A TTS model of the user: one speech server (Chatterbox-TTS-Server, or ComfyUI for Higgs Audio v3), the model it is asked to have
 * loaded, and the GPU lock that makes room for it. Kept in the settings of the user ({@code TtsSettings}); the api key is stored
 * protected ({@code InferenceServices.protectSecret}), never in plain text. One TTS model is one model: another model on the same
 * server is another TTS model. Copied from the audiobook plugin of Marginalia (its {@code BackendConfig}), without what only its
 * batches use (the requests before and after a batch, the checks of the pieces); the two may diverge.
 */
public class TtsModel implements Cloneable {

    /** The model ids of Chatterbox-TTS-Server ({@code repo_id} of its settings). */
    public static final String MODEL_TURBO = "chatterbox-turbo";
    public static final String MODEL_ORIGINAL = "chatterbox";
    public static final String MODEL_MULTILINGUAL = "chatterbox-multilingual";
    /**
     * Higgs Audio v3 in ComfyUI (the TTS Audio Suite nodes): not a Chatterbox server, the address is the one of ComfyUI. Emotions and sounds are tokens in
     * the text ({@code HiggsText}).
     */
    public static final String MODEL_HIGGS = "higgs-audio-v3";

    public static final List<String> MODELS = List.of(MODEL_TURBO, MODEL_ORIGINAL, MODEL_MULTILINGUAL, MODEL_HIGGS);
    /** The formats of the audio the server makes. */
    public static final List<String> FORMATS = List.of("mp3", "wav");

    public static final int DEFAULT_TIMEOUT_SECONDS = 300;
    public static final double DEFAULT_TEMPERATURE = 0.8;
    public static final double DEFAULT_CFG_WEIGHT = 0.5;
    public static final double DEFAULT_SPEED = 1.0;
    public static final int DEFAULT_CHUNK_SIZE = 240;
    public static final int MIN_CHUNK_SIZE = 50;
    public static final int MAX_CHUNK_SIZE = 500;

    private String id = UUID.randomUUID().toString();
    private String name = "";
    private String baseUrl = "";
    /** Protected with the key of the installation. */
    private String protectedApiKey;
    /** One of {@link #MODELS}: the model the server is asked to have loaded. */
    private String model = MODEL_TURBO;
    /**
     * The uuid of the GPU lock every request to the server takes (see {@code GpuService}): the server runs on a GPU shared with the
     * language model or other servers. Null: no lock.
     */
    private String gpuLock;
    /** Higgs only: the precision of the engine; null (a backend saved before) is {@link HiggsPrecision#FP32}. */
    private HiggsPrecision precision;
    /** Higgs only: send the text of the clip (the words spoken in it) with the clip. Experimental. */
    private boolean sendClipText;
    /** True when the server is on this machine or the local network: the private addresses are allowed. */
    private boolean local = true;
    /** How long one speech request may take, in seconds. */
    private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    /** The format of the audio the server makes, one of {@link #FORMATS}. */
    private String format = "mp3";
    /** The defaults of a segment of the script (the studio changes them per segment). */
    private double temperature = DEFAULT_TEMPERATURE;
    private double cfgWeight = DEFAULT_CFG_WEIGHT;
    private double speed = DEFAULT_SPEED;
    private int chunkSize = DEFAULT_CHUNK_SIZE;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name == null ? "" : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBaseUrl() {
        return baseUrl == null ? "" : baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getProtectedApiKey() {
        return protectedApiKey;
    }

    public void setProtectedApiKey(String protectedApiKey) {
        this.protectedApiKey = protectedApiKey;
    }

    public String getModel() {
        return model == null || !MODELS.contains(model) ? MODEL_TURBO : model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    /** True when the server is a ComfyUI with Higgs Audio v3, not a Chatterbox-TTS-Server. */
    public boolean isHiggs() {
        return MODEL_HIGGS.equals(getModel());
    }

    /** The uuid of the GPU lock, null for none. */
    public String getGpuLock() {
        return gpuLock == null || gpuLock.isBlank() ? null : gpuLock;
    }

    public void setGpuLock(String gpuLock) {
        this.gpuLock = gpuLock;
    }

    public boolean isLocal() {
        return local;
    }

    public void setLocal(boolean local) {
        this.local = local;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getFormat() {
        return format == null || !FORMATS.contains(format) ? "mp3" : format;
    }

    /**
     * A copy with its own id and the given name: everything else the same (the address, the protected key as it is stored, the GPU lock,
     * the defaults), nothing shared with this one. For another model on the same server.
     */
    public TtsModel duplicate(String name) {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        TtsModel copy = gson.fromJson(gson.toJson(this), TtsModel.class);
        copy.id = UUID.randomUUID().toString();
        copy.name = name;
        return copy;
    }

    /** A copy that is not local: the private addresses are refused for it. */
    public TtsModel withoutLocal() {
        try {
            TtsModel copy = (TtsModel) super.clone();
            copy.local = false;
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A copy that asks the server for this format. */
    public TtsModel withFormat(String format) {
        try {
            TtsModel copy = (TtsModel) super.clone();
            copy.format = format;
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public double getTemperature() {
        return temperature <= 0 ? DEFAULT_TEMPERATURE : temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public double getCfgWeight() {
        return cfgWeight <= 0 ? DEFAULT_CFG_WEIGHT : cfgWeight;
    }

    public void setCfgWeight(double cfgWeight) {
        this.cfgWeight = cfgWeight;
    }

    public double getSpeed() {
        return speed <= 0 ? DEFAULT_SPEED : speed;
    }

    public void setSpeed(double speed) {
        this.speed = speed;
    }

    /** The size of the pieces the server splits a long text into, kept in the range the server accepts. */
    public int getChunkSize() {
        return chunkSize <= 0 ? DEFAULT_CHUNK_SIZE : Math.max(MIN_CHUNK_SIZE, Math.min(MAX_CHUNK_SIZE, chunkSize));
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public HiggsPrecision getPrecision() {
        return precision == null ? HiggsPrecision.FP32 : precision;
    }

    public void setPrecision(HiggsPrecision precision) {
        this.precision = precision;
    }

    public boolean isSendClipText() {
        return sendClipText;
    }

    public void setSendClipText(boolean sendClipText) {
        this.sendClipText = sendClipText;
    }

    @Override
    public String toString() {
        return getName();
    }

    /** The backends by name, as the user reads them (the case and the accents do not count), for a picker. */
    public static <T extends TtsModel> java.util.List<T> byName(java.util.Collection<T> backends) {
        java.text.Collator collator = java.text.Collator.getInstance();
        collator.setStrength(java.text.Collator.PRIMARY);
        java.util.List<T> sorted = new java.util.ArrayList<>(backends);
        sorted.sort((a, b) -> collator.compare(a.getName() == null ? "" : a.getName(), b.getName() == null ? "" : b.getName()));
        return sorted;
    }
}
