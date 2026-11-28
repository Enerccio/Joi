package com.github.enerccio.joi.extensions.marginalia;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.tools.ToolPermission;
import com.github.enerccio.joi.extensions.ExtensionConfig;
import com.github.enerccio.joi.extensions.ExtensionSetting;
import com.github.enerccio.joi.extensions.ExtensionSettings;
import com.github.enerccio.joi.extensions.ExtensionTool;
import com.github.enerccio.joi.extensions.JoiExtension;
import com.github.enerccio.joi.extensions.JoiExtensionContext;
import com.github.enerccio.marginalia.domain.service.function.FunctionDefinition;
import org.apache.commons.lang3.StringUtils;
import org.osgi.framework.Bundle;

import java.util.List;
import java.util.function.BiFunction;

/**
 * Marginalia's books: she reads the user's books in Marginalia (the user's writing application) through its HTTP API, with a key the
 * user makes there with the scope {@code READ}. Three functions: {@code marginalia_books} lists the books and
 * {@code marginalia_read_part} ({@link ReadPart}) lists a book's parts or reads one, a page at a time, both in her turn; and
 * {@code marginalia_read_book} is a task ({@link ReadBookTask}): the stronger model reads the book with Marginalia's read-only commands
 * and brings back the answer, so a long read never lands in her prompt. Nothing writes into a book.
 */
public class MarginaliaBooksExtension implements JoiExtension {

    static final String URL = "url";
    static final String API_KEY = "apiKey";

    static final ExtensionSettings SETTINGS = new ExtensionSettings("Marginalia's books",
            "Where your Marginalia is and the key she reads your books with: make it in Marginalia → Settings → API keys, with the scope READ only.",
            List.of(ExtensionSetting.text(URL, "Marginalia's address", "The address you open Marginalia at, e.g. https://marginalia.example.org"),
                    ExtensionSetting.secret(API_KEY, "API key (READ)", "mrg_... made in Marginalia → Settings → API keys, scope READ")));

    static final ExtensionTool BOOKS = new ExtensionTool("marginalia_books", "Marginalia: list the books",
            "Lists the user's books in Marginalia", ToolPermission.ALLOW);

    /** Makes the client of the settings; the tests give their own. */
    private final BiFunction<String, String, MarginaliaClient> clients;

    public MarginaliaBooksExtension() {
        this(MarginaliaClient::new);
    }

    MarginaliaBooksExtension(BiFunction<String, String, MarginaliaClient> clients) {
        this.clients = clients;
    }

    @Override
    public void onJoiLoad(Bundle bundle, JoiExtensionContext context) {
        context.declareSettings(SETTINGS);
        context.declareTool(BOOKS);
        context.declareTool(ReadPart.TOOL);
        context.offerTools(joi -> configured(context.settings(joi))
                ? List.of(books(context, joi), ReadPart.function(context, joi, this::client)) : List.of());
        context.offerTask(new ReadBookTask(context, this::client));
        context.log().info("Marginalia's books are offered to the assistants whose user set Marginalia's address and key");
    }

    /** Whether the address and the key are set. */
    static boolean configured(ExtensionConfig config) {
        return StringUtils.isNotBlank(config.value(URL)) && StringUtils.isNotBlank(config.value(API_KEY));
    }

    /** The client of the settings in force for the assistant. */
    MarginaliaClient client(JoiExtensionContext context, Joi joi) throws Exception {
        ExtensionConfig config = context.settings(joi);
        if (!configured(config)) {
            throw new IllegalStateException("Marginalia is not set up: the user sets its address and key in User Settings → Tools");
        }
        return clients.apply(config.value(URL), config.value(API_KEY));
    }

    private FunctionDefinition books(JoiExtensionContext context, Joi joi) {
        ObjectNode schema = new ObjectMapper().createObjectNode().put("type", "object");
        schema.putObject("properties").putObject("all").put("type", "boolean").put("description",
                "Also the books the user keeps out of the list (marked 'Only in the extended API' in Marginalia: drafts, archived books). "
                        + "Leave it out unless the user asks for those.");
        return FunctionDefinition.dynamic(BOOKS.function(), "Lists the user's books in Marginalia, their writing application: name, folder "
                + "and id. To read a part of one, use " + ReadPart.TOOL.function() + "; to ask about a whole book, "
                + ReadBookTask.TOOL.function() + ".", schema, (continuation, args) -> {
            try {
                boolean all = Boolean.TRUE.equals(args.get("all")) || "true".equalsIgnoreCase(String.valueOf(args.get("all")));
                List<MarginaliaClient.Book> books = client(context, joi).books(all);
                if (books.isEmpty()) {
                    continuation.complete("The user has no books in Marginalia.");
                    return;
                }
                StringBuilder out = new StringBuilder("The user's books in Marginalia (name — folder — id):");
                for (MarginaliaClient.Book book : books) {
                    out.append("\n- ").append(book.name()).append(" — ").append(StringUtils.defaultIfBlank(book.folder(), "(no folder)"))
                            .append(" — ").append(book.uuid());
                    if (book.extendedApiOnly()) {
                        out.append(" (kept out of the list)");
                    }
                }
                continuation.complete(out.toString());
            } catch (Exception e) {
                context.log().warn("The books could not be listed: {}", e.getMessage());
                continuation.fail(StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            }
        });
    }
}
