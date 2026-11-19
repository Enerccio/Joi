package com.github.enerccio.joi.domain.repository.impl;

import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.repository.AnimationRepository;
import com.github.enerccio.marginalia.domain.repository.impl.JpaExtendableRepository;

public class JpaAnimationRepository extends JpaExtendableRepository<Animation> implements AnimationRepository {

    @Override
    protected Class<Animation> getEntityClass() {
        return Animation.class;
    }
}
