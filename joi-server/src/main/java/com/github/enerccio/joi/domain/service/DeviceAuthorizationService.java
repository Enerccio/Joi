package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.protocol.oauth.DeviceAuthorization;

/**
 * The OAuth device authorization grant (RFC 8628). A device asks for codes ({@link #start}), shows the user code, and
 * polls ({@link #poll}); the user approves the code in the UI ({@link #approve}), which issues the device's tokens
 * ({@link AccessTokenService#issueDevice}). The codes live in memory for {@value #EXPIRES_SECONDS} seconds; a restart
 * forgets them (the device starts again).
 */
public interface DeviceAuthorizationService {

    long EXPIRES_SECONDS = 600;
    long INTERVAL_SECONDS = 5;

    /** A device waiting for the user, as the approval shows it. */
    record Request(String userCode, String clientId, String clientName) {
    }

    /** What a poll answers: the tokens, or the OAuth error ({@code authorization_pending}...). */
    record Poll(AccessTokenService.Issued issued, String error) {
    }

    /**
     * New codes for a device.
     *
     * @param baseUrl the URL of the server as the device reaches it, for the verification page
     * @param offer   the pairing offer the device scanned ({@link #createOffer}), null for none: the user's screen that showed it
     *                finds the device ({@link #waitingForOffer}) and approves it without a code being typed
     * @throws IllegalStateException    when the administrator turned OAuth off
     * @throws IllegalArgumentException when the offer is not (or no longer) valid
     */
    DeviceAuthorization start(String clientId, String clientName, String baseUrl, String offer) throws Exception;

    /** The same without an offer. */
    default DeviceAuthorization start(String clientId, String clientName, String baseUrl) throws Exception {
        return start(clientId, clientName, baseUrl, null);
    }

    /**
     * A pairing offer: a secret the user's screen shows as a QR code ({@code joi://pair?...}). A device that scans it gives it with its
     * request for codes. It lives {@value #EXPIRES_SECONDS} seconds and takes one device at a time that waits.
     */
    String createOffer();

    /** The device that waits with the offer, null when none (yet). */
    Request waitingForOffer(String offer);

    /** The screen that showed the offer is closed: nothing can use it any more. */
    void forgetOffer(String offer);

    /** The waiting device of the user code (any case, with or without the dash), null when none. */
    Request find(String userCode);

    /** The current user approves the device: its tokens are issued and given to it at its next poll. */
    void approve(String userCode) throws Exception;

    /** The current user denies the device. */
    void deny(String userCode);

    /** What the device gets for its device code. */
    Poll poll(String deviceCode);
}
