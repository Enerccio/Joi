package com.github.enerccio.joi.domain.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * The options of reading a page: the {@code config} of the {@code fetch_page} settings. The token of a crawl4ai server
 * is the secret {@value #TOKEN_SECRET}.
 *
 * @param type           how a page is read
 * @param url            the address of the crawl4ai server or its proxy (not for {@link PageSourceType#DIRECT})
 * @param local          whether the reader is on the user's own network: the only one used when the web options say
 *                       local only ({@code DIRECT} never is)
 * @param proxy          an HTTP(S) proxy for {@code DIRECT} reads
 * @param timeoutSeconds how long a page may take
 * @param maxChars       how much of a page goes to the model; the rest is cut
 * @param allowPrivate   let {@code DIRECT} reach addresses of the server's own network (localhost, 192.168...); off
 *                       by default, so a page she is led to cannot send the server to its own network
 */
public record PageConfig(PageSourceType type, String url, boolean local, String proxy, int timeoutSeconds, int maxChars,
                         boolean allowPrivate) {

    private static final Gson GSON = new Gson();

    public static final String TOKEN_SECRET = "token";
    public static final int TIMEOUT = 30;
    public static final int MAX_CHARS = 20000;

    public PageConfig {
        type = type == null ? PageSourceType.DIRECT : type;
        timeoutSeconds = timeoutSeconds <= 0 ? TIMEOUT : Math.min(timeoutSeconds, 300);
        maxChars = maxChars <= 0 ? MAX_CHARS : Math.min(maxChars, 200_000);
    }

    /** The options in the JSON of the settings; the defaults when it is empty or cannot be read. */
    public static PageConfig from(JsonObject json) {
        try {
            PageConfig read = json == null ? null : GSON.fromJson(json, PageConfig.class);
            if (read != null) {
                return read;
            }
        } catch (RuntimeException e) {
            // a broken text is no options
        }
        return new PageConfig(null, null, false, null, 0, 0, false);
    }

    /** The JSON to keep. */
    public JsonObject toJson() {
        return GSON.toJsonTree(this).getAsJsonObject();
    }
}
