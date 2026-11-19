package com.github.enerccio.joi.domain.animations;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The rotation of a step: a unit quaternion, and the degrees the dialog shows for it (three.js's YXZ order). */
class StepRotationTest {

    @Test
    void degreesAndQuaternionsAreTheSameRotation() {
        // a quarter turn around Y, as three.js makes it
        List<Double> quarter = StepRotation.fromDegrees(0, 90, 0);
        assertThat(quarter.get(0)).isCloseTo(0, within(1e-12));
        assertThat(quarter.get(1)).isCloseTo(Math.sqrt(0.5), within(1e-12));
        assertThat(quarter.get(2)).isCloseTo(0, within(1e-12));
        assertThat(quarter.get(3)).isCloseTo(Math.sqrt(0.5), within(1e-12));

        double[][] angles = {{-30, 45, 10}, {80, -170, 25}, {0, 180, 0}, {-89, 0, -120}, {12.5, -3, 179}};
        for (double[] a : angles) {
            double[] back = StepRotation.toDegrees(StepRotation.fromDegrees(a[0], a[1], a[2]));
            List<Double> again = StepRotation.fromDegrees(back[0], back[1], back[2]);
            // the angles may be another set for the same rotation (180° around Y), the rotation is the same
            assertThat(again).zipSatisfy(StepRotation.fromDegrees(a[0], a[1], a[2]),
                    (x, y) -> assertThat(x).isCloseTo(y, within(1e-9)));
        }
        double[] back = StepRotation.toDegrees(StepRotation.fromDegrees(-30, 45, 10));
        assertThat(back[0]).isCloseTo(-30, within(1e-9));
        assertThat(back[1]).isCloseTo(45, within(1e-9));
        assertThat(back[2]).isCloseTo(10, within(1e-9));
    }

    @Test
    void noRotationIsNull() {
        assertThat(StepRotation.fromDegrees(0, 0, 0)).isNull();
        assertThat(StepRotation.fromDegrees(0, 360, 0)).isNull();
        assertThat(StepRotation.normalized(List.of(0.0, 0.0, 0.0, -2.0))).isNull();
        assertThat(StepRotation.normalized(List.of(1.0, 2.0))).isNull();
        assertThat(StepRotation.normalized(List.of(0.0, 0.0, 0.0, 0.0))).isNull();
        assertThat(StepRotation.normalized(List.of(Double.NaN, 0.0, 0.0, 1.0))).isNull();
        assertThat(StepRotation.toDegrees(null)).containsExactly(0, 0, 0);
    }

    @Test
    void aStoredRotationIsAUnitQuaternionWithPositiveW() {
        List<Double> q = StepRotation.normalized(List.of(0.0, -2.0, 0.0, -2.0));
        assertThat(q.get(1)).isCloseTo(Math.sqrt(0.5), within(1e-12));
        assertThat(q.get(3)).isCloseTo(Math.sqrt(0.5), within(1e-12));
    }

    @Test
    void aStepKeepsItsRotationAndBlendInItsJson() {
        AnimationStep step = new AnimationStep("c1", true, 1.2, false, true).withRotation(StepRotation.fromDegrees(0, 90, 0))
                .withBlend(0.6);
        List<AnimationStep> read = AnimationStep.listFromJson(AnimationStep.toJson(List.of(step, new AnimationStep("c2", false, 1, false, false))));
        assertThat(read).containsExactly(step, new AnimationStep("c2", false, 1, false, false));
        assertThat(read.getFirst().withBlend(99).blend()).isEqualTo(AnimationStep.MAX_BLEND);
        assertThat(read.getFirst().withBlend(-1).blend()).isZero();
        // steps stored before had no rotation and no blend
        assertThat(AnimationStep.listFromJson("[{\"clip\":\"c\",\"loop\":true,\"speed\":2,\"inPlace\":false,\"reverse\":false}]"))
                .containsExactly(new AnimationStep("c", true, 2, false, false));
    }
}
