package com.github.enerccio.joi.protocol.node;

import com.google.gson.annotations.SerializedName;

/** What a node implements ({@link Hello#parts()}). A minimal node has only {@link #TOOLS}, or nothing. */
public enum NodePart {
    /** It offers tools in its manifest and answers tool calls. */
    @SerializedName("tools")
    TOOLS,
    /** It listens (wake word, speech to text) and says what it heard. */
    @SerializedName("audio-in")
    AUDIO_IN,
    /** It plays her speech. */
    @SerializedName("audio-out")
    AUDIO_OUT,
    /** It shows her body. */
    @SerializedName("avatar")
    AVATAR,
    /** It asks the user about calls of the ask tier and reports the answers. */
    @SerializedName("approvals")
    APPROVALS,
    /** It can show her the screen. */
    @SerializedName("screen")
    SCREEN
}
