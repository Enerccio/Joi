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
    /** The expression on her face: a preset ({@code happy}...), {@code none} for none, null for the built-in one. */
    private String expression;
    /** How strong it is, 0 to 1; null for the built-in one. */
    private Double expressionWeight;
    /** Her eyes in it: {@code half}, {@code wide}, {@code auto} for open, null for the built-in one. */
    private String eyes;

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

    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public Double getExpressionWeight() {
        return expressionWeight;
    }

    public void setExpressionWeight(Double expressionWeight) {
        this.expressionWeight = expressionWeight;
    }

    public String getEyes() {
        return eyes;
    }

    public void setEyes(String eyes) {
        this.eyes = eyes;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public void setDelivery(Delivery delivery) {
        this.delivery = delivery;
    }
}
