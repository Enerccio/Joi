package com.github.enerccio.joi.domain.service.impl;

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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
    @CommonTx
    public void deleteWithClips(AnimationPack pack) throws Exception {
        AnimationPack stored = find(pack.getId());
        for (Animation animation : animationService.findByPack(stored)) {
            animationService.delete(animation, false);
        }
        delete(stored, false);
    }

    @Override
    @CommonTxReadOnly
    public List<Body> previewBodies() throws Exception {
        java.util.Map<Joi, String> names = new java.util.HashMap<>();
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
