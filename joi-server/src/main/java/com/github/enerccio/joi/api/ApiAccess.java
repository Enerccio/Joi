package com.github.enerccio.joi.api;

/**
 * What the administrator offers to the clients of the API (Administration → API access). Kept in the application's
 * settings ({@code AppSettings}, attributes {@value #KEY}). Turning a method off stops its credentials working at once;
 * they are not deleted, so turning it on again brings them back.
 *
 * @param tokens             tokens the users create in their settings
 * @param oauth              the OAuth device grant
 * @param accessTokenMinutes how long an OAuth access token works
 * @param refreshTokenDays   how long an OAuth refresh token works (then the device is approved again)
 */
public record ApiAccess(boolean tokens, boolean oauth, int accessTokenMinutes, int refreshTokenDays) {

    public static final String KEY = "joi_api";

    public static final ApiAccess DEFAULT = new ApiAccess(true, true, 60, 90);

    public ApiAccess {
        accessTokenMinutes = Math.clamp(accessTokenMinutes, 5, 7 * 24 * 60);
        refreshTokenDays = Math.clamp(refreshTokenDays, 1, 3650);
    }
}
