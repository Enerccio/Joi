package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.animations.AnimationFolder;
import com.github.enerccio.joi.domain.animations.AnimationFolder.Kind;
import com.github.enerccio.joi.domain.animations.AnimationFolders;
import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.model.impl.settings.JoiUserSetting;
import com.github.enerccio.joi.domain.service.AnimationFolderService;
import com.github.enerccio.joi.domain.service.AnimationPackService;
import com.github.enerccio.joi.domain.service.AnimationService;
import com.github.enerccio.marginalia.domain.service.SettingService;
import com.github.enerccio.marginalia.domain.traits.CommonTx;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

/**
 * {@link AnimationFolderService} on the user setting: the folders are a list there (an extended attribute, no table),
 * the folder of a clip or an animation is an extended attribute of it.
 */
public class AnimationFolderServiceImpl implements AnimationFolderService {

    @Autowired
    private SettingService settingService;

    @Autowired
    private AnimationService animationService;

    @Autowired
    private AnimationPackService packService;

    private JoiUserSetting setting() throws Exception {
        return (JoiUserSetting) settingService.getUserSetting();
    }

    private static List<AnimationFolder> ofKind(JoiUserSetting setting, Kind kind) {
        return setting.getAnimationFolders().stream().filter(f -> f != null && f.getKind() == kind).toList();
    }

    @Override
    @CommonTxReadOnly
    public AnimationFolders tree(Kind kind) throws Exception {
        return new AnimationFolders(ofKind(setting(), kind));
    }

    @Override
    @CommonTx
    public AnimationFolder create(Kind kind, String name, String parent) throws Exception {
        String valid = AnimationFolders.validName(name);
        JoiUserSetting setting = setting();
        AnimationFolders tree = new AnimationFolders(ofKind(setting, kind));
        String in = tree.known(parent);
        if (tree.taken(in, valid, null)) {
            throw new IllegalStateException("a folder of that name is there: " + valid);
        }
        AnimationFolder folder = new AnimationFolder(UUID.randomUUID().toString(), valid, in, kind);
        List<AnimationFolder> folders = new ArrayList<>(setting.getAnimationFolders());
        folders.add(folder);
        store(setting, folders);
        return folder;
    }

    @Override
    @CommonTx
    public AnimationFolder rename(Kind kind, String uuid, String name) throws Exception {
        String valid = AnimationFolders.validName(name);
        JoiUserSetting setting = setting();
        AnimationFolders tree = new AnimationFolders(ofKind(setting, kind));
        AnimationFolder folder = tree.find(uuid);
        if (folder == null) {
            return null;
        }
        if (tree.taken(tree.known(folder.getParent()), valid, uuid)) {
            throw new IllegalStateException("a folder of that name is there: " + valid);
        }
        folder.setName(valid);
        store(setting, setting.getAnimationFolders());
        return folder;
    }

    @Override
    @CommonTx
    public AnimationFolder move(Kind kind, String uuid, String parent) throws Exception {
        JoiUserSetting setting = setting();
        AnimationFolders tree = new AnimationFolders(ofKind(setting, kind));
        AnimationFolder folder = tree.find(uuid);
        if (folder == null) {
            return null;
        }
        String target = tree.known(parent);
        if (target != null && tree.withDescendants(uuid).contains(target)) {
            throw new IllegalArgumentException("a folder can't go into itself or a folder below it");
        }
        if (tree.taken(target, folder.getName(), uuid)) {
            throw new IllegalStateException("a folder of that name is there: " + folder.getName());
        }
        folder.setParent(target);
        store(setting, setting.getAnimationFolders());
        return folder;
    }

    @Override
    @CommonTxReadOnly
    public Content describeContent(Kind kind, String uuid) throws Exception {
        AnimationFolders tree = tree(kind);
        Set<String> folders = tree.withDescendants(uuid);
        if (folders.isEmpty()) {
            return new Content(0, 0);
        }
        int items = 0;
        for (String folder : itemFolders(kind)) {
            if (folders.contains(folder)) {
                items++;
            }
        }
        return new Content(items, folders.size() - 1);
    }

    /** The folder of each clip or animation of the user (null for the top level). */
    private List<String> itemFolders(Kind kind) throws Exception {
        List<String> folders = new ArrayList<>();
        if (kind == Kind.CLIPS) {
            animationService.findAllForUser().forEach(clip -> folders.add(clip.getFolder()));
        } else {
            packService.findAllForUser().forEach(pack -> folders.add(pack.getFolder()));
        }
        return folders;
    }

