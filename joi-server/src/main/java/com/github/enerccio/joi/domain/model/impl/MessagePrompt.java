package com.github.enerccio.joi.domain.model.impl;

import com.github.enerccio.marginalia.domain.model.ExtendableEntity;
import com.github.enerccio.marginalia.domain.traits.CleanupReference;
import com.github.enerccio.marginalia.domain.traits.CleanupReference.Policy;
import com.github.enerccio.marginalia.domain.traits.ExtendedAttribute;
import jakarta.persistence.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * What was sent to the model for a {@link ChatMessage}: the built prompt, the whole payload (after Marginalia's
 * {@code MessagePrompt}). It is read only when somebody asks for it (the prompt dialog, a review), so it lives apart
 * from the message, which the conversation loads often. At most one per message. The prompt is stored deflated; the
 * accessors give and take the plain text.
 */
@Entity
@Table(name = "message_prompts")
public class MessagePrompt extends ExtendableEntity {

    // nullable: the cleanup nulls the reference before it purges the message
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id")
    @CleanupReference(Policy.OWNED_BY)
    private ChatMessage message;

    // deflated: the prompt repeats the persona, the memories and the tool definitions, and compresses several times over
    @Column(length = 268435456)
    private byte[] builtPrompt;

    /** The uuids of the memories that were recalled into the prompt (JSON array), null when none. */
    @ExtendedAttribute
    @Transient
    private String recalledMemories;

    /** The uuids of the summaries that were in the prompt (JSON array), null when none. */
    @ExtendedAttribute
    @Transient
    private String summaries;

    public ChatMessage getMessage() {
        return message;
    }

    public void setMessage(ChatMessage message) {
        this.message = message;
    }

    public String getBuiltPrompt() {
        return inflate(builtPrompt);
    }

    public void setBuiltPrompt(String builtPrompt) {
        this.builtPrompt = deflate(builtPrompt);
    }

    public String getRecalledMemories() {
        return recalledMemories;
    }

    public void setRecalledMemories(String recalledMemories) {
        this.recalledMemories = recalledMemories;
    }

    public String getSummaries() {
        return summaries;
    }

    public void setSummaries(String summaries) {
        this.summaries = summaries;
    }

    private static byte[] deflate(String text) {
        if (text == null) {
            return null;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DeflaterOutputStream out = new DeflaterOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static String inflate(byte[] stored) {
        if (stored == null) {
            return null;
        }
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(stored))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
