package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.marginalia.domain.traits.TemplateDescriptionSource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The description of a variable of a Joi template, a {@link J_L} key, shown in the hints next to a prompt field (see
 * {@link TemplateDescriptionSource}).
 */
@TemplateDescriptionSource
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface JoiTemplateDescription {

    J_L loc();
}
