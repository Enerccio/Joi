package com.github.enerccio.joi.domain.bodies;

import com.github.enerccio.joi.domain.model.impl.Model;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * How she looks in a model in a state: the model's description of what never changes, then, for each toggle described
 * apart, its sentence for the state it is in. So the description stays true when a part is switched: the visor off
 * says how her eyes look.
 */
public final class Appearance {

    private Appearance() {
    }

    /**
     * @param state the toggle's id → whether the part is shown; a toggle not in it is as the model comes
     * @return the description, null when the model has none
     */
    public static String of(Model model, Map<String, Boolean> state) {
        ModelCapabilities capabilities = ModelCapabilities.fromJson(model.getCapabilities());
        List<String> sentences = new ArrayList<>();
        if (StringUtils.isNotBlank(model.getAppearance())) {
            sentences.add(model.getAppearance().strip());
        }
        if (capabilities != null) {
            for (ModelCapabilities.Toggle toggle : capabilities.toggles()) {
                if (!model.describedToggles(capabilities).contains(toggle.id())) {
                    continue;
                }
                boolean shown = state.getOrDefault(toggle.id(), toggle.shown());
                String sentence = (shown ? model.getPartShown() : model.getPartHidden()).get(toggle.id());
                if (StringUtils.isNotBlank(sentence)) {
                    sentences.add(sentence.strip());
                }
            }
        }
        return sentences.isEmpty() ? null : String.join(" ", sentences);
    }

    /** As the model comes (each toggle in its default state). */
    public static String of(Model model) {
        return of(model, Map.of());
    }
}
