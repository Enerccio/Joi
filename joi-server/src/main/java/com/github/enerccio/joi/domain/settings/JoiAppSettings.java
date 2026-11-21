package com.github.enerccio.joi.domain.settings;

/**
 * What the administrator decides for the whole installation (Administration → App settings). Kept in the application's
 * settings ({@code AppSettings}, attributes {@value #KEY}), next to {@code ApiAccess}.
 *
 * @param usersMayUseLocalServers whether users who are not administrators may mark a server of theirs (a TTS model) as
 *                                being on this machine or the local network, so the server calls localhost and private
 *                                addresses for them; administrators always may
 */
public record JoiAppSettings(boolean usersMayUseLocalServers) {

    public static final String KEY = "joi_app";

    /** Off: a user cannot send the server to its own network. */
    public static final JoiAppSettings DEFAULT = new JoiAppSettings(false);
}
