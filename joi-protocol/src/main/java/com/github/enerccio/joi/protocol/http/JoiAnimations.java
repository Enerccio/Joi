package com.github.enerccio.joi.protocol.http;

import java.util.List;

/**
 * The animations she plays in her states (since 1.2), chosen in her dialog: ids of the user's animations, played from
 * the {@code .joia} of her body ({@link BodyAnimations}); an id the body does not have is skipped. Null or empty for
 * none chosen: the node plays her idle rest pose.
 *
 * @param sleeping     while she sleeps (loops), null for none
 * @param fallingAsleep as she goes to sleep, null for none
 * @param wakingUp     as she wakes up, null for none
 * @param idle         while she waits; one at a time, chosen by the node
 * @param talking      while she speaks; one at a time, chosen by the node
 */
public record JoiAnimations(String sleeping, String fallingAsleep, String wakingUp, List<String> idle, List<String> talking) {
}
