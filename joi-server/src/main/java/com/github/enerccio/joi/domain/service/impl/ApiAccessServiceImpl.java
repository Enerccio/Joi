package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.api.ApiAccess;
import com.github.enerccio.joi.api.ApiConfiguration;
import com.github.enerccio.joi.domain.service.ApiAccessService;
import com.github.enerccio.joi.protocol.http.AuthMethod;
import com.github.enerccio.marginalia.domain.model.impl.settings.AppSettings;
import com.github.enerccio.marginalia.domain.security.AdminGuard;
import com.github.enerccio.marginalia.domain.service.SettingService;
import com.github.enerccio.marginalia.domain.traits.CommonTx;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

/** {@link ApiAccessService} on the application's settings. */
public class ApiAccessServiceImpl implements ApiAccessService {

    @Autowired
    private SettingService settingService;

    @Autowired
    private AdminGuard adminGuard;

    @Autowired
    private ApiConfiguration configuration;

    private final Gson gson = new Gson();

    @Override
    @CommonTxReadOnly
    public ApiAccess get() throws Exception {
        AppSettings settings = settingService.getOrCreateApp(AppSettings.class);
        JsonObject attributes = settings.getAttributes();
        if (attributes != null && attributes.has(ApiAccess.KEY)) {
            ApiAccess access = gson.fromJson(attributes.get(ApiAccess.KEY), ApiAccess.class);
            if (access != null) {
                // through the constructor, so the limits apply to what was stored too
                return new ApiAccess(access.tokens(), access.oauth(), access.accessTokenMinutes(), access.refreshTokenDays());
            }
        }
        return ApiAccess.DEFAULT;
    }

    @Override
    @CommonTx
    public void save(ApiAccess access) throws Exception {
        adminGuard.requireAdmin();
        AppSettings settings = settingService.getOrCreateApp(AppSettings.class);
        JsonObject attributes = settings.getAttributes();
        if (attributes == null) {
            attributes = new JsonObject();
            settings.setAttributes(attributes);
        }
        attributes.add(ApiAccess.KEY, gson.toJsonTree(access));
        settingService.save(settings);
    }

    @Override
    public boolean isNoAuth() {
        return configuration.isNoAuth();
    }

    @Override
    public List<AuthMethod> methods() throws Exception {
        ApiAccess access = get();
        List<AuthMethod> methods = new ArrayList<>();
        if (access.oauth()) {
            methods.add(AuthMethod.OAUTH);
        }
        if (access.tokens()) {
            methods.add(AuthMethod.TOKEN);
        }
        if (configuration.isNoAuth()) {
            methods.add(AuthMethod.NONE);
        }
        return methods;
    }
}
