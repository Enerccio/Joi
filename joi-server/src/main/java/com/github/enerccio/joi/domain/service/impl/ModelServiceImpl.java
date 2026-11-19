package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.bodies.ModelCapabilities;
import com.github.enerccio.joi.domain.bodies.ImageType;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.collections.ModelFormat;
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
        Resource resource = new Resource();
        resource.setOriginalName(originalName);
        resource.setMimeType(contentType);
        resource.setPath(file.getName());
        resource.setSize(file.length());
        try (InputStream in = Files.newInputStream(file.toPath())) {
            resource.setHash(DigestUtils.sha256Hex(in));
        }
        return resourceService.save(resource);
    }

    @Override
    @CommonTx
    public void attachConverted(Model model, File vrm) throws Exception {
        File folder = configuration.getResourcesFolder(currentUser).getCanonicalFile();
        if (!folder.equals(vrm.getCanonicalFile().getParentFile())) {
            throw new IllegalArgumentException("not in the folder of resources: " + vrm);
        }
        String name = StringUtils.defaultIfBlank(model.getName(), "model") + ".vrm";
        Resource resource = store(vrm, name, ModelFormat.VRM.contentType());
        resourceService.link(resource, Model.class, model.getId());
        Long previous = model.getConvertedResourceId();
        model.setConvertedResourceId(resource.getId());
        model.setConvertedFileSize(resource.getSize());
        model.setConvertedAt(new java.util.Date());
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
}
