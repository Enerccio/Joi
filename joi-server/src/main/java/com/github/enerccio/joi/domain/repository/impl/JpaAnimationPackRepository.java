package com.github.enerccio.joi.domain.repository.impl;

import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.repository.AnimationPackRepository;
import com.github.enerccio.marginalia.domain.repository.impl.JpaExtendableRepository;

public class JpaAnimationPackRepository extends JpaExtendableRepository<AnimationPack> implements AnimationPackRepository {

    @Override
    protected Class<AnimationPack> getEntityClass() {
        return AnimationPack.class;
    }
}
