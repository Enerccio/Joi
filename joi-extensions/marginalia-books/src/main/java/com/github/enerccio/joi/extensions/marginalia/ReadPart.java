package com.github.enerccio.joi.extensions.marginalia;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.tools.ToolPermission;
import com.github.enerccio.joi.extensions.ExtensionTool;
import com.github.enerccio.joi.extensions.JoiExtensionContext;
import com.github.enerccio.marginalia.domain.service.function.FunctionDefinition;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code marginalia_read_part}: she reads a part of a book herself, in her turn, with no task. Without a part it runs Marginalia's
 * {@code list-parts} (the parts of the active branch, with their handles), with one {@code get-part} (its text, a page of
 * {@link #MAX_LENGTH} characters at most). For a question about a whole book {@code marginalia_read_book} is the better way: a chapter
 * read here lands in her prompt.
 */
final class ReadPart {

    static final ExtensionTool TOOL = new ExtensionTool("marginalia_read_part", "Marginalia: read a part of a book",
            "Reads a part of one of the user's books in Marginalia in her turn, or lists its parts", ToolPermission.ALLOW);

    static final String LIST_PARTS = "list-parts";
    static final String GET_PART = "get-part";
    /** Marginalia's default page of a part's text; she may ask for less, never for more. */
    static final int MAX_LENGTH = 6000;
    /** How long a command may run in her turn. */
    static final Duration TIME = Duration.ofSeconds(90);

    private ReadPart() {
    }

    static FunctionDefinition function(JoiExtensionContext context, Joi joi, ReadBookTask.Clients clients) {
        ObjectMapper jackson = new ObjectMapper();
        ObjectNode schema = jackson.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("book").put("type", "string").put("description", "The book: its name or its id, as "
                + MarginaliaBooksExtension.BOOKS.function() + " lists it.");
        properties.putObject("part").put("type", "string").put("description", "The part: its handle from the list, or its position (the "
                + "first part of the story is 1). Leave it out to list the parts.");
        properties.putObject("offset").put("type", "integer").put("minimum", 0).put("description",
                "Where to start: in the part's text (characters), or in the list (parts). 0 when left out.");
        properties.putObject("length").put("type", "integer").put("minimum", 1).put("maximum", MAX_LENGTH).put("description",
                "How many characters of the part's text, at most " + MAX_LENGTH + ".");
        schema.putArray("required").add("book");
        return FunctionDefinition.dynamic(TOOL.function(), "Reads a part of one of the user's books in Marginalia, their writing application, "
                + "right now: without a part it lists the book's parts (position, handle, a line of each), with a part it gives its text, "
                + "a page at a time (the answer says how long the part is; ask again with offset for the rest). For a question about a whole "
                + "book use " + ReadBookTask.TOOL.function() + ".", schema, (continuation, args) -> {
            try {
                Object book = args.get("book");
                if (!(book instanceof String name) || name.isBlank()) {
                    continuation.fail("Name the book (" + MarginaliaBooksExtension.BOOKS.function() + " lists them)");
                    return;
                }
                MarginaliaClient client = clients.client(context, joi);
                MarginaliaClient.Book found = client.book(name);
                String part = args.get("part") == null ? null : StringUtils.trimToNull(String.valueOf(args.get("part")));
                Integer offset = number(args.get("offset"));
                Integer length = number(args.get("length"));
                Map<String, Object> arguments = new LinkedHashMap<>();
                String command;
                if (part == null) {
                    command = LIST_PARTS;
                    if (offset != null && offset > 0) {
                        // the paged form names the branch too: blank is the active one
                        arguments.put("branch", "");
                        arguments.put("offset", offset);
                    }
                } else {
                    command = GET_PART;
                    arguments.put("part", part);
                    if ((offset != null && offset > 0) || length != null) {
                        arguments.put("branch", "");
                        arguments.put("offset", offset == null ? 0 : Math.max(0, offset));
                        if (length != null) {
                            arguments.put("length", Math.clamp(length, 1, MAX_LENGTH));
                        }
                    }
                }
                continuation.complete(client.run(found.uuid(), command, arguments, continuation::isCancelled, TIME));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                continuation.fail("Interrupted");
            } catch (Exception e) {
                context.log().warn("A part could not be read: {}", e.getMessage());
                continuation.fail(StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            }
        });
    }

    private static Integer number(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && StringUtils.isNotBlank(s)) {
            try {
                return Integer.parseInt(s.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
