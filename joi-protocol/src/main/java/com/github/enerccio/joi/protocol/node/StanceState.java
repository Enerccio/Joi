package com.github.enerccio.joi.protocol.node;

/**
 * {@code stance} (since 1.17): the animation she holds now, her stance (her {@code set_stance} and {@code end_stance}
 * functions, or the user ending it in her dialog). While she has one, the node plays it looped in place of her idle and
 * talking animations, through her turns, until it changes; her mouth, her mood's face and her expressions go on over it,
 * and a gesture ({@code animation}) plays once and returns to it. Asleep, falling asleep and waking up win over it.
 * The server sends it after the node's manifest and each time it changes. A null {@code animation} is no stance: back
 * to idle and talking. A node that does not have the animation in the body's {@code .joia} plays as with none.
 *
 * @param animation the animation's uuid (as in {@code BodyAnimation.id}), null for no stance
 * @param name      its name, for a node that shows it
 */
public record StanceState(String animation, String name) {
}
