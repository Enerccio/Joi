package com.github.enerccio.joi.protocol.node;

import com.google.gson.annotations.SerializedName;

/** The harness tier of a node's tool. The node enforces it again on every call; the server only shows it. */
public enum ToolTier {
    /** Runs silently. */
    @SerializedName("allow")
    ALLOW,
    /** The user approves each call on the node ({@link Approval}). */
    @SerializedName("ask")
    ASK,
    /** Never runs (offered so that she knows it exists and can ask the user to allow it). */
    @SerializedName("deny")
    DENY,
    /** Depends on the arguments: the node decides per call. */
    @SerializedName("depends")
    DEPENDS
}
