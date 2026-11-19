package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.animations.AnimationStep;
import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.repository.AnimationPackRepository;
import com.github.enerccio.joi.domain.service.AnimationPackService;
import com.github.enerccio.joi.domain.service.AnimationService;
import com.github.enerccio.joi.domain.service.BodyService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.marginalia.domain.service.impl.ExtendableServiceImpl;
import com.github.enerccio.marginalia.domain.traits.CommonTx;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

public class AnimationPackServiceImpl extends ExtendableServiceImpl<AnimationPack, AnimationPackRepository> implements AnimationPackService {

    @Autowired
    private AnimationService animationService;

    @Autowired
    private JoiService joiService;

    @Autowired
    private BodyService bodyService;

    @Override
    @CommonTxReadOnly
    public List<AnimationPack> findAllByName() throws Exception {
        List<AnimationPack> packs = new ArrayList<>(findAllForUser());
        packs.sort(Comparator.comparing((AnimationPack pack) -> StringUtils.defaultString(pack.getName()), String.CASE_INSENSITIVE_ORDER));
        return packs;
    }

    @Override
    @CommonTxReadOnly
    public List<Step> steps(AnimationPack pack) throws Exception {
        List<AnimationStep> steps = pack == null ? List.of() : pack.steps();
        if (steps.isEmpty()) {
            return List.of();
        }
        Map<String, Animation> byUuid = new HashMap<>();
        for (Animation clip : animationService.findAllForUser()) {
            byUuid.put(clip.getUuid(), clip);
        }
        List<Step> resolved = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Animation clip = byUuid.get(steps.get(i).clip());
            if (clip != null) {
                resolved.add(new Step(i, steps.get(i), clip));
            }
        }
        return resolved;
    }

    @Override
    @CommonTxReadOnly
    public List<Animation> clips(AnimationPack pack) throws Exception {
        return steps(pack).stream().map(Step::clip).toList();
    }

    @Override
    @CommonTx
    public AnimationPack addClips(AnimationPack pack, Collection<Animation> clips) throws Exception {
        AnimationPack stored = find(pack.getId());
        List<AnimationStep> steps = stored.steps();
        Set<String> present = new HashSet<>(stored.clipUuids());
        StringBuilder description = new StringBuilder(StringUtils.defaultString(StringUtils.stripEnd(stored.getDescription(), null)));
        for (Animation clip : clips) {
            steps.add(AnimationStep.of(clip));
            String text = StringUtils.strip(clip.getDescription());
            if (present.add(clip.getUuid()) && StringUtils.isNotEmpty(text)) {
                if (!description.isEmpty()) {
                    description.append('\n');
                }
                description.append(text);
            }
        }
        stored.setStepList(steps);
        stored.setDescription(StringUtils.defaultIfEmpty(description.toString(), null));
        return save(stored);
    }

    @Override
    @CommonTx
    public AnimationPack createFrom(Animation clip) throws Exception {
        AnimationPack pack = new AnimationPack();
        pack.setName(clip.getName());
        return addClips(save(pack), List.of(clip));
    }

    @Override
    @CommonTx
    public AnimationPack updateStep(AnimationPack pack, int index, AnimationStep step) throws Exception {
        AnimationPack stored = find(pack.getId());
        List<AnimationStep> steps = stored.steps();
        if (index >= 0 && index < steps.size()) {
            steps.set(index, step);
            stored.setStepList(steps);
            stored = save(stored);
        }
        return stored;
    }

    @Override
    @CommonTx
    public AnimationPack moveStep(AnimationPack pack, int index, int delta) throws Exception {
        AnimationPack stored = find(pack.getId());
        List<AnimationStep> steps = stored.steps();
        int target = index + delta;
        if (index >= 0 && index < steps.size() && target >= 0 && target < steps.size()) {
            steps.add(target, steps.remove(index));
            stored.setStepList(steps);
            stored = save(stored);
        }
        return stored;
    }

    @Override
    @CommonTx
    public AnimationPack removeStep(AnimationPack pack, int index) throws Exception {
        AnimationPack stored = find(pack.getId());
        List<AnimationStep> steps = stored.steps();
        if (index >= 0 && index < steps.size()) {
            steps.remove(index);
            stored.setStepList(steps);
            stored = save(stored);
        }
        return stored;
    }

    @Override
    @CommonTx
    public AnimationPack removeClip(AnimationPack pack, Animation clip) throws Exception {
        AnimationPack stored = find(pack.getId());
        List<AnimationStep> steps = stored.steps();
        steps.removeIf(step -> step.clip().equals(clip.getUuid()));
        stored.setStepList(steps);
        return save(stored);
    }

    @Override
    @CommonTxReadOnly
    public List<AnimationPack> packsWith(Animation clip) throws Exception {
        List<AnimationPack> packs = new ArrayList<>();
        for (AnimationPack pack : findAllByName()) {
            if (pack.clipUuids().contains(clip.getUuid())) {
                packs.add(pack);
            }
        }
        return packs;
    }

    @Override
    @CommonTxReadOnly
    public List<Body> previewBodies() throws Exception {
        Map<Joi, String> names = new HashMap<>();
        for (Joi joi : joiService.findAllForUser()) {
            names.put(joi, StringUtils.defaultString(joiService.name(joi)));
        }
        List<Joi> jois = new ArrayList<>(names.keySet());
        jois.sort(Comparator.comparing(names::get, String.CASE_INSENSITIVE_ORDER));
        List<Body> bodies = new ArrayList<>();
        for (Joi joi : jois) {
            for (Body body : bodyService.findForJoi(joi)) {
                if (body.getModel() != null) {
                    bodies.add(body);
                }
            }
        }
        return bodies;
    }

    @Override
    @CommonTxReadOnly
    public Body previewBody(AnimationPack pack) throws Exception {
        if (pack.getPreviewBodyId() == null) {
            return null;
        }
        Long id = pack.getPreviewBodyId();
        return previewBodies().stream().filter(body -> body.getId().equals(id)).findFirst().orElse(null);
    }
}
