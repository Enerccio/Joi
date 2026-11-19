package com.github.enerccio.joi.protocol.node;

import com.google.gson.annotations.SerializedName;

/** Where an {@link Approval} is. */
public enum ApprovalState {
    /** The node waits for the user. */
    @SerializedName("asked")
    ASKED,
    @SerializedName("allowed")
    ALLOWED,
    @SerializedName("denied")
    DENIED
}
