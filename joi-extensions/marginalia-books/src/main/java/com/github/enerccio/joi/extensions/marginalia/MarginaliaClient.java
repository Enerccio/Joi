package com.github.enerccio.joi.extensions.marginalia;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Marginalia's HTTP API ({@code /api/v1}, see Marginalia's manual → HTTP API), as the user's key sees it: the books ({@code GET books}),
 * the commands that only read ({@code GET commands}, those marked {@code readOnly}; their arguments as JSON Schemas from
 * {@code GET openapi.json}), and a command run on a book ({@code POST books/<uuid>/commands/<name>}, waited for, then its run followed
 * with {@code GET runs/<id>}). A command that writes is never run: it is not in the list, whatever the key may do.
 */
public final class MarginaliaClient {

    /** How long one call waits for its command before it answers with a run (Marginalia allows up to 300). */
    static final int WAIT_SECONDS = 30;
    /** The most of a result the model gets, in characters. */
    static final int MAX_RESULT = 40_000;

    /**
     * A book of the user.
     *
     * @param folder          the path of the folder it is directly in (names from the top folder, separated by {@code /}), null for none
     * @param extendedApiOnly marked <i>Only in the extended API</i> in Marginalia: left out of the list unless all books are asked for
     */
    public record Book(String uuid, String name, String folder, boolean extendedApiOnly) {
    }

    /**
     * A command that only reads.
     *
     * @param arguments the JSON Schema of its arguments (an object), from the OpenAPI description
     */
    public record Command(String name, String title, String description, JsonObject arguments) {
    }

    private final String base;
    private final String key;
    private final HttpClient http;
    private final Gson gson = new Gson();

