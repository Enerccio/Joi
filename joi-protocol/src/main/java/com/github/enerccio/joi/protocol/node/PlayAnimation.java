package com.github.enerccio.joi.protocol.node;

/**
 * {@code animation} (since 1.5): she chose an animation to play now (her {@code play_animation} function). The node
 * plays it once, on top of what she is doing (waiting, talking), and goes back to that when it is over. A node that
 * does not know the animation (it is not in the body's {@code .joia} it has) ignores the frame.
 *
 * @param animation the animation's uuid (as in {@code BodyAnimation.id} and {@code JoiAnimations})
 * @param name      its name, for a node that shows what she does
 * @param loop      whether it plays until she does something else; false (or null) plays it once
 */
public record PlayAnimation(String animation, String name, Boolean loop) {

    public boolean loops() {
        return Boolean.TRUE.equals(loop);
    }
}
