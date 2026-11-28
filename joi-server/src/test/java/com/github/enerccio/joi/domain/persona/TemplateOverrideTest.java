package com.github.enerccio.joi.domain.persona;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** When a template of a link of the chain differs from the one it inherits. */
class TemplateOverrideTest {

    @Test
    void aSetTextDiffersFromADifferentSetParent() {
        assertThat(TemplateOverride.differs("Hello {{user}}", "Hi {{user}}")).isTrue();
    }

    @Test
    void notSetOrNoParentNeverDiffers() {
        assertThat(TemplateOverride.differs(null, "Hi")).isFalse();
        assertThat(TemplateOverride.differs("  \n", "Hi")).isFalse();
        assertThat(TemplateOverride.differs("Hi", null)).isFalse();
        assertThat(TemplateOverride.differs("Hi", " ")).isFalse();
    }

    @Test
    void theSameTextDoesNotDifferWhateverTheEndsOfLines() {
        assertThat(TemplateOverride.differs("a\nb", "a\nb")).isFalse();
        assertThat(TemplateOverride.differs("a\r\nb\n", "a\nb")).isFalse();
        assertThat(TemplateOverride.differs("a\n\nb", "a\nb")).isTrue();
    }
}
