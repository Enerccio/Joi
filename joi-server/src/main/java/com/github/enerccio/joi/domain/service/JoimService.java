package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.Model;

import java.io.File;
import java.util.function.LongConsumer;

/**
 * The {@code .joim} of a model ({@code JoimFile}, see {@code DESIGN.md} → Bodies, export and import): written when
 * the model changed and kept as its resource, and imported as a ready model. Files go through the disk: the VRM is
 * streamed, never held in memory.
 */
public interface JoimService {

    /**
     * Whether the model's {@code .joim} is missing or not current: it has a VRM, no conversion is running, and what a
     * {@code .joim} of it holds changed since the last one.
     */
    boolean needsExport(Model model) throws Exception;

    /** The bytes of the VRM an export writes (for its progress), 0 when it has none. */
    long exportSize(Model model) throws Exception;

    /**
     * Writes the model's {@code .joim} and links it to the model, in place of the one before.
     *
     * @param progress takes the bytes of the VRM written, as they are
     * @return the saved model
     * @throws IllegalStateException when the model has no VRM
     */
    Model export(Model model, LongConsumer progress) throws Exception;

    /**
     * Makes an uploaded {@code .joim} the model: its VRM and what was decided on it (the model's name stays when it has
     * one), with no conversion. The {@code .joim} becomes the model's export.
     *
     * @param joim     the uploaded file, in the model service's upload folder
     * @param fileName the name of the user's file
     * @return the saved model
     * @throws java.io.IOException when it is not a {@code .joim}, is of a newer format, is damaged or has no VRM (the
     *                             file is deleted)
     */
    Model importModel(Model model, File joim, String fileName, LongConsumer progress) throws Exception;
}
