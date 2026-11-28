package com.github.enerccio.joi.domain.extensions;

import com.github.enerccio.joi.domain.chat.turn.TurnEvent;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.extensions.ExtensionConfig;
import com.github.enerccio.joi.extensions.ExtensionPart;
import com.github.enerccio.joi.extensions.ExtensionSettings;
import com.github.enerccio.joi.extensions.ExtensionTask;
import com.github.enerccio.joi.extensions.ExtensionTool;
import com.github.enerccio.joi.extensions.JoiExtension;
import com.github.enerccio.joi.extensions.JoiExtensionContext;
import com.github.enerccio.joi.extensions.ToolProvider;
import com.github.enerccio.marginalia.domain.service.OsgiService;
import com.github.enerccio.marginalia.extensions.MarginaliaExtension;
import org.osgi.framework.Bundle;
import org.slf4j.Logger;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * Hands the {@link JoiExtensionContext} to the extensions of Joi: when the platform's {@code OsgiService} reports that an extension
 * that is a {@link JoiExtension} was loaded, it calls {@code onJoiLoad}; before an unload, {@code onJoiUnload}. The platform's service
 * is not there in the tests (see {@code extensions-config.xml}), then nothing is reported, and {@link #load} / {@link #unload} are
 * what a test calls.
 */
public class JoiExtensionLoader implements OsgiService.ExtensionListener, InitializingBean, ApplicationContextAware {

    @Autowired(required = false)
    private OsgiService osgiService;

    @Autowired
    private JoiExtensions extensions;

    @Autowired
    private ExtensionSettingsService settingsService;

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterPropertiesSet() {
        if (osgiService != null) {
            osgiService.addExtensionListener(this);
        }
    }

    @Override
    public void extensionLoaded(Bundle bundle, MarginaliaExtension extension) {
        if (extension instanceof JoiExtension joiExtension) {
            load(joiExtension, bundle, logger(bundle));
        }
    }

    @Override
    public void extensionUnloaded(Bundle bundle, MarginaliaExtension extension) {
        if (extension instanceof JoiExtension joiExtension) {
            unload(joiExtension, bundle, logger(bundle));
        }
    }

    private Logger logger(Bundle bundle) {
        return osgiService != null ? osgiService.createLogger(bundle) : org.slf4j.LoggerFactory.getLogger("extension." + bundle.getSymbolicName());
    }

    /** Calls {@code onJoiLoad}; an extension that fails has what it registered until then removed again. */
    public void load(JoiExtension extension, Bundle bundle, Logger log) {
        Context context = new Context(bundle.getSymbolicName(), log);
        try {
            extension.onJoiLoad(bundle, context);
        } catch (Throwable t) {
            log.error("The extension {} could not be loaded: {}", context.owner(), t.getMessage(), t);
            extensions.unregisterAll(context.owner());
        }
    }

    /** Calls {@code onJoiUnload}; what the extension registered is removed by the platform's service after it. */
    public void unload(JoiExtension extension, Bundle bundle, Logger log) {
        try {
            extension.onJoiUnload(bundle, new Context(bundle.getSymbolicName(), log));
        } catch (Throwable t) {
            log.warn("The extension {} did not unload cleanly: {}", bundle.getSymbolicName(), t.getMessage(), t);
        }
    }

    private final class Context implements JoiExtensionContext {

        private final String owner;
        private final Logger log;

        Context(String owner, Logger log) {
            this.owner = owner;
            this.log = log;
        }

        @Override
        public String owner() {
            return owner;
        }

        @Override
        public Logger log() {
            return log;
        }

        @Override
        public <T> T bean(Class<T> type) {
            try {
                return applicationContext.getBean(type);
            } catch (BeansException e) {
                throw new IllegalArgumentException("The server has no " + type.getName(), e);
            }
        }

        @Override
        public void offerTools(ToolProvider provider) {
            extensions.offerTools(owner, provider);
        }

        @Override
        public void addWorkspacePart(ExtensionPart part) {
            extensions.addPart(owner, part);
        }

        @Override
        public void onTurnEvent(TurnEvents event, TurnEvent listener) {
            extensions.onTurnEvent(owner, event, listener);
        }

        @Override
        public void declareTool(ExtensionTool tool) {
            extensions.declareTool(owner, tool);
        }

        @Override
        public void offerTask(ExtensionTask task) {
            extensions.offerTask(owner, task);
        }

        @Override
        public void declareSettings(ExtensionSettings settings) {
            extensions.declareSettings(owner, settings);
        }

        @Override
        public ExtensionConfig settings(Joi joi) throws Exception {
            return settingsService.resolve(joi, owner);
        }
    }
}
