package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.animations.AnimationStep;
import com.github.enerccio.joi.domain.animations.VrmaFiles;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.service.AnimationPackService;
import com.github.enerccio.joi.domain.service.AnimationService;
import com.github.enerccio.joi.domain.service.AnimationService.UuidUse;
import com.github.enerccio.joi.domain.service.BodyService;
import com.github.enerccio.joi.domain.service.JoiaService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.protocol.joia.JoiaFile;
import com.github.enerccio.joi.protocol.joia.JoiaMetadata;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes and reads the files outside a transaction; the clip and animation services store what was imported in their
 * own.
 */
public class JoiaServiceImpl implements JoiaService {

    @Autowired
    private AnimationService animationService;

    @Autowired
    private AnimationPackService packService;

    @Autowired
    private ModelService modelService;

    @Autowired
    private BodyService bodyService;

    @Override
    public String fileName(List<AnimationPack> animations) {
        return JoiaFile.fileName(animations.size() == 1 ? animations.getFirst().getName() : null);
    }

    /**
     * What a {@code .joia} of animations holds: the metadata (no time yet), the file of each clip in its order, and
     * the fingerprint of both (a clip's file is a resource that never changes, so its id stands for its bytes).
     */
    private record Collected(JoiaMetadata meta, List<File> files, String fingerprint) {
    }

    private Collected collect(List<AnimationPack> animations) throws Exception {
        Map<String, JoiaMetadata.Clip> clips = new LinkedHashMap<>();
        Map<String, File> files = new HashMap<>();
        StringBuilder resources = new StringBuilder();
        List<JoiaMetadata.Animation> exported = new ArrayList<>();
        for (AnimationPack animation : animations) {
            // only the user's own
            AnimationPack stored = packService.findForUser(animation.getUuid());
            if (stored == null) {
                continue;
            }
            List<JoiaMetadata.Step> steps = new ArrayList<>();
            for (AnimationPackService.Step step : packService.steps(stored)) {
                Animation clip = step.clip();
                if (clip == null) {
                    continue;
                }
                if (!files.containsKey(clip.getUuid())) {
                    File file = animationService.file(clip);
                    if (file == null || !file.isFile()) {
                        continue;
                    }
                    files.put(clip.getUuid(), file);
                    clips.put(clip.getUuid(), clip(clip));
                    resources.append(clip.getUuid()).append('=').append(clip.getResource().getId()).append(';');
                }
                AnimationStep s = step.step();
                steps.add(new JoiaMetadata.Step(clip.getUuid(), s.loop(), s.speedOrDefault(), s.inPlace(), s.reverse(),
                        s.rotation(), s.blend()));
            }
            exported.add(new JoiaMetadata.Animation(stored.getUuid(), stored.getName(), stored.getDescription(), stored.getLoop(), steps));
        }
        JoiaMetadata meta = new JoiaMetadata(exported, new ArrayList<>(clips.values()), null);
        List<File> clipFiles = new ArrayList<>();
        for (String uuid : clips.keySet()) {
            clipFiles.add(files.get(uuid));
        }
        String fingerprint = DigestUtils.sha256Hex((meta.toJson() + "\n" + resources).getBytes(StandardCharsets.UTF_8));
        return new Collected(meta, clipFiles, fingerprint);
    }

    private static JoiaMetadata exportedNow(JoiaMetadata meta) {
        return new JoiaMetadata(meta.animations(), meta.clips(), Instant.now().toString());
    }

    @Override
    public void export(List<AnimationPack> animations, OutputStream out) throws Exception {
        Collected collected = collect(animations);
        JoiaFile.write(out, exportedNow(collected.meta()), collected.files());
    }

    @Override
    public boolean needsBodyExport(Body body) throws Exception {
        Body stored = body.getId() == null ? null : bodyService.find(body.getId());
        if (stored == null || stored.isDeleted()) {
            return false;
        }
        Collected collected = collect(bodyService.animations(stored));
        return !collected.fingerprint().equals(stored.getAnimationsFingerprint())
                || (!collected.meta().animations().isEmpty() && bodyService.animationsFile(stored) == null);
    }

    @Override
    public Body exportBody(Body body) throws Exception {
        Body stored = bodyService.find(body.getId());
        Collected collected = collect(bodyService.animations(stored));
        if (collected.meta().animations().isEmpty()) {
            return bodyService.linkAnimations(stored, null, collected.fingerprint(), null);
        }
        File out = ModelFiles.uploadFile(modelService.uploadFolder(), "body." + JoiaFile.EXTENSION);
        try {
            String hash = JoiaFile.write(out, exportedNow(collected.meta()), collected.files());
            return bodyService.linkAnimations(stored, out, collected.fingerprint(), hash);
        } catch (Exception e) {
            FileUtils.deleteQuietly(out);
            throw e;
        }
    }

