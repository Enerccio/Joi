package com.github.enerccio.joi.domain.persona;

import com.github.enerccio.joi.tts.text.Delivery;

/**
 * What the user sets for one mood, by its key: a built-in one they changed (a blank or missing value keeps the built-in
 * one) or one of their own. Kept in the user setting ({@code JoiUserSetting.getMoods()}).
 */
public class MoodSetting {

    private String key;
    private String name;
    private String prompt;
    /** The weight in the daily roll, null for the built-in one. */
    private Integer weight;
    /** How the voice reads in it, null for the built-in one. */
    private Delivery delivery;

    public MoodSetting() {
    }

    public MoodSetting(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public Integer getWeight() {
        return weight;
    }

    public void setWeight(Integer weight) {
        this.weight = weight;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public void setDelivery(Delivery delivery) {
        this.delivery = delivery;
    }
}
