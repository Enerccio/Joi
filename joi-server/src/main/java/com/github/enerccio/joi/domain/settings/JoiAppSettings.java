package com.github.enerccio.joi.domain.settings;

import com.github.enerccio.marginalia.domain.service.agent.WorkspaceLimits;

/**
 * What the administrator decides for the whole installation (Administration → App settings). Kept in the application's
 * settings ({@code AppSettings}, attributes {@value #KEY}), next to {@code ApiAccess}.
 *
 * @param usersMayUseLocalServers whether users who are not administrators may mark a server of theirs (a TTS model) as
 *                                being on this machine or the local network, so the server calls localhost and private
 *                                addresses for them; administrators always may
 * @param workspaceMaxFileBytes   the largest text file in the workspace of an assistant; zero or less: the platform's default
 * @param workspaceMaxTotalBytes  what all the files of one workspace add up to at most; zero or less: the default
 * @param workspaceMaxFiles       the most files in one workspace (folders are free); zero or less: the default
 * @param workspaceMaxImageBytes  the largest image file; zero or less: the default
 * @param workspaceMaxArchiveBytes the largest zip file; zero or less: the default
 */
public record JoiAppSettings(boolean usersMayUseLocalServers, long workspaceMaxFileBytes, long workspaceMaxTotalBytes,
                             int workspaceMaxFiles, long workspaceMaxImageBytes, long workspaceMaxArchiveBytes) {

    public static final String KEY = "joi_app";

    /** Off: a user cannot send the server to its own network; the workspaces have the default limits. */
    public static final JoiAppSettings DEFAULT = new JoiAppSettings(false);

    /** The settings with the default limits of a workspace. */
    public JoiAppSettings(boolean usersMayUseLocalServers) {
        this(usersMayUseLocalServers, 0, 0, 0, 0, 0);
    }

    /** The same settings with other limits of a workspace. */
    public JoiAppSettings withWorkspaceLimits(WorkspaceLimits limits) {
        return new JoiAppSettings(usersMayUseLocalServers, limits.maxFileBytes(), limits.maxTotalBytes(), limits.maxFiles(),
                limits.maxImageBytes(), limits.maxArchiveBytes());
    }

    /** What the workspace of one assistant may hold: what is set, the platform's default for what is not. */
    public WorkspaceLimits workspaceLimits() {
        WorkspaceLimits standard = WorkspaceLimits.DEFAULT;
        return new WorkspaceLimits(workspaceMaxFileBytes > 0 ? workspaceMaxFileBytes : standard.maxFileBytes(),
                workspaceMaxTotalBytes > 0 ? workspaceMaxTotalBytes : standard.maxTotalBytes(),
                workspaceMaxFiles > 0 ? workspaceMaxFiles : standard.maxFiles(),
                workspaceMaxImageBytes > 0 ? workspaceMaxImageBytes : standard.maxImageBytes(),
                workspaceMaxArchiveBytes > 0 ? workspaceMaxArchiveBytes : standard.maxArchiveBytes());
    }
}
