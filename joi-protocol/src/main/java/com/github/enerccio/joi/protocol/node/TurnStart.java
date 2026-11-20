package com.github.enerccio.joi.protocol.node;

/**
 * {@code turn}: the server starts a turn the node did not ask for, with this id; her answer follows in frames that refer
 * to it, as for a {@code say}. Turns of one assistant still run one at a time.
 *
 * @param turn  the id of the turn
 * @param cause why: {@code first_boot} (she wakes up for the very first time: play her waking up before her words),
 *              {@code connected} (the user is back: she greets them); a cause the node does not know is played as a
 *              plain turn
 */
public record TurnStart(String turn, String cause) {
}
