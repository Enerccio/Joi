package com.github.enerccio.joi.api;

import org.apache.commons.lang3.StringUtils;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * What the server's configuration says about the API (bean {@code apiConfiguration}, from {@code joi.api.*}: a -D system
 * property or an environment variable, {@code JOI_API_NO_AUTH}). The administrator's choices are {@code ApiAccess}.
 * <p>
 * {@link #isNoAuth() No auth} is for development and localhost setups: a call without credentials then acts as
 * {@link #getNoAuthUser()} (the first administrator when not set). It is on in {@code mvn jetty:run}, off otherwise.
 */
public class ApiConfiguration {

    private boolean noAuth;
    private String noAuthUser;
    private String publicUrl;
    private Path localSocket;

    public boolean isNoAuth() {
        return noAuth;
    }

    public void setNoAuth(boolean noAuth) {
        this.noAuth = noAuth;
    }

    /** The login a call without credentials acts as, null for the first administrator. */
    public String getNoAuthUser() {
        return noAuthUser;
    }

    public void setNoAuthUser(String noAuthUser) {
        this.noAuthUser = StringUtils.defaultIfBlank(StringUtils.strip(noAuthUser), null);
    }

    /**
     * The Unix domain socket the server also listens on (the desktop app opens it in the data folder), null for none. A call that
     * arrives on it ({@link #isLocalSocket}) is from the server's own machine: it may fetch a token with no key and no login
     * ({@code POST /oauth/local}). Jetty serves the whole web application on it, the web UI too, but only the user who may open the
     * socket's folder reaches it, and no web page can.
     */
    public Path getLocalSocket() {
        return localSocket;
    }

    public void setLocalSocket(String localSocket) {
        String path = StringUtils.defaultIfBlank(StringUtils.strip(localSocket), null);
        this.localSocket = path == null ? null : Path.of(path).toAbsolutePath().normalize();
    }

    /**
     * Whether a call arrived on the local socket: Jetty gives such a call no ports and the path of the socket as its local address
     * (a TCP call always has both), so it cannot be made up by a client that connected any other way.
     */
    public boolean isLocalSocket(String localAddress, int localPort) {
        if (localSocket == null || localPort != -1 || StringUtils.isBlank(localAddress)) {
            return false;
        }
        try {
            return localSocket.equals(Path.of(localAddress).toAbsolutePath().normalize());
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /** The URL the server is reached at from outside ({@code https://joi.example.com}), null to take it from the call. */
    public String getPublicUrl() {
        return publicUrl;
    }

    public void setPublicUrl(String publicUrl) {
        String url = StringUtils.defaultIfBlank(StringUtils.strip(publicUrl), null);
        this.publicUrl = url == null ? null : StringUtils.removeEnd(url, "/");
    }
}
