package com.github.enerccio.joi.domain.repository;

import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.marginalia.domain.repository.ExtendableRepository;

import java.util.List;

/** The animation clips, in their packs. */
public interface AnimationRepository extends ExtendableRepository<Animation> {

    /** The clips of the pack, oldest first. */
    List<Animation> findByPack(AnimationPack pack) throws Exception;

    /** How many clips the pack has. */
    long countByPack(AnimationPack pack) throws Exception;
}
