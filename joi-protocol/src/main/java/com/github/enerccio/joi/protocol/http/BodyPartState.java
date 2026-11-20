package com.github.enerccio.joi.protocol.http;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * A state of a part (since 1.5): what a node sets on the model to put the part in it.
 *
 * @param id        the state's id ({@code shown} or {@code hidden} for a single toggle)
 * @param name      its name
 * @param isDefault whether it is the part's default state
 * @param toggles   the part's toggles as this state sets them
 */
public record BodyPartState(String id, String name, @SerializedName("default") boolean isDefault, List<BodyToggle> toggles) {
}
