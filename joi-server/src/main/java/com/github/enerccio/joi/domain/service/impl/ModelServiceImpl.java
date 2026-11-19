package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.bodies.ModelCapabilities;
import com.github.enerccio.joi.domain.bodies.ImageType;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.bodies.SpringBones;
import com.github.enerccio.joi.domain.bodies.SpringChain;
import com.github.enerccio.joi.protocol.joim.Cbor;
import com.github.enerccio.joi.protocol.joim.JoimFile;
import com.github.enerccio.joi.domain.bodies.joim.JoimMetadata;
import com.github.enerccio.joi.domain.collections.ConversionStatus;
import com.github.enerccio.joi.domain.collections.ModelFormat;
import com.google.gson.JsonObject;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.repository.ModelRepository;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.marginalia.Configuration;
import com.github.enerccio.marginalia.domain.model.impl.Resource;
import com.github.enerccio.marginalia.domain.service.ResourceService;
import com.github.enerccio.marginalia.domain.service.impl.ExtendableServiceImpl;
import com.github.enerccio.marginalia.domain.traits.CommonTx;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Comparator;
import java.util.List;

public class ModelServiceImpl extends ExtendableServiceImpl<Model, ModelRepository> implements ModelService {

    @Autowired
    private Configuration configuration;

    @Autowired
    private ResourceService resourceService;

    @Override
    @CommonTxReadOnly
    public List<Model> findAllByName() throws Exception {
        List<Model> models = new ArrayList<>(findAllForUser());
        models.sort(Comparator.comparing((Model model) -> StringUtils.defaultString(model.getName()), String.CASE_INSENSITIVE_ORDER));
        return models;
    }

    @Override
    public File uploadFolder() throws Exception {
        File folder = configuration.getResourcesFolder(currentUser);
        Files.createDirectories(folder.toPath());
        return folder;
    }

    @Override
    @CommonTx
    public Model attach(Model model, File file, String fileName) throws Exception {
        File folder = configuration.getResourcesFolder(currentUser).getCanonicalFile();
        if (!folder.equals(file.getCanonicalFile().getParentFile())) {
            throw new IllegalArgumentException("not an upload file: " + file);
        }
        ModelFiles.Inspection inspection = ModelFiles.inspect(file, fileName);
        if (inspection == null) {
            Files.deleteIfExists(file.toPath());
            throw new IllegalArgumentException("not a model: " + fileName);
        }

        Resource resource = store(file, fileName, inspection.contentType());

        Resource previous = model.getResource();
        model.setResource(resource);
        model.setFormat(inspection.format());
        model.setMainFile(inspection.mainFile());
        model.setFileCount(inspection.fileCount());
        model.setFbxFiles(inspection.fbxFiles());
        if (model.getConvertedResourceId() == null) {
            model.setCapabilities(null);
            readCapabilities(model);
        }
        model.setFileName(fileName);
        model.setFileSize(resource.getSize());
        model.setUploadRemoved(false);
        if (StringUtils.isBlank(model.getName())) {
            model.setName(FilenameUtils.getBaseName(fileName));
        }
        Model saved = save(model);
        resourceService.link(resource, Model.class, saved.getId());
        if (previous != null) {
            resourceService.softDelete(List.of(previous.getUuid()));
        }
        return saved;
    }

    /** A resource of the current user for a file already in their folder of resources. */
    private Resource store(File file, String originalName, String contentType) throws Exception {
        return store(file, originalName, contentType, null);
    }

    /** As {@link #store(File, String, String)}, with the file's SHA-256 when it is known (null: read to hash it). */
    private Resource store(File file, String originalName, String contentType, String hash) throws Exception {
        Resource resource = new Resource();
        resource.setOriginalName(originalName);
        resource.setMimeType(contentType);
        resource.setPath(file.getName());
        resource.setSize(file.length());
        if (hash == null) {
            try (InputStream in = Files.newInputStream(file.toPath())) {
                hash = DigestUtils.sha256Hex(in);
            }
        }
        resource.setHash(hash);
        return resourceService.save(resource);
    }

    @Override
    @CommonTx
    public void attachConverted(Model model, File vrm) throws Exception {
        linkConverted(model, vrm);
        model.setConvertedAt(new java.util.Date());
    }