    /**
     * @param url the address of Marginalia, with or without {@code /api/v1}
     * @param key a key made in Marginalia → Settings → API keys ({@code READ} is enough)
     */
    public MarginaliaClient(String url, String key) {
        this(url, key, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    MarginaliaClient(String url, String key, HttpClient http) {
        this.base = apiBase(url);
        this.key = StringUtils.strip(key);
        this.http = http;
        if (StringUtils.isBlank(this.key)) {
            throw new IllegalArgumentException("No API key");
        }
    }

    /** The address of the API: the address given, with {@code /api/v1} added when it is not there. */
    static String apiBase(String url) {
        String address = StringUtils.strip(url);
        if (StringUtils.isBlank(address)) {
            throw new IllegalArgumentException("No address of Marginalia");
        }
        URI uri = URI.create(address);
        if (uri.getScheme() == null || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("The address of Marginalia is http:// or https://: " + address);
        }
        address = StringUtils.removeEnd(address, "/");
        return address.endsWith("/api/v1") ? address : address + "/api/v1";
    }

    /** The books of the user, as Marginalia lists them by default: without the ones marked <i>Only in the extended API</i>. */
    public List<Book> books() throws MarginaliaException {
        return books(false);
    }

    /**
     * The books of the user.
     *
     * @param all also the books marked <i>Only in the extended API</i> ({@code ?all=true}); without it Marginalia leaves them out
     */
    public List<Book> books(boolean all) throws MarginaliaException {
        JsonElement answer = get(all ? "books?all=true" : "books");
        List<Book> books = new ArrayList<>();
        if (answer.isJsonArray()) {
            for (JsonElement element : answer.getAsJsonArray()) {
                JsonObject book = element.getAsJsonObject();
                // folderPath and folderName since Marginalia 1.9.2; before, folder was all there was
                String folder = StringUtils.firstNonBlank(text(book, "folderPath"), text(book, "folderName"), text(book, "folder"));
                JsonElement hidden = book.get("extendedApiOnly");
                books.add(new Book(text(book, "uuid"), text(book, "name"), folder,
                        hidden != null && hidden.isJsonPrimitive() && hidden.getAsBoolean()));
            }
        }
        return books;
    }

    /**
     * The book the words name: its uuid, else its name (case does not matter), else the one name that contains them. Looked for among
     * the books of the default list first, then among all of them (a book kept out of the list is still found when she names it).
     */
    public Book book(String nameOrUuid) throws MarginaliaException {
        String wanted = StringUtils.strip(nameOrUuid);
        if (StringUtils.isBlank(wanted)) {
            throw new MarginaliaException(400, "Name the book");
        }
        List<Book> listed = books(false);
        Book found = find(wanted, listed);
        if (found != null) {
            return found;
        }
        List<Book> all = books(true);
        if (all.size() > listed.size()) {
            found = find(wanted, all);
            if (found != null) {
                return found;
            }
        }
        throw notFound(wanted, listed);
    }

    /** The one book of the list the words name, null when none or several do. */
    private static Book find(String wanted, List<Book> books) {
        for (Book book : books) {
            if (wanted.equals(book.uuid())) {
                return book;
            }
        }
        List<Book> named = books.stream().filter(book -> wanted.equalsIgnoreCase(StringUtils.strip(book.name()))).toList();
        if (named.size() == 1) {
            return named.getFirst();
        }
        List<Book> containing = books.stream().filter(book -> StringUtils.containsIgnoreCase(book.name(), wanted)).toList();
        return named.isEmpty() && containing.size() == 1 ? containing.getFirst() : null;
    }

    private static MarginaliaException notFound(String wanted, List<Book> books) {
        List<Book> named = books.stream().filter(book -> wanted.equalsIgnoreCase(StringUtils.strip(book.name()))).toList();
        List<Book> candidates = named.isEmpty() ? books.stream().filter(book -> StringUtils.containsIgnoreCase(book.name(), wanted)).toList() : named;
        String list = (candidates.isEmpty() ? books : candidates).stream().map(Book::name).limit(30).reduce((a, b) -> a + "; " + b).orElse("none");
        return new MarginaliaException(404, (candidates.isEmpty() ? "No book \"" + wanted + "\". The books: " : "Several books match \"" + wanted
                + "\" (give its uuid): ") + list);
    }

    /** The commands that only read, by name, each with the schema of its arguments. */
    public List<Command> readOnlyCommands() throws MarginaliaException {
        JsonElement listed = get("commands");
        JsonObject spec = get("openapi.json").getAsJsonObject();
        JsonObject paths = spec.has("paths") && spec.get("paths").isJsonObject() ? spec.getAsJsonObject("paths") : new JsonObject();
        List<Command> commands = new ArrayList<>();
        if (!listed.isJsonArray()) {
            return commands;
        }
        for (JsonElement element : listed.getAsJsonArray()) {
            JsonObject command = element.getAsJsonObject();
            if (!command.has("readOnly") || !command.get("readOnly").getAsBoolean()) {
                continue;
            }
            String name = text(command, "name");
            if (name == null) {
                continue;
            }
            commands.add(new Command(name, text(command, "title"), text(command, "description"), arguments(paths, name, command)));
        }
        return commands;
    }

    /** The schema of the arguments of a command: from the OpenAPI description, else made from the forms {@code GET commands} gives. */
    private static JsonObject arguments(JsonObject paths, String name, JsonObject command) {
        JsonElement path = paths.get("/books/{book}/commands/" + name);
        try {
            JsonObject schema = path.getAsJsonObject().getAsJsonObject("post").getAsJsonObject("requestBody").getAsJsonObject("content")
                    .getAsJsonObject("application/json").getAsJsonObject("schema").getAsJsonObject("properties").getAsJsonObject("arguments");
            if (schema != null) {
                return schema.deepCopy();
            }
        } catch (RuntimeException e) {
            // not described: made from the forms below
        }
        JsonArray forms = command.has("forms") && command.get("forms").isJsonArray() ? command.getAsJsonArray("forms") : new JsonArray();
        JsonArray schemas = new JsonArray();
        for (JsonElement form : forms) {
            schemas.add(form(form.getAsJsonObject()));
        }
        if (schemas.size() == 1) {
            return schemas.get(0).getAsJsonObject();
        }
        JsonObject schema = new JsonObject();
        if (schemas.isEmpty()) {
            schema.addProperty("type", "object");
            schema.addProperty("additionalProperties", true);
        } else {
            // as the OpenAPI description has it: one schema per form
            schema.add("oneOf", schemas);
        }
        return schema;
    }

    /** The schema of one form of a command, from {@code GET commands}. */
    private static JsonObject form(JsonObject form) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        if (form.has("anyArguments") && form.get("anyArguments").getAsBoolean() || !form.has("parameters")) {
            schema.addProperty("additionalProperties", true);
            return schema;
        }
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (JsonElement p : form.getAsJsonArray("parameters")) {
            JsonObject parameter = p.getAsJsonObject();
            JsonObject property = new JsonObject();
            property.addProperty("type", jsonType(text(parameter, "type")));
            if (text(parameter, "description") != null) {
                property.addProperty("description", text(parameter, "description"));
            }
            properties.add(text(parameter, "name"), property);
            if (parameter.has("optional") && !parameter.get("optional").getAsBoolean()) {
                required.add(text(parameter, "name"));
            }
        }
        schema.add("properties", properties);
        if (!required.isEmpty()) {
            schema.add("required", required);
        }
        return schema;
    }

    private static String jsonType(String type) {
        String lower = StringUtils.defaultString(type).toLowerCase(Locale.ROOT);
        if (lower.matches("int|integer|long|short|byte")) {
            return "integer";
        }
        if (lower.matches("double|float|number|bigdecimal")) {
            return "number";
        }
        return lower.equals("boolean") ? "boolean" : "string";
    }

    /**
     * Runs a command that only reads on a book and gives what it returns, as text (at most {@link #MAX_RESULT} characters).
     *
     * @param cancelled asked while it waits: true stops the run
     * @param maxWait   how long it may take in all; a run still going then is stopped
     */
    public String run(String bookUuid, String command, Map<String, Object> arguments, BooleanSupplier cancelled, Duration maxWait)
            throws MarginaliaException, InterruptedException {
        JsonObject body = new JsonObject();
        body.add("arguments", gson.toJsonTree(arguments == null ? Map.of() : arguments));
        body.addProperty("wait", WAIT_SECONDS);
        long until = System.currentTimeMillis() + maxWait.toMillis();
        JsonObject answer = send("POST", "books/" + encode(bookUuid) + "/commands/" + encode(command), body.toString(),
                Duration.ofSeconds(WAIT_SECONDS + 30)).getAsJsonObject();
        while ("running".equals(text(answer, "status"))) {
            String run = text(answer, "run");
            if (run == null) {
                throw new MarginaliaException(0, "Marginalia gave no run to follow");
            }
            if (cancelled.getAsBoolean() || System.currentTimeMillis() > until) {
                try {
                    send("DELETE", "runs/" + encode(run), null, Duration.ofSeconds(30));
                } catch (MarginaliaException e) {
                    // it ends on its own
                }
                throw new MarginaliaException(0, cancelled.getAsBoolean() ? "Stopped" : "The command took too long");
            }
            Thread.sleep(1000);
            answer = send("GET", "runs/" + encode(run), null, Duration.ofSeconds(30)).getAsJsonObject();
        }
        String status = text(answer, "status");
        if (!"done".equals(status)) {
            throw new MarginaliaException(0, "The command " + command + " " + StringUtils.defaultString(status, "failed")
                    + (text(answer, "error") == null ? "" : ": " + text(answer, "error")));
        }
        JsonElement result = answer.get("result");
        String text = result == null || result.isJsonNull() ? "(nothing)" : result.isJsonPrimitive() ? result.getAsString() : result.toString();
        return text.length() <= MAX_RESULT ? text : text.substring(0, MAX_RESULT) + "\n[cut here: the text goes on; ask for a smaller part]";
    }

    // ----------------------------------------------------------------------------------------------------------------

    private JsonElement get(String path) throws MarginaliaException {
        return send("GET", path, null, Duration.ofSeconds(60));
    }

    /** One call; an answer that is not 2xx is an exception with what Marginalia said. A {@code 202} of a command is a running run. */
    private JsonElement send(String method, String path, String body, Duration timeout) throws MarginaliaException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/" + path)).timeout(timeout)
                .header("Authorization", "Bearer " + key).header("Accept", "application/json");
        if (body != null) {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new MarginaliaException("Marginalia could not be reached at " + base + ": " + StringUtils.defaultIfBlank(e.getMessage(),
                    e.getClass().getSimpleName()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MarginaliaException("Interrupted", e);
        }
        int status = response.statusCode();
        JsonElement json = parse(response.body());
        if (status == 200 || status == 202 || status == 204) {
            return json;
        }
        String error = json.isJsonObject() ? text(json.getAsJsonObject(), "error") : null;
        throw new MarginaliaException(status, switch (status) {
            case 401 -> "Marginalia does not take the key (401): make a new one in Marginalia → Settings → API keys";
            case 403 -> "The key may not do that (403)" + (error == null ? "" : ": " + error) + ". It needs the scope READ";
            default -> "Marginalia answered " + status + (error == null ? "" : ": " + error);
        });
    }

    private static JsonElement parse(String body) {
        if (StringUtils.isBlank(body)) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(body);
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static String text(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    private static String encode(String part) {
        return URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20");
    }

}
