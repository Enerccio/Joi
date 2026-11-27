package com.github.enerccio.joi.protocol.http;

/**
 * An extension of the server (since 1.13, {@code /api/v1/admin/extensions}): an OSGi bundle in the extensions folder.
 *
 * @param jar          the name of its JAR file, the id of the extension in the calls
 * @param symbolicName its {@code Bundle-SymbolicName}
 * @param name         its {@code Bundle-Name}, null without one
 * @param version      its version, null without one
 * @param state        the OSGi state: {@code INSTALLED}, {@code RESOLVED}, {@code STARTING}, {@code STOPPING}, {@code ACTIVE}, {@code UNINSTALLED}
 * @param stopped      whether the administrator stopped it (it stays stopped after a restart)
 * @param loaded       whether the server loaded it as an extension
 * @param valid        whether it fits this version of the server, null when it was not checked
 * @param sha256       the SHA-256 of its JAR, null when it could not be read
 */
public record ExtensionInfo(String jar, String symbolicName, String name, String version, String state, boolean stopped,
                            boolean loaded, Boolean valid, String sha256) {
}
