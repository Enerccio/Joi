package com.github.enerccio.joi.domain.repository.impl;

import com.github.enerccio.joi.domain.model.impl.AccessToken;
import com.github.enerccio.joi.domain.repository.AccessTokenRepository;
import com.github.enerccio.marginalia.domain.repository.impl.JpaExtendableRepository;

import java.util.List;

public class JpaAccessTokenRepository extends JpaExtendableRepository<AccessToken> implements AccessTokenRepository {

    @Override
    protected Class<AccessToken> getEntityClass() {
        return AccessToken.class;
    }

    @Override
    public AccessToken findByPrefix(String prefix) throws Exception {
        return first(entityManager.createQuery("SELECT t FROM AccessToken t WHERE t.keyPrefix = :prefix", AccessToken.class)
                .setParameter("prefix", prefix).setMaxResults(1).getResultList());
    }

    @Override
    public AccessToken findByRefreshPrefix(String prefix) throws Exception {
        return first(entityManager.createQuery("SELECT t FROM AccessToken t WHERE t.refreshPrefix = :prefix", AccessToken.class)
                .setParameter("prefix", prefix).setMaxResults(1).getResultList());
    }

    private static AccessToken first(List<AccessToken> found) {
        return found.isEmpty() ? null : found.getFirst();
    }
}
