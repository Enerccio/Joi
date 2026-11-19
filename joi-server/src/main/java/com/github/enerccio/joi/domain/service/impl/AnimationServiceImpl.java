package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.animations.VrmaFiles;
import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.repository.AnimationRepository;
import com.github.enerccio.joi.domain.service.AnimationService;
import com.github.enerccio.marginalia.domain.model.impl.Resource;
import com.github.enerccio.marginalia.domain.service.ResourceService;
import com.github.enerccio.marginalia.domain.service.impl.ExtendableServiceImpl;
import com.github.enerccio.marginalia.domain.traits.CommonTx;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;

public class AnimationServiceImpl extends ExtendableServiceImpl<Animation, AnimationRepository> implements AnimationService {

    @Autowired
    private ResourceService resourceService;

    @Override
    @CommonTxReadOnly
    public List<Animation> findByPack(AnimationPack pack) throws Exception {
        return pack == null || pack.getId() == null ? List.of() : getRepository().findByPack(pack);
    }

    @Override
    @CommonTxReadOnly
    public long countByPack(AnimationPack pack) throws Exception {
        return pack == null || pack.getId() == null ? 0 : getRepository().countByPack(pack);
    }

    @Override
    @CommonTx
    public Animation add(AnimationPack pack, File file, String fileName) throws Exception {
        File folder = configuration.getResourcesFolder(currentUser).getCanonicalFile();
        if (!folder.equals(file.getCanonicalFile().getParentFile())) {
            throw new IllegalArgumentException("not an upload file: " + file);
        }
        VrmaFiles.Inspection inspection = VrmaFiles.inspect(file);
        if (inspection == null) {
            Files.deleteIfExists(file.toPath());
            throw new IllegalArgumentException("not a VRM animation: " + fileName);
        }

        Resource resource = new Resource();
        resource.setOriginalName(fileName);
        resource.setMimeType(VrmaFiles.CONTENT_TYPE);
        resource.setPath(file.getName());
        resource.setSize(file.length());
        try (InputStream in = Files.newInputStream(file.toPath())) {
            resource.setHash(DigestUtils.sha256Hex(in));
        }
        resource = resourceService.save(resource);

        Animation animation = new Animation();
        animation.setPack(pack);
        animation.setResource(resource);
        animation.setName(FilenameUtils.getBaseName(fileName));
        animation.setFileName(fileName);
        animation.setFileSize(resource.getSize());
        animation.setDuration(inspection.duration());
        animation.setBones(inspection.bones());
        animation.setExpressions(inspection.expressions());
        Animation saved = save(animation);
        resourceService.link(resource, Animation.class, saved.getId());
        return saved;
    }

    @Override
    @CommonTxReadOnly
    public File file(Animation animation) throws Exception {
        if (animation.getResource() == null) {
            return null;
        }
        Resource resource = resourceService.find(animation.getResource().getId());
        return resource == null || resource.isDeleted() || resource.getOwner() == null
                || !resource.getOwner().getId().equals(currentUser.getId()) ? null : resourceService.getResourceFile(resource);
    }

    @Override
    @CommonTx
    public Animation delete(Animation animation, boolean hard) throws Exception {
        Animation stored = find(animation.getId());
        if (stored.getResource() != null) {
            resourceService.softDelete(List.of(stored.getResource().getUuid()));
        }
        return super.delete(stored, hard);
    }
}
