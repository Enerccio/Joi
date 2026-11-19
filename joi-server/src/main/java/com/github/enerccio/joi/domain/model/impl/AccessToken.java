package com.github.enerccio.joi.domain.model.impl;

import com.github.enerccio.joi.domain.collections.AccessTokenKind;
import com.github.enerccio.marginalia.domain.model.ExtendableEntity;
import com.github.enerccio.marginalia.domain.traits.ExtendedAttribute;
import jakarta.persistence.*;

import java.util.Date;

/**
 * A credential of the API (like Marginalia's {@code ApiKey}): a {@link AccessTokenKind#TOKEN token} the user made, or
 * the tokens of an {@link AccessTokenKind#OAUTH OAuth} device. The text of a token is
 * {@code <start><prefix>_<secret>}; it is given out once and only the prefix (to find it) and a SHA-256 hash are
 * stored. An OAuth device has two: the access token (here as for a token) and the refresh token
 * ({@link #getRefreshPrefix()}). A revoked credential is deleted.
 */
@Entity
@Table(name = "access_tokens")
public class AccessToken extends ExtendableEntity {

    /** The name the user gave the token, or the name of the device. */
    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccessTokenKind kind;

    @Column(nullable = false, unique = true, length = 32)
    private String keyPrefix;

    @Column(nullable = false, length = 64)
    private String secretHash;

    /** When the (access) token stops working, null for never. */
    @Column
    private Date expires;

    /** The prefix of the refresh token of an OAuth device, null for a token. */
    @Column(unique = true, length = 32)
    private String refreshPrefix;

    @Column(length = 64)
    private String refreshHash;

    /** When the refresh token stops working: the device has to be approved again. */
    @ExtendedAttribute
    @Transient
    private Date refreshExpires;

    /** The {@code client_id} the device sent. */
    @ExtendedAttribute
    @Transient
    private String clientId;

    /** When it was last used, written at most once a minute. */
    @ExtendedAttribute
    @Transient
    private Date lastUsed;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public AccessTokenKind getKind() {
        return kind;
    }

    public void setKind(AccessTokenKind kind) {
        this.kind = kind;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public String getSecretHash() {
        return secretHash;
    }

    public void setSecretHash(String secretHash) {
        this.secretHash = secretHash;
    }

    public Date getExpires() {
        return expires;
    }

    public void setExpires(Date expires) {
        this.expires = expires;
    }

    public String getRefreshPrefix() {
        return refreshPrefix;
    }

    public void setRefreshPrefix(String refreshPrefix) {
        this.refreshPrefix = refreshPrefix;
    }

    public String getRefreshHash() {
        return refreshHash;
    }

    public void setRefreshHash(String refreshHash) {
        this.refreshHash = refreshHash;
    }

    public Date getRefreshExpires() {
        return refreshExpires;
    }

    public void setRefreshExpires(Date refreshExpires) {
        this.refreshExpires = refreshExpires;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public Date getLastUsed() {
        return lastUsed;
    }

    public void setLastUsed(Date lastUsed) {
        this.lastUsed = lastUsed;
    }

    public boolean isExpired(long now) {
        return expires != null && expires.getTime() <= now;
    }
}
