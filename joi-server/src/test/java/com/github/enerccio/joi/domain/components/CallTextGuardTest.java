package com.github.enerccio.joi.domain.components;

import com.github.enerccio.joi.domain.components.CallTextGuard.Verdict;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** What the first words of a round say: a call written as text, or speech. */
class CallTextGuardTest {

    private static final Predicate<String> KNOWN = Set.of("play_animation", "wave", "recall")::contains;

    private static Verdict check(String held) {
        return CallTextGuard.check(held, KNOWN, false);
    }

    @Test
    void aCallOfAKnownOrUnderscoredNameIsFoundAsSoonAsTheBracketComes() {
        assertThat(check("play_animation(")).isEqualTo(Verdict.LEAK);
        assertThat(check("play_animation(animation=\"Bashful\")\nOh!")).isEqualTo(Verdict.LEAK);
        assertThat(check("  `play_animation(animation=\"x\")`")).isEqualTo(Verdict.LEAK);
        assertThat(check("default_api.play_animation(")).isEqualTo(Verdict.LEAK);
        assertThat(check("do_something_unknown(")).isEqualTo(Verdict.LEAK);
        assertThat(check("wave(")).isEqualTo(Verdict.LEAK);
    }

    @Test
    void anUnknownPlainNameIsACallOnlyWithArgumentsWrittenAsOne() {
        assertThat(check("smile()")).isEqualTo(Verdict.LEAK);
        assertThat(check("smile(speed=2)")).isEqualTo(Verdict.LEAK);
        assertThat(check("smile(laughs)")).isEqualTo(Verdict.PASS);
        assertThat(check("Hmm(laughs softly) well")).isEqualTo(Verdict.PASS);
    }

    @Test
    void wordsPassAndTheStartOfAWordWaits() {
        assertThat(check("Hello there")).isEqualTo(Verdict.PASS);
        assertThat(check("Hmm (laughs)")).isEqualTo(Verdict.PASS);
        assertThat(check("\"Oh, that is fine\"")).isEqualTo(Verdict.PASS);
        assertThat(check("Čau, co děláš")).isEqualTo(Verdict.PASS);
        assertThat(check("")).isEqualTo(Verdict.WAIT);
        assertThat(check("  ")).isEqualTo(Verdict.WAIT);
        assertThat(check("play_ani")).isEqualTo(Verdict.WAIT);
        assertThat(check("smile(")).isEqualTo(Verdict.WAIT);
        assertThat(check("smile(spe")).isEqualTo(Verdict.WAIT);
    }

    @Test
    void whenTheRoundEndedNothingWaits() {
        assertThat(CallTextGuard.check("Hello", KNOWN, true)).isEqualTo(Verdict.PASS);
        assertThat(CallTextGuard.check("", KNOWN, true)).isEqualTo(Verdict.PASS);
        assertThat(CallTextGuard.check("play_animation(animation=\"x\")", KNOWN, true)).isEqualTo(Verdict.LEAK);
    }

    @Test
    void theSettingIsKeptInTheAttributesWithTheRest() {
        AI ai = new AI();
        assertThat(CallTextGuard.enabled(ai)).isFalse();
        TextModelTypes.set(ai, Set.of(com.github.enerccio.joi.domain.collections.TextLLMType.CHAT));
        CallTextGuard.set(ai, true);
        assertThat(CallTextGuard.enabled(ai)).isTrue();
        assertThat(TextModelTypes.get(ai)).hasSize(1);
        CallTextGuard.set(ai, false);
        assertThat(CallTextGuard.enabled(ai)).isFalse();
        assertThat(TextModelTypes.get(ai)).hasSize(1);
    }
}
