package com.github.enerccio.joi.api;

import org.apache.commons.lang3.StringUtils;

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

    /** The URL the server is reached at from outside ({@code https://joi.example.com}), null to take it from the call. */
    public String getPublicUrl() {
        return publicUrl;
    }

    public void setPublicUrl(String publicUrl) {
        String url = StringUtils.defaultIfBlank(StringUtils.strip(publicUrl), null);
        this.publicUrl = url == null ? null : StringUtils.removeEnd(url, "/");
    }
}
