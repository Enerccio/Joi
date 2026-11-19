package com.github.enerccio.joi.domain.repository;

import com.github.enerccio.joi.domain.model.impl.AccessToken;
import com.github.enerccio.marginalia.domain.repository.ExtendableRepository;

/** The credentials of the API; found by their prefixes without a current user (the call is not authenticated yet). */
public interface AccessTokenRepository extends ExtendableRepository<AccessToken> {

    /** The credential whose (access) token has the prefix, deleted ones included; null for none. */
    AccessToken findByPrefix(String prefix) throws Exception;

    /** The OAuth credential whose refresh token has the prefix; null for none. */
    AccessToken findByRefreshPrefix(String prefix) throws Exception;
}