    private static JoiaMetadata.Clip clip(Animation clip) {
        return new JoiaMetadata.Clip(clip.getUuid(), clip.getName(), clip.getDescription(), clip.getTags(), clip.getLoop(),
                clip.getInPlace(), clip.getSpeed(), clip.getFileName(), clip.getSource(), clip.getTake(), clip.getDuration(),
                clip.getBones(), clip.getExpressions(), null, null);
    }

    @Override
    public Imported importFile(File joia) throws Exception {
        File folder = modelService.uploadFolder();
        Map<String, UuidUse> uses = new HashMap<>();
        Map<Integer, File> written = new LinkedHashMap<>();
        try {
            JoiaFile.Contents contents = JoiaFile.read(joia, new JoiaFile.Reader() {
                @Override
                public void metadata(JoiaMetadata meta) throws IOException {
                    try {
                        for (JoiaMetadata.Clip clip : meta.clips()) {
                            UuidUse use = animationService.uuidUse(clip.uuid());
                            if (use == UuidUse.OTHER_USER) {
                                throw new ForeignClipException(StringUtils.defaultString(clip.name(), clip.uuid()));
                            }
                            uses.put(clip.uuid(), use);
                        }
                    } catch (IOException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IOException(e);
                    }
                }

                @Override
                public File clip(int index, JoiaMetadata.Clip clip) {
                    if (uses.get(clip.uuid()) == UuidUse.MINE) {
                        return null;
                    }
                    File file = ModelFiles.uploadFile(folder, "clip." + VrmaFiles.EXTENSION);
                    written.put(index, file);
                    return file;
                }
            });
            JoiaMetadata meta = contents.meta();

            // the uuid of each clip of the file, as it is in the library now
            Map<String, String> linked = new HashMap<>();
            int added = 0;
            int failed = 0;
            for (JoiaMetadata.Clip clip : meta.clips()) {
                if (uses.get(clip.uuid()) == UuidUse.MINE) {
                    linked.put(clip.uuid(), clip.uuid());
                }
            }
            for (Map.Entry<Integer, File> entry : new ArrayList<>(written.entrySet())) {
                JoiaMetadata.Clip clip = meta.clips().get(entry.getKey());
                String fileName = StringUtils.defaultIfBlank(clip.fileName(), StringUtils.defaultIfBlank(clip.name(), "clip") + "." + VrmaFiles.EXTENSION);
                if (!VrmaFiles.isVrma(fileName)) {
                    fileName = fileName + "." + VrmaFiles.EXTENSION;
                }
                Animation stored;
                try {
                    // a uuid of the user's deleted clip can't be had again: the clip gets a new one
                    stored = animationService.add(entry.getValue(), fileName, uses.get(clip.uuid()) == UuidUse.FREE ? clip.uuid() : null);
                } catch (IllegalArgumentException e) {
                    failed++;
                    written.remove(entry.getKey());
                    continue;
                }
                written.remove(entry.getKey());
                if (clip.name() != null) {
                    stored.setName(clip.name());
                }
                stored.setDescription(clip.description());
                stored.setTags(clip.tags());
                stored.setLoop(clip.loop());
                stored.setInPlace(clip.inPlace());
                stored.setSpeed(clip.speed());
                stored.setSource(clip.source());
                stored.setTake(clip.take());
                stored = animationService.save(stored);
                linked.put(clip.uuid(), stored.getUuid());
                added++;
            }

            List<AnimationPack> animations = new ArrayList<>();
            for (JoiaMetadata.Animation animation : meta.animations()) {
                List<AnimationStep> steps = new ArrayList<>();
                for (JoiaMetadata.Step step : animation.steps()) {
                    String clip = linked.get(step.clip());
                    if (clip != null) {
                        steps.add(new AnimationStep(clip, step.loop(), step.speed() > 0 ? step.speed() : 1, step.inPlace(),
                                step.reverse(), step.rotation(), step.blend()));
                    }
                }
                AnimationPack pack = new AnimationPack();
                pack.setName(animation.name());
                pack.setDescription(animation.description());
                pack.setLoop(animation.loop());
                pack.setStepList(steps);
                animations.add(packService.save(pack));
            }
            return new Imported(animations, added, (int) uses.values().stream().filter(use -> use == UuidUse.MINE).count(), failed);
        } finally {
            for (File file : written.values()) {
                FileUtils.deleteQuietly(file);
            }
            FileUtils.deleteQuietly(joia);
        }
    }
}
