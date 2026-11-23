package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.animations.VrmaFiles;
import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.repository.AnimationRepository;
import com.github.enerccio.joi.domain.service.AnimationPackService;
import com.github.enerccio.joi.domain.service.AnimationService;
import com.github.enerccio.marginalia.domain.model.impl.Resource;
import com.github.enerccio.marginalia.domain.service.ResourceService;
import com.github.enerccio.marginalia.domain.service.impl.ExtendableServiceImpl;
import com.github.enerccio.marginalia.domain.traits.CommonTx;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AnimationServiceImpl extends ExtendableServiceImpl<Animation, AnimationRepository> implements AnimationService {

    @Autowired
    private ResourceService resourceService;

    @Autowired
    private AnimationPackService packService;

    @Override
    @CommonTxReadOnly
    public List<Animation> findAllByName() throws Exception {
        List<Animation> clips = new ArrayList<>(findAllForUser());
        clips.sort(Comparator.comparing((Animation clip) -> StringUtils.defaultString(clip.getName()), String.CASE_INSENSITIVE_ORDER));
        return clips;
    }

    @Override
    @CommonTx
    public Animation add(File file, String fileName) throws Exception {
        return add(file, fileName, null);
    }

    @Override
    @CommonTx
    public Animation add(File file, String fileName, String uuid) throws Exception {
        if (uuid != null && find(uuid) != null) {
            Files.deleteIfExists(file.toPath());
            throw new IllegalArgumentException("the clip's uuid is taken: " + uuid);
        }
        File folder = configuration.getResourcesFolder(currentUser).getCanonicalFile();
        if (!folder.equals(file.getCanonicalFile().getParentFile())) {
            throw new IllegalArgumentException("not an upload file: " + file);
        }
        VrmaFiles.Inspection inspection = VrmaFiles.inspect(file);
        if (inspection == null) {
            Files.deleteIfExists(file.toPath());
            throw new IllegalArgumentException("not a VRM animation: " + fileName);
        }

        Resource resource = resourceService.saveFile(file, fileName, VrmaFiles.CONTENT_TYPE);

        Animation animation = new Animation();
        animation.setUuid(uuid);
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
    public UuidUse uuidUse(String uuid) throws Exception {
        Long id = uuid == null ? null : find(uuid);
        if (id == null) {
            return UuidUse.FREE;
        }
        Animation clip = find(id);
        if (clip == null || clip.getOwner() == null || !clip.getOwner().getId().equals(currentUser.getId())) {
            return UuidUse.OTHER_USER;
        }
        return clip.isDeleted() ? UuidUse.MINE_DELETED : UuidUse.MINE;
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
        for (AnimationPack pack : packService.packsWith(stored)) {
            packService.removeClip(pack, stored);
        }
        if (stored.getResource() != null) {
            resourceService.softDelete(List.of(stored.getResource().getUuid()));
        }
        return super.delete(stored, hard);
    }
}
