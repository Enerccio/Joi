package com.github.enerccio.joi.api;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A call of the API as {@link JoiApi} sees it, apart from the servlet (so it is tested without HTTP).
 *
 * @param path    the path after the context ({@code /api/v1/jois})
 * @param query   the parameters of the query string, the first value of each
 * @param headers the headers, names in lower case
 * @param baseUrl the URL of the server as the client reached it ({@code http://host:port}, no slash at the end)
 * @param stream  the body as a stream, for a call that is read to the disk (an image upload: {@code body} is empty then), null otherwise
 * @param local   whether it arrived on the server's local socket (see {@link ApiConfiguration#getLocalSocket()}), so it is from the
 *                machine the server runs on
 */
public record ApiCall(String method, String path, Map<String, String> query, Map<String, String> headers, byte[] body, String baseUrl,
                      boolean local, java.io.InputStream stream) {

    public ApiCall(String method, String path, Map<String, String> query, Map<String, String> headers, byte[] body, String baseUrl) {
        this(method, path, query, headers, body, baseUrl, false, null);
    }

    public ApiCall(String method, String path, Map<String, String> query, Map<String, String> headers, byte[] body, String baseUrl,
                   boolean local) {
        this(method, path, query, headers, body, baseUrl, local, null);
    }

    /** The body of a call that is streamed to the disk instead of read whole (an upload): the stream, or the bytes of a call made without HTTP. */
    public java.io.InputStream bodyStream() {
        return stream != null ? stream : new java.io.ByteArrayInputStream(body);
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    public String text() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** The body as a form ({@code application/x-www-form-urlencoded}), with the query's parameters under it. */
    public Map<String, String> form() {
        Map<String, String> form = new HashMap<>(parse(text()));
        query.forEach(form::putIfAbsent);
        return form;
    }

    /** The parameters of a query string or a form. */
    public static Map<String, String> parse(String text) {
        Map<String, String> values = new HashMap<>();
        if (text == null || text.isBlank()) {
            return values;
        }
        for (String pair : text.strip().split("&")) {
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            if (!name.isEmpty()) {
                values.putIfAbsent(name, value);
            }
        }
        return values;
    }
}
