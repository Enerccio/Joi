package com.github.enerccio.joi.loc;

import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.LocalizationOverride;

/**
 * The platform's texts as Joi shows them: they name the application they were written for ("Also used outside
 * Marginalia"), these say Joi instead, in every language the platform has (now or later), and a few have Joi's own wording.
 * A bean in {@code container-config.xml}, picked up by the platform's localization.
 */
public class JoiLocalizationOverride extends LocalizationOverride {

    public static final String PLATFORM_APP = "Marginalia";
    public static final String APP = "Joi";

    public JoiLocalizationOverride() {
        replace(ALL_LANGUAGES, PLATFORM_APP, APP);
        // what Joi logs as jobs (the kinds are in JoiJobs)
        text("en", L.HELP_JOBS_ADMIN, "Every job that talks to an external AI (a chat turn, a summary, a task of an assistant, the description of a model, "
                + "a speech) or converts a model with Blender is logged here with the user, its start and end and each call it made to a model, "
                + "a speech backend or Blender. Only metadata is kept, never the text. Nothing is deleted automatically: set the filter and delete "
                + "the matching jobs.");
    }
}
