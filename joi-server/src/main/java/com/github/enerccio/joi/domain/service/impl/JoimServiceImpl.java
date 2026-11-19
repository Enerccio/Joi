package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.bodies.joim.JoimMetadata;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.service.JoimService;
import com.github.enerccio.joi.domain.service.ModelConversionService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.protocol.joim.JoimFile;
import com.google.gson.JsonObject;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.function.LongConsumer;

/**
 * Writes and reads the files outside a transaction (a VRM can be hundreds of megabytes); the model service stores
 * what changed in its own.
 */
public class JoimServiceImpl implements JoimService {

    @Autowired
    private ModelService modelService;

    @Autowired
    private ModelConversionService conversionService;

    @Override
    public boolean needsExport(Model model) throws Exception {
        if (model.getId() == null) {
            return false;
        }
        Model stored = modelService.find(model.getId());
        if (stored == null || stored.isDeleted() || conversionService.isRunning(stored)) {
            return false;
        }
        String fingerprint = modelService.exportFingerprint(stored);
        return fingerprint != null && (!fingerprint.equals(stored.getExportFingerprint()) || modelService.exportFile(stored) == null);
    }

    @Override
    public long exportSize(Model model) throws Exception {
        File file = model.getId() == null ? null : modelService.bodyFile(modelService.find(model.getId()));
        return file == null ? 0 : file.length();
    }

    @Override
    public Model export(Model model, LongConsumer progress) throws Exception {
        Model stored = modelService.find(model.getId());
        File vrm = modelService.bodyFile(stored);
        JsonObject meta = modelService.exportMetadata(stored);
        String fingerprint = modelService.exportFingerprint(stored);
        if (vrm == null || meta == null || fingerprint == null) {
            throw new IllegalStateException("the model has no VRM");
        }
        meta.addProperty("exportedAt", Instant.now().toString());
        File out = ModelFiles.uploadFile(modelService.uploadFolder(), "model." + JoimFile.EXTENSION);
        try {
            String hash = JoimFile.write(out, JoimFile.Profile.FULL, meta, vrm, modelService.bodyHash(stored), progress);
            return modelService.linkExport(stored, out, fingerprint, hash);
        } catch (Exception e) {
            FileUtils.deleteQuietly(out);
            throw e;
        }
    }

    @Override
    public Model importModel(Model model, File joim, String fileName, LongConsumer progress) throws Exception {
        File vrm = ModelFiles.uploadFile(modelService.uploadFolder(), "model.vrm");
        try {
            JoimFile.Contents contents = JoimFile.read(joim, vrm, Objects.requireNonNullElse(progress, bytes -> {
            }));
            if (!contents.hasModel()) {
                throw new IOException("the file has no model");
            }
            Model target = model.getId() == null ? model : modelService.find(model.getId());
            JoimMetadata.apply(contents.meta(), target);
            return modelService.attachImport(target, joim, fileName, contents.fileHash(), vrm, contents.modelHash());
        } catch (Exception e) {
            FileUtils.deleteQuietly(vrm);
            FileUtils.deleteQuietly(joim);
            throw e;
        }
    }
}
