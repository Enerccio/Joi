package com.github.enerccio.joi.domain.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolEffectsTest {

    @Test
    void gesturesAndLooksChangeNothing() {
        assertThat(ToolEffects.changesState(AnimationTools.PLAY_ANIMATION)).isFalse();
        assertThat(ToolEffects.changesState(ExpressionTools.SET_EXPRESSION)).isFalse();
        assertThat(ToolEffects.needsNoAnswer(ExpressionTools.SET_EXPRESSION)).isTrue();
        assertThat(ToolEffects.changesState(BodyTools.LIST_OUTFITS)).isFalse();
        assertThat(ToolEffects.changesState(BodyTools.LIST_BODIES)).isFalse();
        assertThat(ToolEffects.changesState(MemoryTools.RECALL)).isFalse();
    }

    @Test
    void everythingElseChangesState() {
        assertThat(ToolEffects.changesState(BodyTools.CHANGE_BODY_PART)).isTrue();
        assertThat(ToolEffects.changesState(BodyTools.SET_OUTFIT)).isTrue();
        assertThat(ToolEffects.changesState(BodyTools.SWITCH_BODY)).isTrue();
        assertThat(ToolEffects.changesState(MemoryTools.REMEMBER)).isTrue();
        // her stance is kept, but its result gives the model nothing to answer
        assertThat(ToolEffects.changesState(AnimationTools.SET_STANCE)).isTrue();
        assertThat(ToolEffects.changesState(AnimationTools.END_STANCE)).isTrue();
        assertThat(ToolEffects.needsNoAnswer(AnimationTools.SET_STANCE)).isTrue();
        assertThat(ToolEffects.needsNoAnswer(AnimationTools.END_STANCE)).isTrue();
        // a node's tool, and one nobody listed
        assertThat(ToolEffects.changesState("peters-macbook-air__notify")).isTrue();
        assertThat(ToolEffects.changesState("something_new")).isTrue();
    }
}
