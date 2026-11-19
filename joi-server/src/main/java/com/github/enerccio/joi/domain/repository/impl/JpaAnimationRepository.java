package com.github.enerccio.joi.domain.repository.impl;

import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.repository.AnimationRepository;
import com.github.enerccio.marginalia.domain.repository.impl.JpaExtendableRepository;

import java.util.List;

public class JpaAnimationRepository extends JpaExtendableRepository<Animation> implements AnimationRepository {

    @Override
    protected Class<Animation> getEntityClass() {
        return Animation.class;
    }

    @Override
    public List<Animation> findByPack(AnimationPack pack) throws Exception {
        return entityManager.createQuery("SELECT a FROM Animation a WHERE a.deleted = false AND a.pack = :pack ORDER BY a.id ASC", Animation.class)
                .setParameter("pack", pack).getResultList();
    }

    @Override
    public long countByPack(AnimationPack pack) throws Exception {
        return entityManager.createQuery("SELECT COUNT(a) FROM Animation a WHERE a.deleted = false AND a.pack = :pack", Long.class)
                .setParameter("pack", pack).getSingleResult();
    }
}
