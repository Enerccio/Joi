package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.api.ApiAccess;
import com.github.enerccio.joi.domain.collections.AccessTokenKind;
import com.github.enerccio.joi.domain.model.impl.AccessToken;
import com.github.enerccio.joi.domain.repository.AccessTokenRepository;
import com.github.enerccio.joi.domain.service.AccessTokenService;
import com.github.enerccio.joi.domain.service.ApiAccessService;
import com.github.enerccio.joi.protocol.http.AdminScope;
import com.github.enerccio.joi.protocol.http.AuthMethod;
import com.github.enerccio.marginalia.domain.security.AdminGuard;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.domain.service.impl.ExtendableServiceImpl;
import com.github.enerccio.marginalia.domain.traits.CommonTx;
import com.github.enerccio.marginalia.utils.AppClock;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.Date;
import java.util.HexFormat;

/** {@link AccessTokenService}, after Marginalia's {@code ApiKeyServiceImpl}. */
public class AccessTokenServiceImpl extends ExtendableServiceImpl<AccessToken, AccessTokenRepository> implements AccessTokenService {

    static final int MAX_NAME_LENGTH = 100;
    static final int PREFIX_LENGTH = 12;
    private static final int SECRET_BYTES = 32;
    private static final long LAST_USED_RESOLUTION_MS = 60_000L;
    private static final String PREFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private ApiAccessService apiAccessService;

    @Autowired
    private AdminGuard adminGuard;

    @Override
    @CommonTx
    public Created create(String name, Date expires) throws Exception {
        return create(name, expires, Set.of());
    }

    @Override
    @CommonTx
    public Created create(String name, Date expires, Collection<AdminScope> scopes) throws Exception {
        if (!apiAccessService.get().tokens()) {
            throw new IllegalStateException("Tokens are turned off");
        }
        if (scopes != null && !scopes.isEmpty()) {
            adminGuard.requireAdmin();
        }
        String trimmed = checkedName(name);
        if (expires != null && expires.getTime() <= AppClock.now().getTime()) {
            throw new IllegalArgumentException("The token would be expired already");
        }
        checkCount();
        AccessToken token = new AccessToken();
        token.setKind(AccessTokenKind.TOKEN);
        token.setName(trimmed);
        String prefix = newPrefix(false);
        String text = TOKEN_START + prefix + "_" + secret();
        token.setKeyPrefix(prefix);
        token.setSecretHash(hash(text));
        token.setExpires(expires);
        token.setScopes(scopes);
        return new Created(save(token), text);
    }

    @Override
    @CommonTx
    public Issued issueDevice(String name, String clientId) throws Exception {
        ApiAccess access = apiAccessService.get();
        if (!access.oauth()) {
            throw new IllegalStateException("OAuth is turned off");
        }
        checkCount();
        AccessToken token = new AccessToken();
        token.setKind(AccessTokenKind.OAUTH);
        token.setName(StringUtils.abbreviate(StringUtils.defaultIfBlank(StringUtils.strip(name), "Device"), MAX_NAME_LENGTH));
        token.setClientId(StringUtils.abbreviate(clientId, MAX_NAME_LENGTH));
        return rotate(token, access);
    }

    @Override
    @CommonTx
    public Issued issueLocal(String name, String clientId) throws Exception {
        String client = StringUtils.abbreviate(StringUtils.defaultIfBlank(StringUtils.strip(clientId), "joi-node"), MAX_NAME_LENGTH);
        AccessToken token = findAllForUser().stream()
                .filter(t -> !t.isDeleted() && t.getKind() == AccessTokenKind.LOCAL && client.equals(t.getClientId()))
                .findFirst().orElse(null);
        if (token == null) {
            checkCount();
            token = new AccessToken();
            token.setKind(AccessTokenKind.LOCAL);
            token.setClientId(client);
        }
        token.setName(StringUtils.abbreviate(StringUtils.defaultIfBlank(StringUtils.strip(name), client), MAX_NAME_LENGTH));
        long now = AppClock.now().getTime();
        String prefix = newPrefix(false);
        String accessText = LOCAL_START + prefix + "_" + secret();
        long expiresIn = apiAccessService.get().accessTokenMinutes() * 60L;
        token.setKeyPrefix(prefix);
        token.setSecretHash(hash(accessText));
        token.setExpires(new Date(now + expiresIn * 1000));
        token.setRefreshPrefix(null);
        token.setRefreshHash(null);
        token.setRefreshExpires(null);
        token.setLastUsed(new Date(now));
        return new Issued(save(token), accessText, null, expiresIn);
    }

    @Override
    @CommonTx
    public Issued refresh(String refreshToken) throws Exception {
        ApiAccess access = apiAccessService.get();
        String prefix = prefixOf(refreshToken, REFRESH_START);
        if (!access.oauth() || prefix == null) {
            return null;
        }
        AccessToken token = getRepository().findByRefreshPrefix(prefix);
        if (token == null || token.isDeleted() || token.getRefreshHash() == null || !matches(refreshToken, token.getRefreshHash())) {
            return null;
        }
        long now = AppClock.now().getTime();
        if (token.getRefreshExpires() != null && token.getRefreshExpires().getTime() <= now) {
            return null;
        }
        if (owner(token) == null) {
            return null;
        }
        return rotate(token, access);
    }

