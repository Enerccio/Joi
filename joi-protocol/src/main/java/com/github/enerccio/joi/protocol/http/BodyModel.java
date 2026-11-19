package com.github.enerccio.joi.protocol.http;

/**
 * A model of a body.
 *
 * @param id   the id of the model
 * @param name its name
 * @param joim its {@code .joim} (the VRM and what was decided on it), null when none was written yet
 */
public record BodyModel(String id, String name, Download joim) {
}
