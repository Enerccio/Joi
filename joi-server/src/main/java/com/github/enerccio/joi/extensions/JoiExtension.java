package com.github.enerccio.joi.extensions;

import com.github.enerccio.marginalia.extensions.MarginaliaExtension;
import org.osgi.framework.Bundle;

/**
 * An extension of Joi: an OSGi bundle (a JAR in the extensions folder, or loaded in Administration → Extensions) with a
 * {@code BundleActivator} that registers an instance of this interface as an OSGi service, as the model providers do with the
 * platform's {@link MarginaliaExtension}. The bundle sees the server's classes (boot delegation), so it needs no imports; it embeds
 * the libraries only it needs.
 * <pre>
 * public class MyActivator implements BundleActivator {
 *     public void start(BundleContext context) {
 *         context.registerService(MarginaliaExtension.class, new MyExtension(), null);
 *     }
 *     ...
 * }
 * </pre>
 * The extension is registered as a {@link MarginaliaExtension} (what the platform looks for); one that is also a {@code JoiExtension}
 * gets the {@link JoiExtensionContext}. A model provider is no more than a {@code MarginaliaExtension} (see the platform's
 * {@code ExtensionAIFactory}), and needs none of this.
 */
public interface JoiExtension extends MarginaliaExtension {

    /**
     * The extension was loaded (the server's start, an upload, Start in the administration): register what it adds. Called on the thread
     * that loads it, not in a request scope of a user.
     */
    void onJoiLoad(Bundle bundle, JoiExtensionContext context) throws Exception;

    /**
     * The extension is about to be unloaded: let go of what it holds (threads, connections). What it registered through the context is
     * removed by Joi afterwards.
     */
    default void onJoiUnload(Bundle bundle, JoiExtensionContext context) throws Exception {
    }

    // the platform's two methods: the platform's service calls them first; Joi's are called right after, with the context

    @Override
    default void onExtensionLoad(Bundle bundle, com.github.enerccio.marginalia.domain.service.OsgiService parentService,
                                 com.github.enerccio.marginalia.domain.service.ExtensionService extensionService) {
    }

    @Override
    default void onExtensionUnload(Bundle b, com.github.enerccio.marginalia.domain.service.OsgiService parentService,
                                   com.github.enerccio.marginalia.domain.service.ExtensionService extensionService) {
    }
}
