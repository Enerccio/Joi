package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.collections.ModelFormat;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.repository.ModelRepository;
import com.github.enerccio.marginalia.domain.service.ExtendableService;

import java.io.File;
import java.util.List;

/** The 3D models of a user (what bodies are made of). */
public interface ModelService extends ExtendableService<Model, ModelRepository> {

    /** The models of the current user, by name. */
    List<Model> findAllByName() throws Exception;

    /**
     * The current user's folder of resources, where an upload streams into ({@link ModelFiles#uploadFile}): models can
     * be large, so they are never held in memory. Asked in the UI thread; the upload runs in its own request.
     */
    File uploadFolder() throws Exception;

    /**
     * Makes the uploaded file the model's: a resource of the current user (linked to the model), with the format and
     * the main file read from it. The resource the model had before is deleted. A model without a name is named after
     * the file. The model is saved (created when it is new).
     *
     * @param file     an uploaded file in the {@link #uploadFolder}
     * @param fileName the name of the user's file
     * @return the saved model
     * @throws IllegalArgumentException when the file is not a model, nor a zip with one (the file is deleted)
     */
    Model attach(Model model, File file, String fileName) throws Exception;

    /** The model's file, null when it has none. */
    File file(Model model) throws Exception;

    /**
     * Makes the VRM a conversion wrote the model's converted file: a resource of the current user linked to the model,
     * in place of the one before (deleted). Sets the size and the time of the conversion; the caller saves the model.
     *
     * @param vrm a file in the {@link #uploadFolder}
     */
    void attachConverted(Model model, File vrm) throws Exception;

    /** The converted VRM of the model, null when it has none. */
    File convertedFile(Model model) throws Exception;

    /**
     * The VRM a body shows of the model: the converted one, else the upload when it is a .vrm or a .glb itself; null
     * when it has none ({@link Model#renderable()}). Its format is {@link #bodyFormat}.
     */
    File bodyFile(Model model) throws Exception;

    /** The hash (SHA-256) of the {@link #bodyFile}, null when there is none. */
    String bodyHash(Model model) throws Exception;

    /** The format of the {@link #bodyFile}: VRM when converted, else the upload's. */
    ModelFormat bodyFormat(Model model);

    /**
     * Reads what the model can do from its VRM (the converted one, else the uploaded .vrm or .glb) into
     * {@link Model#getCapabilities()}; the caller saves the model. Nothing changes when it has no such file or the
     * file can't be read.
     *
     * @return whether they were read
     */
    boolean readCapabilities(Model model) throws Exception;
}
