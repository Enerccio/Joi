package com.github.enerccio.joi.node.renderer;

/**
 * How the renderer page renders her for the Looking Glass, set in the node's tuning panel and kept in
 * {@code node.json}; the fields are those of {@code looking-glass.js} (see there for what each does).
 */
public final class LookingGlassTuning {

    /** The views of the Go's quilt: 66, one width to choose. */
    public static final int[] QUILTS = {2048, 3072, 4092};
    /** What the page shows: her hologram, or for checking, the quilt as it is or the middle view. */
    public static final String[] VIEWS = {"hologram", "quilt", "center"};
    /** How many views are rendered in full (0: all 66; the others are made from them by their depth). */
    public static final int[] RENDERED = {0, 9, 5, 3, 2, 1};

    // tuned by eye on the Go with the test avatar
    public double depthiness = 0.27;
    public double focus = -0.35;
    public double zoom = 0.97;
    public double center = -0.003;
    public double pitch = 1;
    public boolean invert;
    public boolean flipY;
    public int quilt = 4092;
    public int fps = 20;
    public int rendered = 5;
    public String view = "hologram";

    public LookingGlassTuning copy() {
        LookingGlassTuning copy = new LookingGlassTuning();
        copy.depthiness = depthiness;
        copy.focus = focus;
        copy.zoom = zoom;
        copy.center = center;
        copy.pitch = pitch;
        copy.invert = invert;
        copy.flipY = flipY;
        copy.quilt = quilt;
        copy.fps = fps;
        copy.rendered = rendered;
        copy.view = view;
        return copy;
    }
}
