package com.github.enerccio.joi.protocol.http;

import java.util.List;

/**
 * {@code GET /api/v1/jois/{id}/body}: the body she is in, its models and the state of their parts, and its animations.
 *
 * @param id                the id of the body
 * @param name              its name, null when it has none
 * @param model             the full model, null when none was chosen
 * @param lookingGlassModel the lighter model for the Looking Glass, null for the full one there too
 * @param parts             the parts of the full model that can be switched, each in the state the body is in
 * @param toggles           every toggle of the full model as the body has it now (since 1.5), null from a server before
 * @param appearance        how she looks in it now, null when the model is not described
 * @param animations        the animations it plays
 * @param outfits           its outfits, the one she wears marked (since 1.12), null from a server before
 */
public record BodyInfo(String id, String name, BodyModel model, BodyModel lookingGlassModel, List<BodyPart> parts,
                       List<BodyToggle> toggles, String appearance, BodyAnimations animations, List<BodyOutfit> outfits) {

    public BodyInfo(String id, String name, BodyModel model, BodyModel lookingGlassModel, List<BodyPart> parts,
                    List<BodyToggle> toggles, String appearance, BodyAnimations animations) {
        this(id, name, model, lookingGlassModel, parts, toggles, appearance, animations, null);
    }
}
