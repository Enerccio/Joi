package com.github.enerccio.joi.protocol.http;

/**
 * {@code GET /api/v1/admin/update} (since 1.13): the update of the installation, as the watcher outside the server last
 * wrote it (a built image or a checkout; see the server's manual).
 *
 * @param watching        whether a watcher is running (it wrote its status lately)
 * @param running         whether an update is running now
 * @param requested       whether an update was asked for and not taken yet
 * @param updateAvailable whether the watcher saw something newer
 * @param canRequest      whether an update can be asked for now
 * @param runningVersion  the version the server runs
 * @param runningCommit   the commit the server was built from, null when not known
 * @param branch          the branch the watcher follows, null when not known
 * @param upstream        what it compares with, null when not known
 * @param remoteCommit    the newest commit it saw, null when not known
 * @param fetchFailed     whether its last look for something newer failed
 * @param checked         when it last looked (ISO 8601), null for never
 * @param lastRun         when the last update ran (ISO 8601), null for never
 * @param lastRunOk       whether it succeeded, null for none
 */
public record UpdateStatus(boolean watching, boolean running, boolean requested, boolean updateAvailable, boolean canRequest,
                           String runningVersion, String runningCommit, String branch, String upstream, String remoteCommit,
                           boolean fetchFailed, String checked, String lastRun, Boolean lastRunOk) {
}
