package com.github.enerccio.joi.domain.bodies;

import com.github.enerccio.joi.domain.model.impl.Model;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The tree of toggles: a shape key is part of its mesh, the user can say otherwise, and what is seen follows the parents. */
class ToggleTreeTest {

    private static final ModelCapabilities CAPABILITIES = new ModelCapabilities("1.0", List.of(), List.of(
            new ModelCapabilities.Toggle("Bodysuit", null, false, true),
            new ModelCapabilities.Toggle("Bodysuit", "Hide Panties", true, true),
            new ModelCapabilities.Toggle("Headgear", null, false, true),
            new ModelCapabilities.Toggle("Headgear", "Hide Halo", true, true),
            new ModelCapabilities.Toggle("Scarf", null, false, true)), List.of(), List.of());

    @Test
    void aShapeKeyIsPartOfItsMeshAndPartsFollowTheirParents() {
        ToggleTree tree = ToggleTree.of(new Model(), CAPABILITIES);

        assertThat(tree.parent("key:Bodysuit/Hide Panties")).isEqualTo("mesh:Bodysuit");
        assertThat(tree.parent("mesh:Bodysuit")).isNull();
        assertThat(tree.order()).containsExactly("mesh:Bodysuit", "key:Bodysuit/Hide Panties", "mesh:Headgear", "key:Headgear/Hide Halo",
                "mesh:Scarf");

        // the bodysuit hidden hides the panties whatever their own state; the rest is as it is
        Map<String, Boolean> own = new LinkedHashMap<>();
        CAPABILITIES.toggles().forEach(toggle -> own.put(toggle.id(), true));
        own.put("mesh:Bodysuit", false);
        Map<String, Boolean> seen = tree.effective(own);
        assertThat(seen).containsEntry("mesh:Bodysuit", false).containsEntry("key:Bodysuit/Hide Panties", false)
                .containsEntry("key:Headgear/Hide Halo", true).containsEntry("mesh:Scarf", true);
        // and its own state is not touched: shown again, the panties are as they were
        own.put("key:Bodysuit/Hide Panties", false);
        own.put("mesh:Bodysuit", true);
        assertThat(tree.effective(own)).containsEntry("key:Bodysuit/Hide Panties", false).containsEntry("mesh:Bodysuit", true);
    }

    @Test
    void theUserCanChangeTheTreeAndALoopIsCut() {
        Model model = new Model();
        // the scarf is part of the headgear, the halo of none
        model.setToggleParentMap(Map.of("mesh:Scarf", "mesh:Headgear", "key:Headgear/Hide Halo", ""));
        ToggleTree tree = ToggleTree.of(model, CAPABILITIES);
        assertThat(tree.parent("mesh:Scarf")).isEqualTo("mesh:Headgear");
        assertThat(tree.parent("key:Headgear/Hide Halo")).isNull();
        assertThat(tree.depth("mesh:Scarf")).isEqualTo(1);
        assertThat(tree.isAbove("mesh:Headgear", "mesh:Scarf")).isTrue();

        // a part of its own part: the loop is cut, whatever else is said
        model.setToggleParentMap(Map.of("mesh:Headgear", "mesh:Scarf", "mesh:Scarf", "mesh:Headgear", "key:Headgear/Hide Halo", "key:Headgear/Hide Halo"));
        ToggleTree looped = ToggleTree.of(model, CAPABILITIES);
        assertThat(looped.parent("key:Headgear/Hide Halo")).isNull();
        assertThat(looped.ancestors("mesh:Scarf").size()).isLessThan(2);
        assertThat(looped.order()).containsExactlyInAnyOrderElementsOf(CAPABILITIES.toggles().stream().map(ModelCapabilities.Toggle::id).toList());
    }
}
