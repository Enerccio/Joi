package com.github.enerccio.joi.domain.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * What a search or a page is fetched with: one client with the proxy (an HTTP(S) one, with a user and a password when it
 * asks), the timeout and what the requests say they are. A status that is not 2xx is a {@link WebException} with a short
 * message; a body is read up to a limit, so a page that never ends cannot fill the memory. Redirects of a GET are
 * followed here, by hand, so each address can be checked ({@code allowed}).
 */
public final class WebHttp {

    static {
        // the JDK will not send a proxy's password on a CONNECT (an HTTPS request through an HTTP proxy) unless asked to
        if (System.getProperty("jdk.http.auth.tunneling.disabledSchemes") == null) {
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        }
    }

    public static final String USER_AGENT = "Mozilla/5.0 (compatible; Joi/1.0; +https://github.com/Enerccio/joi)";
    /** The most of a body that is read, in bytes. */
    public static final int MAX_BODY = 4 * 1024 * 1024;
    private static final int REDIRECTS = 5;

    /** What was answered. */
    public record Response(int status, String body, URI uri, String contentType) {
    }

    private final HttpClient client;
    private final Duration timeout;
    private final String userAgent;

    /**
     * @param proxy     {@code http://host:port} (or https), blank for none
     * @param proxyAuth {@code user:password} for the proxy, blank for none
     */
    public WebHttp(String proxy, String proxyAuth, int timeoutSeconds, String userAgent) throws WebException {
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.userAgent = StringUtils.defaultIfBlank(userAgent, USER_AGENT);
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER);
        if (StringUtils.isNotBlank(proxy)) {
            URI uri;
            try {
                uri = URI.create(proxy.strip());
            } catch (IllegalArgumentException e) {
                throw new WebException("The proxy is not an address: " + proxy.strip());
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (uri.getHost() == null || !(scheme.equals("http") || scheme.equals("https"))) {
                throw new WebException("The proxy has to be http://host:port (an HTTP proxy)");
            }
            builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 8080)));
            if (StringUtils.isNotBlank(proxyAuth) && proxyAuth.contains(":")) {
                String user = StringUtils.substringBefore(proxyAuth, ":");
                char[] password = StringUtils.substringAfter(proxyAuth, ":").toCharArray();
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return getRequestorType() == RequestorType.PROXY ? new PasswordAuthentication(user, password) : null;
                    }
                });
            }
        }
        this.client = builder.build();
    }

    /** A GET; redirects are followed (up to 5) while {@code allowed} accepts where they lead. */
    public Response get(URI uri, Map<String, String> headers, Consumer<URI> allowed) throws WebException {
        URI current = uri;
        for (int hop = 0; hop <= REDIRECTS; hop++) {
            if (allowed != null) {
                allowed.accept(current);
            }
            Sent sent = send(request(current, headers).GET().build(), true);
            if (sent.location() != null) {
                current = current.resolve(sent.location());
                continue;
            }
            return sent.response();
        }
        throw new WebException("Too many redirects from " + uri.getHost());
    }

    /** A POST of JSON. */
    public Response postJson(URI uri, String json, Map<String, String> headers) throws WebException {
        Map<String, String> all = new LinkedHashMap<>(headers == null ? Map.of() : headers);
        all.put("Content-Type", "application/json");
        all.putIfAbsent("Accept", "application/json");
        return send(request(uri, all).POST(HttpRequest.BodyPublishers.ofString(json)).build(), false).response();
    }

    /** A POST of a form. */
    public Response postForm(URI uri, Map<String, String> form, Map<String, String> headers) throws WebException {
        Map<String, String> all = new LinkedHashMap<>(headers == null ? Map.of() : headers);
        all.put("Content-Type", "application/x-www-form-urlencoded");
        String body = form.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(Collectors.joining("&"));
        return send(request(uri, all).POST(HttpRequest.BodyPublishers.ofString(body)).build(), false).response();
    }

    public static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private HttpRequest.Builder request(URI uri, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout).header("User-Agent", userAgent);
        if (headers != null) {
            headers.forEach(builder::header);
        }
        return builder;
    }

    /** The answer, and where it sends the client on (a redirect), null otherwise. */
    private record Sent(Response response, String location) {
    }

    private Sent send(HttpRequest request, boolean redirects) throws WebException {
        HttpResponse<InputStream> answer;
        try {
            answer = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebException("Stopped while waiting for " + request.uri().getHost(), e);
        } catch (IOException e) {
            throw new WebException("Could not reach " + request.uri().getHost() + ": " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()), e);
        }
        String type = answer.headers().firstValue("Content-Type").orElse("");
        String body;
        try (InputStream in = answer.body()) {
            body = read(in, charset(type));
        } catch (IOException e) {
            throw new WebException("Could not read the answer of " + request.uri().getHost() + ": " + e.getMessage(), e);
        }
        int status = answer.statusCode();
        String location = answer.headers().firstValue("Location").orElse(null);
        if (redirects && status / 100 == 3 && location != null) {
            return new Sent(new Response(status, "", request.uri(), type), location);
        }
        if (status / 100 != 2) {
            throw new WebException(request.uri().getHost() + " answered " + status + errorText(body), status, null);
        }
        return new Sent(new Response(status, body, request.uri(), type), null);
    }

    private static String read(InputStream in, Charset charset) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = in.read(buffer)) > 0 && total < MAX_BODY) {
            int take = Math.min(count, MAX_BODY - total);
            out.write(buffer, 0, take);
            total += take;
        }
        return out.toString(charset);
    }

    private static Charset charset(String contentType) {
        String name = StringUtils.substringAfter(contentType.toLowerCase(Locale.ROOT), "charset=");
        name = StringUtils.substringBefore(name, ";").strip().replace("\"", "");
        try {
            return name.isEmpty() ? StandardCharsets.UTF_8 : Charset.forName(name);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * What the other side said was wrong, short: the first of {@code error.message}, {@code detail.error}, {@code error}
     * and {@code message} of a JSON body, else the start of the text. Empty for no body. The providers' own formats are
     * not relied on; this only makes a good message of the ones that have a common shape.
     */
    static String errorText(String body) {
        if (StringUtils.isBlank(body)) {
            return "";
        }
        String found = null;
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                found = first(object, "error", "message");
                if (found == null) {
                    found = first(object, "detail", "error");
                }
                if (found == null) {
                    found = text(object.get("error"));
                }
                if (found == null) {
                    found = text(object.get("message"));
                }
                if (found == null) {
                    found = text(object.get("detail"));
                }
            }
        } catch (RuntimeException e) {
            // not JSON: the text itself
        }
        if (found == null) {
            found = body.contains("<") ? null : StringUtils.normalizeSpace(body);
        }
        return found == null ? "" : ": " + StringUtils.abbreviate(StringUtils.normalizeSpace(found), 200);
    }

    private static String first(JsonObject object, String outer, String inner) {
        JsonElement value = object.get(outer);
        return value != null && value.isJsonObject() ? text(value.getAsJsonObject().get(inner)) : null;
    }

    private static String text(JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }
}
