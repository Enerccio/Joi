package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.model.impl.Joi;

/**
 * Her stance: an animation of hers she holds (dancing, sitting, a pose), looped by her nodes in place of her idle and
 * talking animations until she ends it or takes another (see {@code DESIGN.md} → Bodies, Stances). It is hers, kept on
 * her ({@link Joi#getStance()}), so it lasts through turns, reconnects and restarts; her nodes get it as the
 * {@code stance} frame ({@link NodeService#stance}). She takes and ends it with her functions ({@code set_stance},
 * {@code end_stance}); the user can end it in her dialog.
 */
public interface StanceService {

    /** The animation she holds now, null for none (or one deleted since). */
    AnimationPack current(Joi joi) throws Exception;

    /**
     * She takes a stance: kept, a note in her conversation, and her nodes play it.
     *
     * @return false when she already held it (nothing changed)
     */
    boolean take(Joi joi, AnimationPack animation) throws Exception;

    /**
     * Her stance ends: back to idle and talking. When the user ended it ({@code byUser}), she is told as an event.
     *
     * @return the stance that ended, null when she had none
     */
    AnimationPack end(Joi joi, boolean byUser) throws Exception;
}