    @Override
    @CommonTx
    public Model applySprings(Model model) throws Exception {
        Model stored = find(model.getId());
        stored.setSpringChains(model.getSpringChains());
        File current = convertedFile(stored);
        if (current == null) {
            throw new IllegalStateException("the model has no converted VRM");
        }
        File out = ModelFiles.uploadFile(uploadFolder(), "model.vrm");
        SpringBones.Result result;
        try {
            result = SpringBones.write(current, out, SpringChain.listFromJson(stored.getSpringChains()));
        } catch (IOException e) {
            FileUtils.deleteQuietly(out);
            throw e;
        }
        String before = bodyHash(stored);
        linkConverted(stored, out);
        String after = bodyHash(stored);
        // spring bones don't change how she looks: what was current stays current
        if (before != null && before.equals(stored.getSnapshotsHash())) {
            stored.setSnapshotsHash(after);
        }
        if (before != null && before.equals(stored.getDescribedHash())) {
            stored.setDescribedHash(after);
        }
        String chains = SpringChain.toJson(result.chains());
        stored.setSpringChains(chains);
        stored.setAppliedSpringChains(chains);
        return save(stored);
    }

    /** Links the VRM as the model's converted file, in place of the one before (deleted); reads its capabilities. */
    private void linkConverted(Model model, File vrm) throws Exception {
        linkConverted(model, vrm, null);
    }

    /** As {@link #linkConverted(Model, File)}, with the VRM's SHA-256 when it is known. */
    private void linkConverted(Model model, File vrm, String hash) throws Exception {
        File folder = configuration.getResourcesFolder(currentUser).getCanonicalFile();
        if (!folder.equals(vrm.getCanonicalFile().getParentFile())) {
            throw new IllegalArgumentException("not in the folder of resources: " + vrm);
        }
        String name = StringUtils.defaultIfBlank(model.getName(), "model") + ".vrm";
        Resource resource = store(vrm, name, ModelFormat.VRM.contentType(), hash);
        resourceService.link(resource, Model.class, model.getId());
        Long previous = model.getConvertedResourceId();
        model.setConvertedResourceId(resource.getId());
        model.setConvertedFileSize(resource.getSize());
        try {
            model.setCapabilities(ModelCapabilities.read(vrm).toJson());
        } catch (IOException e) {
            // stored anyway: the preview tells what is wrong with it
            model.setCapabilities(null);
        }
        if (previous != null) {
            Resource old = resourceService.find(previous);
            if (old != null) {
                resourceService.softDelete(List.of(old.getUuid()));
            }
        }
    }

    @Override
    @CommonTxReadOnly
    public File bodyFile(Model model) throws Exception {
        File file = model.getId() == null ? null : convertedFile(model);
        if (file == null && model.getResource() != null && (model.getFormat() == ModelFormat.VRM || model.getFormat() == ModelFormat.GLB)
                && model.getMainFile() == null) {
            file = file(model);
        }
        return file;
    }

    @Override
    @CommonTxReadOnly
    public String bodyHash(Model model) throws Exception {
        Resource resource = null;
        if (model.getConvertedResourceId() != null) {
            resource = resourceService.find(model.getConvertedResourceId());
        } else if (model.getResource() != null && model.getMainFile() == null
                && (model.getFormat() == ModelFormat.VRM || model.getFormat() == ModelFormat.GLB)) {
            resource = resourceService.find(model.getResource().getId());
        }
        return resource == null || resource.isDeleted() ? null : resource.getHash();
    }

    @Override
    public ModelFormat bodyFormat(Model model) {
        return model.getConvertedResourceId() != null ? ModelFormat.VRM : model.getFormat();
    }

