package com.github.enerccio.joi.domain.model.impl.settings;

import com.github.enerccio.marginalia.domain.model.impl.settings.BaseUserSetting;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

/**
 * The settings of a Joi user, on top of the platform's ({@link BaseUserSetting}: default model and protocol, theme,
 * language). {@code SettingService} creates it ({@code userSettingClass} in {@code services-config.xml}). Fields are
 * extended attributes, so adding one needs no migration.
 * <p>
 * The discriminator must be the simple class name: {@code Setting} writes {@code getClass().getSimpleName()} into
 * {@code DTYPE} whatever the annotation says, so any other value can't be read back.
 */
@Entity
@DiscriminatorValue("JoiUserSetting")
public class JoiUserSetting extends BaseUserSetting {
}
