package com.github.enerccio.joi.protocol.http;

/**
 * The {@code done} event: the last one of a stream that went well.
 *
 * @param messageId   the id of the stored message
 * @param speechError why some or all audio is missing, null when nothing is
 */
public record Done(String messageId, String speechError) {
}
