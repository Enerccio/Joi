package com.github.enerccio.joi.protocol.http;

import java.util.List;

/**
 * The animations a body plays.
 *
 * @param all        whether it plays all of the user's animations (also those added later), else those chosen for it
 * @param animations the animations, by name
 * @param joia       the {@code .joia} of them with their clips, null when none was written (or it has none)
 */
public record BodyAnimations(boolean all, List<BodyAnimation> animations, Download joia) {
}
