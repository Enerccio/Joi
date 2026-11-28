package com.github.enerccio.joi.extensions.marginalia;

import com.github.enerccio.marginalia.extensions.MarginaliaExtension;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

/** Registers the extension: the platform finds a {@code MarginaliaExtension}, Joi sees that it is a {@code JoiExtension}. */
public class MarginaliaActivator implements BundleActivator {

    private ServiceRegistration<MarginaliaExtension> registration;

    @Override
    public void start(BundleContext context) {
        registration = context.registerService(MarginaliaExtension.class, new MarginaliaBooksExtension(), null);
    }

    @Override
    public void stop(BundleContext context) {
        if (registration != null) {
            registration.unregister();
            registration = null;
        }
    }
}
