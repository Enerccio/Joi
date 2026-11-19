package com.github.enerccio.joi.crud;

import com.github.enerccio.joi.domain.collections.AccessTokenKind;
import com.github.enerccio.joi.domain.model.impl.AccessToken;
import com.github.enerccio.joi.domain.service.AccessTokenService;
import com.github.enerccio.marginalia.crud.ExtendableCrudContract;
import com.github.enerccio.marginalia.domain.service.OwnedService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccessTokenCrudTest extends ExtendableCrudContract<AccessToken> {

    @Autowired
    private AccessTokenService accessTokenService;

    @Override
    protected OwnedService<AccessToken, ?> service() {
        return accessTokenService;
    }

    @Override
    protected AccessToken newEntity() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        AccessToken token = new AccessToken();
        token.setName("Kitchen Pi");
        token.setKind(AccessTokenKind.OAUTH);
        token.setKeyPrefix(unique.substring(0, 12));
        token.setSecretHash("a".repeat(64));
        token.setRefreshPrefix(unique.substring(12, 24));
        token.setRefreshHash("b".repeat(64));
        token.setExpires(new Date(5_000L));
        token.setRefreshExpires(new Date(9_000L));
        token.setClientId("kitchen-pi");
        return token;
    }

    @Override
    protected void assertCreated(AccessToken loaded) {
        assertThat(loaded.getName()).isEqualTo("Kitchen Pi");
        assertThat(loaded.getKind()).isEqualTo(AccessTokenKind.OAUTH);
        assertThat(loaded.getExpires()).hasSameTimeAs(new Date(5_000L));
        assertThat(loaded.getRefreshExpires()).hasSameTimeAs(new Date(9_000L));
        assertThat(loaded.getClientId()).isEqualTo("kitchen-pi");
    }

    @Override
    protected void modify(AccessToken entity) {
        entity.setLastUsed(new Date(7_000L));
    }

    @Override
    protected void assertModified(AccessToken loaded) {
        assertThat(loaded.getLastUsed()).hasSameTimeAs(new Date(7_000L));
        assertThat(loaded.getClientId()).isEqualTo("kitchen-pi");
    }
}
