package com.github.enerccio.joi.protocol.http;

/**
 * How a reply went.
 *
 * @param promptTokens     the tokens of the prompt
 * @param completionTokens the tokens the model wrote
 * @param firstTokenMs     from the request to the first token of the model, -1 when not known
 * @param textMs           from the request to the end of the text
 * @param speechMs         how long the speech took, -1 for none
 */
public record Usage(long promptTokens, long completionTokens, long firstTokenMs, long textMs, long speechMs) {
}