    @Override
    public boolean readCapabilities(Model model) throws Exception {
        File file = bodyFile(model);
        if (file == null) {
            return false;
        }
        try {
            model.setCapabilities(ModelCapabilities.read(file).toJson());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    @CommonTxReadOnly
    public File convertedFile(Model model) throws Exception {
        if (model.getConvertedResourceId() == null) {
            return null;
        }
        Resource resource = resourceService.find(model.getConvertedResourceId());
        return resource == null || resource.isDeleted() || resource.getOwner() == null || !resource.getOwner().getId().equals(currentUser.getId())
                ? null : resourceService.getResourceFile(resource);
    }

    @Override
    @CommonTxReadOnly
    public File file(Model model) throws Exception {
        if (model.getResource() == null) {
            return null;
        }
        Resource resource = resourceService.find(model.getResource().getId());
        return resource == null ? null : resourceService.getResourceFile(resource);
    }
    @Override
    @CommonTx
    public Model storeSnapshots(Model model, Map<String, byte[]> shots) throws Exception {
        Model stored = find(model.getId());
        File folder = uploadFolder();
        Map<String, String> before = new LinkedHashMap<>(stored.getSnapshots());
        Map<String, String> snapshots = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> shot : shots.entrySet()) {
            if (StringUtils.isBlank(shot.getKey()) || ImageType.of(shot.getValue()) != ImageType.JPEG) {
                throw new IllegalArgumentException("not a snapshot: " + shot.getKey());
            }
            String name = shot.getKey().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-") + ".jpg";
            File file = ModelFiles.uploadFile(folder, name);
            Files.write(file.toPath(), shot.getValue());
            Resource resource = storeImage(file, name, ImageType.JPEG.mimeType());
            resourceService.link(resource, Model.class, stored.getId());
            snapshots.put(shot.getKey(), String.valueOf(resource.getId()));
        }
        stored.setSnapshots(snapshots);
        stored.setSnapshotsHash(bodyHash(stored));
        stored.setSnapshotsAt(new java.util.Date());
        Model saved = save(stored);
        forget(before.values());
        return saved;
    }

    @Override
    @CommonTx
    public Model addReference(Model model, File file, String fileName) throws Exception {
        File folder = configuration.getResourcesFolder(currentUser).getCanonicalFile();
        if (!folder.equals(file.getCanonicalFile().getParentFile())) {
            throw new IllegalArgumentException("not an upload file: " + file);
        }
        byte[] head;
        try (InputStream in = Files.newInputStream(file.toPath())) {
            head = in.readNBytes(16);
        }
        ImageType type = ImageType.of(head);
        if (type == null) {
            Files.deleteIfExists(file.toPath());
            throw new IllegalArgumentException("not an image: " + fileName);
        }
        Model stored = find(model.getId());
        String name = StringUtils.defaultIfBlank(fileName, file.getName());
        Resource resource = storeImage(file, name, type.mimeType());
        resourceService.link(resource, Model.class, stored.getId());
        stored.getReferences().put(String.valueOf(resource.getId()), name);
        return save(stored);
    }

    @Override
    @CommonTx
    public Model removeReference(Model model, String resourceId) throws Exception {
        Model stored = find(model.getId());
        if (stored.getReferences().remove(resourceId) != null) {
            forget(List.of(resourceId));
        }
        return save(stored);
    }

    @Override
    @CommonTxReadOnly
    public File imageFile(Model model, String resourceId) throws Exception {
        if (resourceId == null || !(model.getSnapshots().containsValue(resourceId) || model.getReferences().containsKey(resourceId))) {
            return null;
        }
        Resource resource = resourceService.find(Long.valueOf(resourceId));
        return resource == null || resource.isDeleted() || resource.getOwner() == null
                || !resource.getOwner().getId().equals(currentUser.getId()) ? null : resourceService.getResourceFile(resource);
    }

    /**
     * A resource of the current user for an image in their folder of resources: the file moves to their folder of
     * images, where the platform keeps the files of images.
     */
    private Resource storeImage(File file, String originalName, String contentType) throws Exception {
        File images = configuration.getImagesFolder(currentUser);
        Files.createDirectories(images.toPath());
        File moved = new File(images, file.getName());
        Files.move(file.toPath(), moved.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return store(moved, originalName, contentType);
    }

    /** Deletes the resources (by id) the model no longer has. */
    private void forget(Collection<String> resourceIds) throws Exception {
        List<String> uuids = new ArrayList<>();
        for (String id : resourceIds) {
            Resource resource = resourceService.find(Long.valueOf(id));
            if (resource != null && !resource.isDeleted()) {
                uuids.add(resource.getUuid());
            }
        }
        if (!uuids.isEmpty()) {
            resourceService.softDelete(uuids);
        }
    }

    @Override
    @CommonTxReadOnly
    public JsonObject exportMetadata(Model model) throws Exception {
        File file = bodyFile(model);
        return file == null ? null : JoimMetadata.of(model, bodyFormat(model), file.length());
    }

    @Override
    @CommonTxReadOnly
    public String exportFingerprint(Model model) throws Exception {
        JsonObject meta = exportMetadata(model);
        String hash = meta == null ? null : bodyHash(model);
        if (hash == null) {
            return null;
        }
        java.security.MessageDigest digest = DigestUtils.getSha256Digest();
        digest.update(Cbor.encode(meta));
        digest.update(hash.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return org.apache.commons.codec.binary.Hex.encodeHexString(digest.digest());
    }

    @Override
    @CommonTxReadOnly
    public File exportFile(Model model) throws Exception {
        if (model.getExportResourceId() == null) {
            return null;
        }
        Resource resource = resourceService.find(model.getExportResourceId());
        return resource == null || resource.isDeleted() || resource.getOwner() == null || !resource.getOwner().getId().equals(currentUser.getId())
                ? null : resourceService.getResourceFile(resource);
    }

    @Override
    @CommonTx
    public Model linkExport(Model model, File joim, String fingerprint, String hash) throws Exception {
        Model stored = find(model.getId());
        checkUploadFolder(joim);
        linkExportFile(stored, joim, JoimMetadata.fileName(stored.getName()), hash);
        stored.setExportFingerprint(fingerprint);
        return save(stored);
    }

    @Override
    @CommonTx
    public Model attachImport(Model model, File joim, String fileName, String joimHash, File vrm, String vrmHash) throws Exception {
        checkUploadFolder(joim);
        checkUploadFolder(vrm);
        if (StringUtils.isBlank(model.getName())) {
            model.setName(FilenameUtils.getBaseName(fileName));
        }
        Resource previous = model.getResource();
        model.setResource(null);
        model.setFormat(ModelFormat.JOIM);
        model.setMainFile(null);
        model.setFileCount(1);
        model.setFbxFiles(List.of());
        model.setConvertFbx(null);
        model.setFileName(fileName);
        model.setFileSize(joim.length());
        model.setUploadRemoved(false);
        model.setConversionStatus(ConversionStatus.DONE);
        Model saved = save(model);
        linkConverted(saved, vrm, vrmHash);
        saved.setConvertedAt(new java.util.Date());
        linkExportFile(saved, joim, fileName, joimHash);
        saved.setExportFingerprint(exportFingerprint(saved));
        saved = save(saved);
        if (previous != null) {
            resourceService.softDelete(List.of(previous.getUuid()));
        }
        return saved;
    }

    @Override
    @CommonTxReadOnly
    public Cleanup cleanup(Model model) throws Exception {
        Model stored = find(model.getId());
        Resource upload = removableUpload(stored);
        File uploadFile = upload == null ? null : resourceService.getResourceFile(upload);
        int oldFiles = 0;
        long oldBytes = 0;
        for (File file : oldFiles(stored)) {
            oldFiles++;
            oldBytes += file.length();
        }
        return new Cleanup(uploadFile == null ? null : stored.getFileName(), uploadFile == null ? 0 : uploadFile.length(), oldFiles, oldBytes);
    }

    @Override
    @CommonTx
    public Model cleanUp(Model model) throws Exception {
        Model stored = find(model.getId());
        List<File> delete = new ArrayList<>(oldFiles(stored));
        Resource upload = removableUpload(stored);
        if (upload != null) {
            File file = resourceService.getResourceFile(upload);
            resourceService.softDelete(List.of(upload.getUuid()));
            if (file.isFile() && !getRepository().pathInUse(upload.getPath())) {
                delete.add(file);
            }
            stored.setResource(null);
            stored.setUploadRemoved(true);
            stored = save(stored);
        }
        for (File file : delete) {
            Files.deleteIfExists(file.toPath());
        }
        return stored;
    }

    /** The model's upload when it can go: it has a converted VRM to show instead. */
    private Resource removableUpload(Model model) throws Exception {
        if (model.getResource() == null || convertedFile(model) == null) {
            return null;
        }
        Resource upload = resourceService.find(model.getResource().getId());
        return upload == null || upload.isDeleted() ? null : upload;
    }

    /** The files of the model's deleted resources still on disk that nothing else uses. */
    private List<File> oldFiles(Model model) throws Exception {
        List<File> files = new ArrayList<>();
        for (Resource resource : getRepository().findDeletedResources(currentUser.getId(), model.getId())) {
            if (resource.getPath() == null || getRepository().pathInUse(resource.getPath())) {
                continue;
            }
            File file = resourceService.getResourceFile(resource);
            if (file.isFile()) {
                files.add(file);
            }
        }
        return files;
    }

    private void checkUploadFolder(File file) throws Exception {
        File folder = configuration.getResourcesFolder(currentUser).getCanonicalFile();
        if (!folder.equals(file.getCanonicalFile().getParentFile())) {
            throw new IllegalArgumentException("not in the folder of resources: " + file);
        }
    }

    /** Links the file as the model's {@code .joim}, in place of the one before (deleted). */
    private void linkExportFile(Model model, File joim, String name, String hash) throws Exception {
        Resource resource = store(joim, name, JoimFile.CONTENT_TYPE, hash);
        resourceService.link(resource, Model.class, model.getId());
        Long previous = model.getExportResourceId();
        model.setExportResourceId(resource.getId());
        model.setExportFileSize(resource.getSize());
        model.setExportedAt(new java.util.Date());
        if (previous != null) {
            Resource old = resourceService.find(previous);
            if (old != null && !old.isDeleted()) {
                resourceService.softDelete(List.of(old.getUuid()));
            }
        }
    }
}
