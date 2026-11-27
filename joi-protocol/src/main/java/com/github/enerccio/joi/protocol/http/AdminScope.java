package com.github.enerccio.joi.protocol.http;

import com.google.gson.annotations.SerializedName;

/**
 * A part of the administration a token may use through the API (since 1.13, {@link Me#scopes()}). Only an administrator
 * gives them to a token, and they work only while the owner of the token is one. An OAuth device or the local node never
 * has them.
 */
public enum AdminScope {

    /** The update of the installation: its status and log, and asking for an update ({@code /api/v1/admin/update}). */
    @SerializedName("update")
    UPDATE,

    /** The extensions: list, install or replace, stop, start, remove, read their logs ({@code /api/v1/admin/extensions}). */
    @SerializedName("extensions")
    EXTENSIONS
}
