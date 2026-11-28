package com.github.enerccio.joi.extensions.marginalia;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.tools.ToolPermission;
import com.github.enerccio.joi.extensions.ExtensionTask;
import com.github.enerccio.joi.extensions.ExtensionTool;
import com.github.enerccio.joi.extensions.JoiExtensionContext;
import com.github.enerccio.marginalia.domain.service.function.FunctionDefinition;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code marginalia_read_book}: she asks a question about one of the user's books; a task of the stronger model answers it. The model
 * gets every command of Marginalia that only reads as a function bound to that book (taken from Marginalia's own description of its API
 * when the task runs, so a new command of Marginalia reaches it without a new plugin). It asks the user by default: the user can allow
 * it for good (the Tools pages, or "approve all" in her Tasks tab).
 */
public class ReadBookTask implements ExtensionTask {

    static final ExtensionTool TOOL = new ExtensionTool("marginalia_read_book", "Marginalia: read a book",
            "Reads one of the user's books in Marginalia and answers a question about it", ToolPermission.ASK);
    /** How many rounds of calls the model has to read what it needs. */
    static final int MAX_CALLS = 16;
    /** How long one command may take, its run followed. */
    static final Duration COMMAND_TIME = Duration.ofMinutes(5);

    /** Gives the client of the settings in force for the assistant. */
    interface Clients {
        MarginaliaClient client(JoiExtensionContext context, Joi joi) throws Exception;
    }

    private final JoiExtensionContext context;
    private final Clients clients;

    ReadBookTask(JoiExtensionContext context, Clients clients) {
        this.context = context;
        this.clients = clients;
    }

    @Override
    public ExtensionTool tool() {
        return TOOL;
    }

    @Override
    public String description() {
        return "Reads one of the user's books in Marginalia, their writing application, and answers a question about it: what happens "
                + "in a chapter, where a character appears, how the story goes on, a summary. Name the book as "
                + MarginaliaBooksExtension.BOOKS.function() + " lists it (its name or id) and say what you want to know.";
    }

    @Override
    public ObjectNode parameters() {
        ObjectNode schema = new ObjectMapper().createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("book").put("type", "string").put("description", "The book: its name or its id.");
        properties.putObject("question").put("type", "string").put("description",
                "What you want to know from the book, as a whole question ('what happens to Mara in chapter 3', not 'chapter 3').");
        schema.putArray("required").add("book").add("question");
        return schema;
    }

    @Override
    public boolean offered(Joi joi) throws Exception {
        return MarginaliaBooksExtension.configured(context.settings(joi));
    }

    @Override
    public String check(Map<String, Object> arguments) {
        if (!(arguments.get("book") instanceof String book) || book.isBlank()) {
            return "Name the book (" + MarginaliaBooksExtension.BOOKS.function() + " lists them)";
        }
        return question(arguments) == null ? "Say what you want to know from the book" : null;
    }

    @Override
    public String title(Map<String, Object> arguments) {
        return StringUtils.strip(String.valueOf(arguments.get("book"))) + ": " + question(arguments);
    }

    @Override
    public Work prepare(Joi joi, Map<String, Object> arguments) throws Exception {
        MarginaliaClient client = clients.client(context, joi);
        MarginaliaClient.Book book = client.book(String.valueOf(arguments.get("book")));
        List<MarginaliaClient.Command> commands = client.readOnlyCommands();
        if (commands.isEmpty()) {
            throw new IllegalStateException("Marginalia offers this key no command that reads (the key needs the scope READ)");
        }
        List<FunctionDefinition> functions = new ArrayList<>();
        Set<String> names = new HashSet<>();
        StringBuilder list = new StringBuilder();
        for (MarginaliaClient.Command command : commands) {
            String name = functionName(command.name(), names);
            String what = StringUtils.firstNonBlank(command.description(), command.title(), command.name());
            functions.add(FunctionDefinition.dynamic(name, what, schema(command.arguments()), (continuation, args) -> {
                try {
                    continuation.complete(client.run(book.uuid(), command.name(), args, continuation::isCancelled, COMMAND_TIME));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    continuation.fail("Interrupted");
                } catch (Exception e) {
                    continuation.fail(StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
                }
            }));
            list.append("\n- ").append(name);
            if (StringUtils.isNotBlank(command.title()) && !command.title().equals(command.name())) {
                list.append(": ").append(command.title());
            }
        }
        String guidance = "The book is \"" + book.name() + "\"" + (StringUtils.isBlank(book.folder()) ? "" : " (folder " + book.folder() + ")")
                + " in Marginalia, the user's writing application. Your functions are Marginalia's commands that only read, each run "
                + "on this book:" + list + "\nFind your way first (the book's outline, its chapters or parts), then read only what the "
                + "question needs. A long result is cut; ask for a smaller part then.";
        return new Work(guidance, functions, MAX_CALLS);
    }

    /** The name of a function: the command's, with what a model does not take in a name made {@code _}, unique, at most 64. */
    static String functionName(String command, Set<String> taken) {
        String base = StringUtils.left(command.replaceAll("[^A-Za-z0-9_-]", "_"), 60);
        String name = base;
        for (int i = 2; !taken.add(name); i++) {
            name = base + "_" + i;
        }
        return name;
    }

    /**
     * The JSON Schema of a command's arguments for a function: an object. A command with several forms ({@code oneOf}) gets one object
     * with the properties of all of them, required only what every form requires, and its forms named in the description, so the model
     * sees the names it may use (Marginalia picks the form by the names given).
     */
    static ObjectNode schema(JsonObject arguments) throws Exception {
        ObjectMapper jackson = new ObjectMapper();
        ObjectNode schema = arguments == null ? jackson.createObjectNode() : (ObjectNode) jackson.readTree(arguments.toString());
        if (schema.has("oneOf") && schema.get("oneOf").isArray()) {
            ObjectNode merged = jackson.createObjectNode().put("type", "object");
            ObjectNode properties = merged.putObject("properties");
            java.util.Set<String> required = null;
            List<String> forms = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode form : schema.get("oneOf")) {
                java.util.Set<String> names = new java.util.LinkedHashSet<>();
                form.path("properties").properties().forEach(property -> {
                    names.add(property.getKey());
                    if (!properties.has(property.getKey())) {
                        properties.set(property.getKey(), property.getValue().deepCopy());
                    }
                });
                java.util.Set<String> own = new java.util.LinkedHashSet<>();
                form.path("required").forEach(name -> own.add(name.asText()));
                if (required == null) {
                    required = own;
                } else {
                    required.retainAll(own);
                }
                forms.add("(" + String.join(", ", names) + ")");
            }
            if (required != null && !required.isEmpty()) {
                com.fasterxml.jackson.databind.node.ArrayNode array = merged.putArray("required");
                required.forEach(array::add);
            }
            merged.put("description", "Give the arguments of one of its forms: " + String.join(" or ", forms) + ".");
            return merged;
        }
        if (!"object".equals(schema.path("type").asText("object"))) {
            ObjectNode any = jackson.createObjectNode().put("type", "object").put("additionalProperties", true);
            any.put("description", "The arguments by name: " + schema);
            return any;
        }
        schema.put("type", "object");
        if (!schema.has("properties")) {
            schema.putObject("properties");
        }
        return schema;
    }
}
