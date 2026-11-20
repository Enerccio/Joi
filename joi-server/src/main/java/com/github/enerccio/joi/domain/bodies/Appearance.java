package com.github.enerccio.joi.domain.bodies;

import com.github.enerccio.joi.domain.model.impl.Model;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * How she looks in a model in a state: the model's description, which is of the model in its base state (the default
 * state with the layers of her look off: {@link Model#bareShown}), then, for each part described apart
 * ({@link DescribedPart}: a group of toggles, or a toggle) that is not in its base state, the sentence of the state it
 * is in, what changed. So the description stays true when a part is switched: the visor off says how her eyes look,
 * and the layers worn add themselves in turn, the bare model first.
 */
public final class Appearance {

    private Appearance() {
    }

    /**
     * @param state the toggle's id → whether the part is shown; a toggle not in it is as by default
     * @return the description, null when the model has none
     */
    public static String of(Model model, Map<String, Boolean> state) {
        ModelCapabilities capabilities = ModelCapabilities.fromJson(model.getCapabilities());
        List<String> sentences = new ArrayList<>();
        if (StringUtils.isNotBlank(model.getAppearance())) {
            sentences.add(model.getAppearance().strip());
        }
        Map<String, Boolean> bare = model.bareShown(capabilities);
        Map<String, Boolean> full = model.defaultShown(capabilities);
        full.putAll(state);
        ToggleTree tree = ToggleTree.of(model, capabilities);
        for (DescribedPart part : DescribedPart.of(model, capabilities)) {
            DescribedPart.State in = part.stateIn(full);
            // a part under a hidden one is not seen: what the hidden one says covers it
            boolean covered = part.toggles().stream().allMatch(toggle -> tree.ancestors(toggle.id()).stream()
                    .anyMatch(above -> !full.getOrDefault(above, true)));
            DescribedPart.State base = part.stateIn(bare);
            String sentence = in == null || covered || base != null && base.id().equals(in.id()) ? null : part.sentence(model, in.id());
            if (sentence != null) {
                sentences.add(sentence);
            }
        }
        return sentences.isEmpty() ? null : String.join(" ", sentences);
    }

    /** In the model's default state (the bare model and the layers it is dressed in by default). */
    public static String of(Model model) {
        return of(model, Map.of());
    }
}
