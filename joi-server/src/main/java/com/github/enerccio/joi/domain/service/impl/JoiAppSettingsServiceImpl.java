package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.service.JoiAppSettingsService;
import com.github.enerccio.joi.domain.settings.JoiAppSettings;
import com.github.enerccio.marginalia.domain.model.impl.settings.AppSettings;
import com.github.enerccio.marginalia.domain.security.AdminGuard;
import com.github.enerccio.marginalia.domain.service.SettingService;
import com.github.enerccio.marginalia.domain.traits.CommonTx;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.springframework.beans.factory.annotation.Autowired;

/** {@link JoiAppSettingsService} on the application's settings. */
public class JoiAppSettingsServiceImpl implements JoiAppSettingsService {

    @Autowired
    private SettingService settingService;

    @Autowired
    private AdminGuard adminGuard;

    private final Gson gson = new Gson();

    @Override
    @CommonTxReadOnly
    public JoiAppSettings get() throws Exception {
        AppSettings settings = settingService.getOrCreateApp(AppSettings.class);
        JsonObject attributes = settings.getAttributes();
        if (attributes != null && attributes.has(JoiAppSettings.KEY)) {
            try {
                JoiAppSettings stored = gson.fromJson(attributes.get(JoiAppSettings.KEY), JoiAppSettings.class);
                if (stored != null) {
                    return stored;
                }
            } catch (RuntimeException e) {
                // a broken text is the defaults
            }
        }
        return JoiAppSettings.DEFAULT;
    }

    @Override
    @CommonTx
    public void save(JoiAppSettings joiSettings) throws Exception {
        adminGuard.requireAdmin();
        AppSettings settings = settingService.getOrCreateApp(AppSettings.class);
        JsonObject attributes = settings.getAttributes();
        if (attributes == null) {
            attributes = new JsonObject();
            settings.setAttributes(attributes);
        }
        attributes.add(JoiAppSettings.KEY, gson.toJsonTree(joiSettings == null ? JoiAppSettings.DEFAULT : joiSettings));
        settingService.save(settings);
    }

    @Override
    @CommonTxReadOnly
    public boolean localServersAllowed() throws Exception {
        return adminGuard.isAdmin() || get().usersMayUseLocalServers();
    }
}
