package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.service.AccessTokenService;
import com.github.enerccio.joi.domain.service.ApiAccessService;
import com.github.enerccio.joi.domain.service.DeviceAuthorizationService;
import com.github.enerccio.joi.protocol.oauth.DeviceAuthorization;
import com.github.enerccio.marginalia.utils.AppClock;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** {@link DeviceAuthorizationService} in memory. */
public class DeviceAuthorizationServiceImpl implements DeviceAuthorizationService {

    /** No vowels (no words), no look-alikes (RFC 8628, 6.1). */
    static final String USER_CODE_ALPHABET = "BCDFGHJKLMNPQRSTVWXZ";
    static final int MAX_WAITING = 200;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private ApiAccessService apiAccessService;

    private static final class Pending {
        final String deviceCode;
        final String userCode;
        final String clientId;
        final String clientName;
        final long expires;
        volatile long lastPoll;
        volatile AccessTokenService.Issued issued;
        volatile boolean denied;

        Pending(String deviceCode, String userCode, String clientId, String clientName, long expires) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.clientId = clientId;
            this.clientName = clientName;
            this.expires = expires;
        }
    }

    private final Map<String, Pending> byDeviceCode = new ConcurrentHashMap<>();
    private final Map<String, Pending> byUserCode = new ConcurrentHashMap<>();

    @Override
    public DeviceAuthorization start(String clientId, String clientName, String baseUrl) throws Exception {
        if (!apiAccessService.get().oauth()) {
            throw new IllegalStateException("OAuth is turned off");
        }
        if (StringUtils.isBlank(clientId)) {
            throw new IllegalArgumentException("client_id is missing");
        }
        forgetExpired();
        if (byDeviceCode.size() >= MAX_WAITING) {
            throw new IllegalStateException("Too many devices are waiting");
        }
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String deviceCode = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String userCode;
        do {
            userCode = userCode();
        } while (byUserCode.containsKey(userCode));
        String id = StringUtils.abbreviate(clientId.strip(), 100);
        String name = StringUtils.abbreviate(StringUtils.defaultIfBlank(StringUtils.strip(clientName), id), 100);
        Pending pending = new Pending(deviceCode, userCode, id, name, now() + EXPIRES_SECONDS * 1000);
        byDeviceCode.put(deviceCode, pending);
        byUserCode.put(userCode, pending);
        String page = baseUrl + "/?device";
        return new DeviceAuthorization(deviceCode, display(userCode), page,
                page + "=" + URLEncoder.encode(display(userCode), StandardCharsets.UTF_8), EXPIRES_SECONDS, INTERVAL_SECONDS);
    }

    @Override
    public Request find(String userCode) {
        Pending pending = waiting(userCode);
        return pending == null ? null : new Request(display(pending.userCode), pending.clientId, pending.clientName);
    }

    @Override
    public void approve(String userCode) throws Exception {
        Pending pending = waiting(userCode);
        if (pending == null) {
            throw new IllegalArgumentException("No device waits for this code");
        }
        pending.issued = accessTokenService.issueDevice(pending.clientName, pending.clientId);
    }

    @Override
    public void deny(String userCode) {
        Pending pending = waiting(userCode);
        if (pending != null) {
            pending.denied = true;
        }
    }

    @Override
    public Poll poll(String deviceCode) {
        Pending pending = deviceCode == null ? null : byDeviceCode.get(deviceCode);
        if (pending == null) {
            return new Poll(null, "invalid_grant");
        }
        long now = now();
        if (pending.expires <= now) {
            forget(pending);
            return new Poll(null, "expired_token");
        }
        if (pending.denied) {
            forget(pending);
            return new Poll(null, "access_denied");
        }
        if (pending.issued != null) {
            forget(pending);
            return new Poll(pending.issued, null);
        }
        long last = pending.lastPoll;
        pending.lastPoll = now;
        // a little slack: a device that polls on the dot of the interval is not too fast
        return new Poll(null, last != 0 && now - last < INTERVAL_SECONDS * 1000 - 500 ? "slow_down" : "authorization_pending");
    }

    private Pending waiting(String userCode) {
        Pending pending = userCode == null ? null : byUserCode.get(normalized(userCode));
        if (pending == null || pending.expires <= now() || pending.denied || pending.issued != null) {
            return null;
        }
        return pending;
    }

    private void forget(Pending pending) {
        byDeviceCode.remove(pending.deviceCode);
        byUserCode.remove(pending.userCode);
    }

    private void forgetExpired() {
        long now = now();
        byDeviceCode.values().stream().filter(p -> p.expires <= now).toList().forEach(this::forget);
    }

    private static String userCode() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(USER_CODE_ALPHABET.charAt(RANDOM.nextInt(USER_CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** The code as typed: upper case, without the dash and spaces. */
    static String normalized(String userCode) {
        return userCode.replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT);
    }

    static String display(String code) {
        return code.length() == 8 ? code.substring(0, 4) + "-" + code.substring(4) : code;
    }

    private static long now() {
        return AppClock.now().getTime();
    }
}
