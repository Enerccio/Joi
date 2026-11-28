package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.marginalia.domain.service.impl.inference.tokenizer.EstimatedImageTokenizer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How many tokens the summaries count for an exchange: its words, and the user's images or the notes that stand for them. */
class SummaryTokensTest {

    private static ChatMessage message(String input, String response) {
        ChatMessage message = new ChatMessage();
        message.setInput(input);
        message.setResponse(response);
        return message;
    }

    @Test
    void wordsAreAboutFourCharactersEach() {
        assertEquals(3, SummarizerServiceImpl.tokens(message("12345678", "9")));
    }

    @Test
    void imagesCountByTheirEstimate() {
        ChatMessage message = message("1234", null);
        message.setImages(ChatMessage.toJson(List.of("a", "b")));
        message.setImageTokens(3000L);
        assertEquals(1 + 3000, SummarizerServiceImpl.tokens(message));
    }

    @Test
    void imagesWithoutAnEstimateCountAsUnknownSize() {
        ChatMessage message = message("1234", null);
        message.setImages(ChatMessage.toJson(List.of("a", "b")));
        assertEquals(1 + 2 * EstimatedImageTokenizer.UNKNOWN_TOKENS, SummarizerServiceImpl.tokens(message));
    }

    @Test
    void notesStandInForTheImages() {
        ChatMessage message = message("1234", null);
        message.setImages(ChatMessage.toJson(List.of("a")));
        message.setImageTokens(3000L);
        message.setImageNotes(ChatMessage.toJson(List.of("a cat on a sofa")));
        assertEquals((4 + 15 + 3) / 4, SummarizerServiceImpl.tokens(message));
    }

    @Test
    void keptToolResultsCountAndTheOthersDoNot() {
        ChatMessage message = message("1234", null);
        message.setToolCalls(com.github.enerccio.joi.domain.chat.ToolUsage.toJson(List.of(
                new com.github.enerccio.joi.domain.chat.ToolUsage("read", "{}", "x".repeat(400), false, true),
                new com.github.enerccio.joi.domain.chat.ToolUsage("wave", "{}", "y".repeat(4000), false, false))));
        // the words, and the kept call: its name, arguments and result
        assertEquals((4 + 4 + 2 + 400 + 3) / 4, SummarizerServiceImpl.tokens(message));
    }

    @Test
    void theEstimateOfAFullImage() {
        // scaled to 1568 on the longest side, as it is sent
        assertEquals((1568L * 1176 + 749) / 750, EstimatedImageTokenizer.estimate(3136, 2352));
    }
}
