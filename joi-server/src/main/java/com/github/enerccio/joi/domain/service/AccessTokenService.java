package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.AccessToken;
import com.github.enerccio.joi.domain.repository.AccessTokenRepository;
import com.github.enerccio.joi.protocol.http.AuthMethod;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.domain.service.ExtendableService;

import java.util.Date;

/**
 * The credentials of the API ({@link AccessToken}): the tokens a user makes ({@value #TOKEN_START}...) and the tokens of
 * the OAuth devices they approved ({@value #ACCESS_START}... and {@value #REFRESH_START}...). The text of a token is
 * given out once; only its prefix and a SHA-256 hash are stored. A kind the administrator turned off
 * ({@link ApiAccessService}) does not authenticate. {@link #findAllForUser()} lists those of the current user.
 */
public interface AccessTokenService extends ExtendableService<AccessToken, AccessTokenRepository> {

    String TOKEN_START = "joi_";
    String ACCESS_START = "joia_";
    String REFRESH_START = "joir_";
    /** The access token of the node on the server's own machine ({@link AccessTokenKind#LOCAL}). */
    String LOCAL_START = "joil_";

    /** How many credentials a user can have at once. */
    int MAX_PER_USER = 50;

    /** A new token and its text, stored nowhere: show it once. */
    record Created(AccessToken token, String text) {
    }

    /** The tokens of an OAuth device. */
    record Issued(AccessToken token, String accessToken, String refreshToken, long expiresInSeconds) {
    }

    /**
     * Who a call acts for.
     *
     * @param token  the credential, null for {@link AuthMethod#NONE}
     * @param method how it was authenticated
     */
    record Principal(User user, AccessToken token, AuthMethod method) {
    }

    /**
     * A token of the current user.
     *
     * @param expires when it stops working, null for never
     * @throws IllegalStateException    when the administrator turned tokens off
     * @throws IllegalArgumentException for a blank or too long name, an expiry in the past, or too many credentials
     */
    Created create(String name, Date expires) throws Exception;

    /**
     * The tokens of a device the current user approved.
     *
     * @throws IllegalStateException when the administrator turned OAuth off
     */
    Issued issueDevice(String name, String clientId) throws Exception;

    /**
     * The access token of the node on the server's own machine, for the current user, who is known by the local socket and not by a
     * login. The same {@code clientId} gets a new token in place of the old one (which stops working), so a node has one entry in the
     * user's list; it has no refresh token. Works whatever the administrator offers to other clients.
     */
    Issued issueLocal(String name, String clientId) throws Exception;

    /**
     * A new pair for a refresh token; the old pair stops working. Needs no current user.
     *
     * @return null when the refresh token is not valid (unknown, revoked, expired, OAuth turned off)
     */
    Issued refresh(String refreshToken) throws Exception;

    /**
     * Who the text of a token or an access token is, null when it is not valid. Needs no current user. Marks it used
     * (at most once a minute).
     */
    Principal authenticate(String text) throws Exception;

    /** Revokes (deletes) a credential of the current user; whether there was one. */
    boolean revoke(String uuid) throws Exception;
}
