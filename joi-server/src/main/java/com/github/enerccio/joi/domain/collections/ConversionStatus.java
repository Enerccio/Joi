package com.github.enerccio.joi.domain.collections;

/** Where the conversion of a {@code Model} to VRM is. Stored by name, so the names never change. */
public enum ConversionStatus {

    /** Blender is converting it. */
    RUNNING,
    /** The converted VRM is there. */
    DONE,
    /** The last conversion failed (the error and the log say why); an earlier VRM, if any, stays. */
    FAILED
}