    @Override
    @CommonTx
    public void delete(Kind kind, String uuid) throws Exception {
        JoiUserSetting setting = setting();
        AnimationFolders tree = new AnimationFolders(ofKind(setting, kind));
        AnimationFolder folder = tree.find(uuid);
        if (folder == null) {
            return;
        }
        String up = tree.known(folder.getParent());
        for (AnimationFolder child : tree.children(uuid)) {
            if (tree.taken(up, child.getName(), uuid)) {
                throw new IllegalStateException("a folder above has the name " + child.getName());
            }
        }
        for (AnimationFolder child : tree.children(uuid)) {
            child.setParent(up);
        }
        List<AnimationFolder> folders = new ArrayList<>(setting.getAnimationFolders());
        folders.remove(folder);
        store(setting, folders);
        if (kind == Kind.CLIPS) {
            moveClips(animationService.findAllForUser().stream().filter(c -> uuid.equals(c.getFolder())).toList(), up);
        } else {
            moveAnimations(packService.findAllForUser().stream().filter(p -> uuid.equals(p.getFolder())).toList(), up);
        }
    }

    @Override
    @CommonTx
    public void deleteWithContent(Kind kind, String uuid) throws Exception {
        JoiUserSetting setting = setting();
        AnimationFolders tree = new AnimationFolders(ofKind(setting, kind));
        Set<String> gone = tree.withDescendants(uuid);
        if (gone.isEmpty()) {
            return;
        }
        if (kind == Kind.CLIPS) {
            for (Animation clip : animationService.findAllForUser()) {
                if (gone.contains(clip.getFolder())) {
                    animationService.delete(clip, false);
                }
            }
        } else {
            for (AnimationPack pack : packService.findAllForUser()) {
                if (gone.contains(pack.getFolder())) {
                    packService.delete(pack, false);
                }
            }
        }
        List<AnimationFolder> folders = new ArrayList<>(setting.getAnimationFolders());
        folders.removeIf(f -> f != null && f.getKind() == kind && gone.contains(f.getUuid()));
        store(setting, folders);
    }

    @Override
    @CommonTxReadOnly
    public List<Animation> clipsIn(String folder) throws Exception {
        AnimationFolders tree = tree(Kind.CLIPS);
        String in = tree.known(folder);
        return animationService.findAllByName().stream().filter(clip -> Objects.equals(tree.known(clip.getFolder()), in)).toList();
    }

    @Override
    @CommonTxReadOnly
    public List<AnimationPack> animationsIn(String folder) throws Exception {
        AnimationFolders tree = tree(Kind.ANIMATIONS);
        String in = tree.known(folder);
        return packService.findAllByName().stream().filter(pack -> Objects.equals(tree.known(pack.getFolder()), in)).toList();
    }

    @Override
    @CommonTx
    public void moveClips(Collection<Animation> clips, String folder) throws Exception {
        String target = tree(Kind.CLIPS).known(folder);
        for (Animation clip : clips) {
            Animation stored = animationService.find(clip.getId());
            if (stored != null && !Objects.equals(stored.getFolder(), target)) {
                stored.setFolder(target);
                animationService.save(stored);
            }
        }
    }

    @Override
    @CommonTx
    public void moveAnimations(Collection<AnimationPack> animations, String folder) throws Exception {
        String target = tree(Kind.ANIMATIONS).known(folder);
        for (AnimationPack pack : animations) {
            AnimationPack stored = packService.find(pack.getId());
            if (stored != null && !Objects.equals(stored.getFolder(), target)) {
                stored.setFolder(target);
                packService.save(stored);
            }
        }
    }

    @Override
    @CommonTxReadOnly
    public List<Animation> clipsByPath() throws Exception {
        return tree(Kind.CLIPS).sortedByLabel(animationService.findAllForUser(), Animation::getFolder, Animation::getName);
    }

    @Override
    @CommonTxReadOnly
    public List<AnimationPack> animationsByPath() throws Exception {
        return tree(Kind.ANIMATIONS).sortedByLabel(packService.findAllForUser(), AnimationPack::getFolder,
                pack -> StringUtils.defaultString(pack.getName()));
    }

    private void store(JoiUserSetting setting, List<AnimationFolder> folders) throws Exception {
        setting.setAnimationFolders(folders);
        settingService.save(setting);
    }
}
