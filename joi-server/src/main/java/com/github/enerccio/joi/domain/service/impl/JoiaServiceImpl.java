package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.animations.AnimationStep;
import com.github.enerccio.joi.domain.animations.VrmaFiles;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.service.AnimationPackService;
import com.github.enerccio.joi.domain.service.AnimationService;
import com.github.enerccio.joi.domain.service.AnimationService.UuidUse;
import com.github.enerccio.joi.domain.service.JoiaService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.protocol.joia.JoiaFile;
import com.github.enerccio.joi.protocol.joia.JoiaMetadata;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
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

    @Override
    public String fileName(List<AnimationPack> animations) {
        return JoiaFile.fileName(animations.size() == 1 ? animations.getFirst().getName() : null);
    }

    @Override
    public void export(List<AnimationPack> animations, OutputStream out) throws Exception {
        Map<String, JoiaMetadata.Clip> clips = new LinkedHashMap<>();
        Map<String, File> files = new HashMap<>();
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
                }
                AnimationStep s = step.step();
                steps.add(new JoiaMetadata.Step(clip.getUuid(), s.loop(), s.speedOrDefault(), s.inPlace()));
            }
            exported.add(new JoiaMetadata.Animation(stored.getUuid(), stored.getName(), stored.getDescription(), stored.getLoop(), steps));
        }
        JoiaMetadata meta = new JoiaMetadata(exported, new ArrayList<>(clips.values()), Instant.now().toString());
        List<File> clipFiles = new ArrayList<>();
        for (String uuid : clips.keySet()) {
            clipFiles.add(files.get(uuid));
        }
        JoiaFile.write(out, meta, clipFiles);
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
                        steps.add(new AnimationStep(clip, step.loop(), step.speed() > 0 ? step.speed() : 1, step.inPlace()));
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
