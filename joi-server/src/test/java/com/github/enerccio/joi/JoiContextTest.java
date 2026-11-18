package com.github.enerccio.joi;

import com.github.enerccio.joi.domain.model.impl.settings.JoiUserSetting;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.marginalia.Configuration;
import com.github.enerccio.marginalia.domain.service.SettingService;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * joi-server's wiring starts on its own migrations (Hibernate validates every entity against them), with Joi's
 * schema, user settings and texts.
 */
class JoiContextTest extends PlatformTestBase {

    @Autowired
    private Configuration configuration;

    @Autowired
    private SettingService settingService;

    @Autowired
    private Localization loc;

    @Test
    void usesJoisSchema() {
        assertThat(configuration.getDatabaseSchema()).isEqualTo(JoiDatabase.SCHEMA);
    }

    @Test
    void userSettingsAreJois() throws Exception {
        login();
        assertThat(settingService.getUserSettingClass()).isEqualTo(JoiUserSetting.class);
        assertThat(settingService.getUserSetting()).isInstanceOf(JoiUserSetting.class);
    }

    @Test
    void textsOfBothLayers() {
        assertThat(loc.getValue(J_L.LABEL_APP_DESC)).isEqualTo("Your personal assistant");
        assertThat(loc.getValue(L.LABEL_APP_DESC)).isNotBlank();
    }
}
