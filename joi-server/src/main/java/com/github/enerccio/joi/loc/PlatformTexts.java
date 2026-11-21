package com.github.enerccio.joi.loc;

import com.github.enerccio.marginalia.loc.*;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The platform's texts ({@link L}) as Joi shows them: the platform's bundles name the application they were written
 * for ("Also used outside Marginalia"), these say Joi instead. One subclass per language, registered in
 * {@code container-config.xml} in place of the platform's own bundles.
 */
public final class PlatformTexts {

    public static final String PLATFORM_APP = "Marginalia";
    public static final String APP = "Joi";

    private PlatformTexts() {
    }

    /** Puts every text naming the platform's application again with Joi's name. */
    static void rebrand(Map<L, String> messages, BiConsumer<L, String> setValue) {
        new EnumMap<>(messages).forEach((key, text) -> {
            if (text != null && text.contains(PLATFORM_APP)) {
                setValue.accept(key, text.replace(PLATFORM_APP, APP));
            }
        });
    }

    public static class EN extends LocalizationBundleEN {
        @Override
        protected void loadMessages() {
            super.loadMessages();
            rebrand(getMessages(), (key, text) -> setValue(key, text));
            // what Joi logs as jobs (the kinds are in JoiJobs)
            setValue(L.HELP_JOBS_ADMIN, "Every job that talks to an external AI (a chat turn, a summary, a task of an assistant, the description of a model, "
                    + "a speech) or converts a model with Blender is logged here with the user, its start and end and each call it made to a model, "
                    + "a speech backend or Blender. Only metadata is kept, never the text. Nothing is deleted automatically: set the filter and delete "
                    + "the matching jobs.");
        }
    }

    public static class JP extends LocalizationBundleJP {
        @Override
        protected void loadMessages() {
            super.loadMessages();
            rebrand(getMessages(), (key, text) -> setValue(key, text));
        }
    }

    public static class FR extends LocalizationBundleFR {
        @Override
        protected void loadMessages() {
            super.loadMessages();
            rebrand(getMessages(), (key, text) -> setValue(key, text));
        }
    }

    public static class ES extends LocalizationBundleES {
        @Override
        protected void loadMessages() {
            super.loadMessages();
            rebrand(getMessages(), (key, text) -> setValue(key, text));
        }
    }

    public static class DE extends LocalizationBundleDE {
        @Override
        protected void loadMessages() {
            super.loadMessages();
            rebrand(getMessages(), (key, text) -> setValue(key, text));
        }
    }

    public static class KO extends LocalizationBundleKO {
        @Override
        protected void loadMessages() {
            super.loadMessages();
            rebrand(getMessages(), (key, text) -> setValue(key, text));
        }
    }

    public static class IT extends LocalizationBundleIT {
        @Override
        protected void loadMessages() {
            super.loadMessages();
            rebrand(getMessages(), (key, text) -> setValue(key, text));
        }
    }
}
