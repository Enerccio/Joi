package com.github.enerccio.joi.domain.animations;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The translation of a step: an offset in metres, kept within bounds, none when it does not move; stored with the step. */
class StepTranslationTest {

    @Test
    void anOffsetIsKeptAndNothingIsNull() {
        assertThat(StepTranslation.of(0.25, 0, -1.5)).containsExactly(0.25, 0.0, -1.5);
        assertThat(StepTranslation.of(0, 0, 0)).isNull();
        // less than a tenth of a millimetre is nothing
        assertThat(StepTranslation.of(0.00001, -0.00002, 0)).isNull();
        assertThat(StepTranslation.of(0.001, 0, 0)).containsExactly(0.001, 0.0, 0.0);
    }

    @Test
    void aWrongOneIsNoneAndAFarOneIsHeldWithinBounds() {
        assertThat(StepTranslation.normalized(null)).isNull();
        assertThat(StepTranslation.normalized(List.of(1.0, 2.0))).isNull();
        assertThat(StepTranslation.normalized(List.of(1.0, Double.NaN, 0.0))).isNull();
        assertThat(StepTranslation.normalized(Arrays.asList(1.0, null, 0.0))).isNull();
        assertThat(StepTranslation.of(25, -40, 3)).containsExactly(StepTranslation.MAX, -StepTranslation.MAX, 3.0);
        assertThat(StepTranslation.toArray(null)).containsExactly(0, 0, 0);
        assertThat(StepTranslation.toArray(List.of(0.5, 0.0, -0.1))).containsExactly(0.5, 0, -0.1);
    }

    @Test
    void aStepKeepsItsTranslationNextToItsRotation() {
        AnimationStep step = new AnimationStep("clip", true, 1, false, false)
                .withRotation(StepRotation.fromDegrees(0, 90, 0))
                .withTranslation(StepTranslation.of(0.5, 0, -0.25));
        List<AnimationStep> read = AnimationStep.listFromJson(AnimationStep.toJson(List.of(step, step.withTranslation(null))));
        assertThat(read.getFirst().translation()).containsExactly(0.5, 0.0, -0.25);
        assertThat(read.getFirst().rotation()).isEqualTo(step.rotation());
        assertThat(read.get(1).translation()).isNull();
        // the other changes keep it
        assertThat(step.withLoop(false).withSpeed(2).withBlend(1).withReverse(true).withInPlace(true).translation())
                .containsExactly(0.5, 0.0, -0.25);
        // a step stored before steps were moved has none
        assertThat(AnimationStep.listFromJson("[{\"clip\":\"c\",\"loop\":true,\"speed\":1,\"inPlace\":false,\"reverse\":false}]")
                .getFirst().translation()).isNull();
    }
}
