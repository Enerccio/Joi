package com.github.enerccio.joi.protocol.http;

import com.google.gson.annotations.SerializedName;

/**
 * A part of a body's model described apart, which can be switched, and the state it is in.
 *
 * @param id        the part's id
 * @param name      the part's name, as the user named it
 * @param state     the id of the state it is in (for a single toggle {@code shown} or {@code hidden})
 * @param stateName the name of that state
 * @param isDefault whether that is the part's default state
 */
public record BodyPart(String id, String name, String state, String stateName, @SerializedName("default") boolean isDefault) {
}
