package com.github.enerccio.joi.domain.bodies;

import java.io.File;
import java.io.IOException;

/** A small VRM 1.0 with hair, a tail and a scarf to swing (see {@link SpringBonesTest}), for tests elsewhere. */
public final class SpringBonesTestFiles {

    private SpringBonesTestFiles() {
    }

    public static File vrm(File file) throws IOException {
        return SpringBonesTest.glb(file, SpringBonesTest.vrm1Json(), new byte[]{1, 2, 3, 4});
    }
}
