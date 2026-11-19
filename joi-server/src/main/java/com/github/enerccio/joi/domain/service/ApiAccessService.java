package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.api.ApiAccess;
import com.github.enerccio.joi.protocol.http.AuthMethod;

import java.util.List;

/** What the API offers: the administrator's {@link ApiAccess} and the configuration's no auth. */
public interface ApiAccessService {

    /** The administrator's choices, the defaults when never saved. Needs no user. */
    ApiAccess get() throws Exception;

    /** Stores the choices; administrators only. */
    void save(ApiAccess access) throws Exception;

    /** Whether the configuration lets calls without credentials in. */
    boolean isNoAuth();

    /** What is offered now, in the order a client should prefer them: OAuth, tokens, none. */
    List<AuthMethod> methods() throws Exception;
}