    /** New access and refresh tokens for the credential (the old ones stop working), stored. */
    private Issued rotate(AccessToken token, ApiAccess access) throws Exception {
        long now = AppClock.now().getTime();
        String prefix = newPrefix(false);
        String accessText = ACCESS_START + prefix + "_" + secret();
        String refreshPrefix = newPrefix(true);
        String refreshText = REFRESH_START + refreshPrefix + "_" + secret();
        long expiresIn = access.accessTokenMinutes() * 60L;
        token.setKeyPrefix(prefix);
        token.setSecretHash(hash(accessText));
        token.setExpires(new Date(now + expiresIn * 1000));
        token.setRefreshPrefix(refreshPrefix);
        token.setRefreshHash(hash(refreshText));
        token.setRefreshExpires(new Date(now + access.refreshTokenDays() * 24L * 3600 * 1000));
        token.setLastUsed(new Date(now));
        return new Issued(save(token), accessText, refreshText, expiresIn);
    }

    @Override
    @CommonTx
    public Principal authenticate(String text) throws Exception {
        AccessTokenKind kind = text == null ? null : text.startsWith(ACCESS_START) ? AccessTokenKind.OAUTH
                : text.startsWith(TOKEN_START) ? AccessTokenKind.TOKEN
                : text.startsWith(LOCAL_START) ? AccessTokenKind.LOCAL : null;
        if (kind == null) {
            return null;
        }
        ApiAccess access = apiAccessService.get();
        // a local token is the machine's own: the administrator's offer to other clients does not turn it off
        if (kind == AccessTokenKind.TOKEN ? !access.tokens() : kind == AccessTokenKind.OAUTH && !access.oauth()) {
            return null;
        }
        String start = kind == AccessTokenKind.TOKEN ? TOKEN_START : kind == AccessTokenKind.OAUTH ? ACCESS_START : LOCAL_START;
        String prefix = prefixOf(text, start);
        if (prefix == null) {
            return null;
        }
        AccessToken token = getRepository().findByPrefix(prefix);
        if (token == null || token.isDeleted() || token.getKind() != kind || !matches(text, token.getSecretHash())) {
            return null;
        }
        long now = AppClock.now().getTime();
        if (token.isExpired(now)) {
            return null;
        }
        User owner = owner(token);
        if (owner == null) {
            return null;
        }
        if (token.getLastUsed() == null || now - token.getLastUsed().getTime() >= LAST_USED_RESOLUTION_MS) {
            token.setLastUsed(new Date(now));
            token = saveWithoutModification(token);
        }
        // the administration only for a token, and only while its owner is an administrator
        Set<AdminScope> scopes = kind == AccessTokenKind.TOKEN && owner.isAdmin() ? token.getScopes() : EnumSet.noneOf(AdminScope.class);
        return new Principal(owner, token, kind == AccessTokenKind.TOKEN ? AuthMethod.TOKEN : AuthMethod.OAUTH, Set.copyOf(scopes));
    }

    @Override
    @CommonTx
    public boolean revoke(String uuid) throws Exception {
        AccessToken token = findForUser(uuid);
        if (token == null || token.isDeleted()) {
            return false;
        }
        delete(token, false);
        return true;
    }

    private User owner(AccessToken token) throws Exception {
        if (token.getOwner() == null) {
            return null;
        }
        User owner = userService.find(token.getOwner().getId());
        return owner == null || owner.isDeleted() ? null : owner;
    }

    private void checkCount() throws Exception {
        if (findAllForUser().size() >= MAX_PER_USER) {
            throw new IllegalArgumentException("A user can have at most " + MAX_PER_USER + " tokens and devices");
        }
    }

    private static String checkedName(String name) {
        String trimmed = StringUtils.trimToEmpty(name);
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("The name must have 1 to " + MAX_NAME_LENGTH + " characters");
        }
        return trimmed;
    }

    private String newPrefix(boolean refresh) throws Exception {
        while (true) {
            String prefix = randomPrefix();
            if ((refresh ? getRepository().findByRefreshPrefix(prefix) : getRepository().findByPrefix(prefix)) == null) {
                return prefix;
            }
        }
    }

    /**
     * The prefix of a text of the form {@code <start><prefix>_<secret>}, null when it does not have that form.
     */
    static String prefixOf(String text, String start) {
        if (text == null || !text.startsWith(start) || text.length() > 200) {
            return null;
        }
        String rest = text.substring(start.length());
        int separator = rest.indexOf('_');
        if (separator != PREFIX_LENGTH || rest.length() == separator + 1) {
            return null;
        }
        String prefix = rest.substring(0, separator);
        for (int i = 0; i < prefix.length(); i++) {
            if (PREFIX_ALPHABET.indexOf(prefix.charAt(i)) < 0) {
                return null;
            }
        }
        return prefix;
    }

    private static String randomPrefix() {
        StringBuilder sb = new StringBuilder(PREFIX_LENGTH);
        for (int i = 0; i < PREFIX_LENGTH; i++) {
            sb.append(PREFIX_ALPHABET.charAt(RANDOM.nextInt(PREFIX_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String secret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    private static boolean matches(String text, String storedHash) {
        return MessageDigest.isEqual(hash(text).getBytes(StandardCharsets.US_ASCII), storedHash.getBytes(StandardCharsets.US_ASCII));
    }

    /** SHA-256 of the whole text as lowercase hex: 256 random bits, a slow password hash would add nothing. */
    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
