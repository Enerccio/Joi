package com.github.enerccio.joi.tts.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the user keeps for speech, as JSON in the attributes of their user setting under {@link #KEY}: the TTS models, the voice actors
 * and the words spoken in their clips. Modelled after the audiobook settings of Marginalia, with only what Joi needs.
 */
public class TtsSettings {

    /** The key in the attributes of the user setting. */
    public static final String KEY = "tts";

    private List<TtsModel> models = new ArrayList<>();
    private List<Actor> actors = new ArrayList<>();
    /** The words spoken in a reference clip, by the uuid of its resource (Higgs can be given them with the clip). */
    private Map<String, String> clipTexts = new LinkedHashMap<>();

    public List<TtsModel> getModels() {
        if (models == null) {
            models = new ArrayList<>();
        }
        return models;
    }

    public List<Actor> getActors() {
        if (actors == null) {
            actors = new ArrayList<>();
        }
        return actors;
    }

    public Map<String, String> getClipTexts() {
        if (clipTexts == null) {
            clipTexts = new LinkedHashMap<>();
        }
        return clipTexts;
    }

    /** The model with the id, null when there is none. */
    public TtsModel findModel(String id) {
        return id == null ? null : getModels().stream().filter(m -> id.equals(m.getId())).findFirst().orElse(null);
    }

    /** The actor with the id, null when there is none. */
    public Actor findActor(String id) {
        return id == null ? null : getActors().stream().filter(a -> id.equals(a.getId())).findFirst().orElse(null);
    }

    /** Puts the model in place of the one with its id, or at the end. */
    public void putModel(TtsModel model) {
        put(getModels(), model, model.getId(), TtsModel::getId);
    }

    /** Puts the actor in place of the one with its id, or at the end. */
    public void putActor(Actor actor) {
        put(getActors(), actor, actor.getId(), Actor::getId);
    }

    private static <T> void put(List<T> list, T item, String id, java.util.function.Function<T, String> idOf) {
        for (int i = 0; i < list.size(); i++) {
            if (idOf.apply(list.get(i)).equals(id)) {
                list.set(i, item);
                return;
            }
        }
        list.add(item);
    }
}
