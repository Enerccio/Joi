package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.settings.JoiAppSettings;

/** The administrator's settings of the installation ({@link JoiAppSettings}), in the application's settings. */
public interface JoiAppSettingsService {

    /** What is stored, {@link JoiAppSettings#DEFAULT} when nothing is. */
    JoiAppSettings get() throws Exception;

    /** Stores them; administrators only. */
    void save(JoiAppSettings settings) throws Exception;

    /**
     * Whether the current user may have the server call servers of theirs on its own network: an administrator always,
     * another user when {@link JoiAppSettings#usersMayUseLocalServers()} is on.
     */
    boolean localServersAllowed() throws Exception;
}
