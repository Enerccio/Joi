package com.github.enerccio.joi.ui.dialogs.joi;

import com.github.enerccio.joi.domain.collections.MemoryImportance;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Memory;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.MemoryService;
import com.github.enerccio.joi.ui.dialogs.JoiDialog;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The Memories tab: added, edited and stored as typed, deleted one by one or checked in a shift-click range. */
class JoiMemoriesPartTest extends PlatformTestBase {

    @Autowired
    private JoiService joiService;
    @Autowired
    private MemoryService memoryService;

    private UI ui;

    @BeforeEach
    void setUp() throws Exception {
        ui = new UI();
        UI.setCurrent(ui);
        login();
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
        ui = null;
    }

    private JoiMemoriesPart memories(Joi joi) throws Exception {
        JoiDialog dialog = new JoiDialog(joiService.find(joi.getId()));
        dialog.create();
        return dialog.getMemoriesPart();
    }

    @Test
    void aMemoryIsAddedOpenAndStoredAsItIsEdited() throws Exception {
        Joi joi = joiService.create("Mira");
        JoiMemoriesPart part = memories(joi);
        assertThat(part.getMemories()).isEmpty();

        Memory added = part.addForTest();
        assertThat(part.getGrid().isDetailsVisible(added)).isTrue();
        part.editForTest(added, "Coffee", MemoryImportance.IMPORTANT, "coffee, morning", "He takes his coffee black.");

        Memory stored = memoryService.findAll(joi).getFirst();
        assertThat(stored.getName()).isEqualTo("Coffee");
        assertThat(stored.getImportance()).isEqualTo(MemoryImportance.IMPORTANT);
        assertThat(stored.getKeywords()).isEqualTo("coffee, morning");
        assertThat(stored.getText()).isEqualTo("He takes his coffee black.");
        assertThat(stored.getApproved()).isTrue();
        assertThat(stored.getSourceMessage()).isNull();

        // the dialog opened again shows it
        assertThat(memories(joi).getMemories()).extracting(Memory::getName).containsExactly("Coffee");
    }

    @Test
    void shiftClickChecksARangeAndTheCheckedAreDeleted() throws Exception {
        Joi joi = joiService.create("Mira");
        Joi other = joiService.create("Kairo");
        for (int n = 0; n < 5; n++) {
            Memory memory = memoryService.create(joi);
            memory.setName("m" + n);
            memoryService.update(joi, memory);
        }
        Memory foreign = memoryService.create(other);

        JoiMemoriesPart part = memories(joi);
        List<Memory> shown = List.copyOf(part.getMemories());
        assertThat(shown).extracting(Memory::getName).containsExactly("m4", "m3", "m2", "m1", "m0");

        part.toggleForTest(shown.get(0), false);
        part.toggleForTest(shown.get(3), true);
        assertThat(part.getGrid().getSelectedItems()).containsExactlyInAnyOrder(shown.get(0), shown.get(1), shown.get(2), shown.get(3));
        // a shift-click on a checked one unchecks the range back to it
        part.toggleForTest(shown.get(2), true);
        assertThat(part.getGrid().getSelectedItems()).containsExactlyInAnyOrder(shown.get(0), shown.get(1));

        part.deleteSelectedForTest();
        assertThat(part.getMemories()).extracting(Memory::getName).containsExactly("m2", "m1", "m0");
        assertThat(memoryService.findAll(joi)).extracting(Memory::getName).containsExactly("m2", "m1", "m0");

        // another assistant's memory is not hers to change or delete
        foreign.setName("taken");
        assertThat(memoryService.update(joi, foreign)).isNull();
        assertThat(memoryService.deleteMemories(joi, List.of(foreign.getId()))).isZero();
        assertThat(memoryService.findAll(other)).hasSize(1);
    }
}
