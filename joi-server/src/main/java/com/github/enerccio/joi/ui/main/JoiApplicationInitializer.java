package com.github.enerccio.joi.ui.main;

import com.github.enerccio.marginalia.bound.SessionTrackingListener;
import com.github.enerccio.marginalia.ui.main.HealthServlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.jspecify.annotations.NonNull;
import org.springframework.web.WebApplicationInitializer;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.request.RequestContextListener;
import org.springframework.web.context.support.XmlWebApplicationContext;

/**
 * Starts the Spring context of joi-server from its XML ({@code META-INF/spring/application-config.xml}) when the
 * servlet container starts the WAR; there is no web.xml. The Vaadin servlet is {@link JoiServlet}.
 */
public class JoiApplicationInitializer implements WebApplicationInitializer {

    @Override
    public void onStartup(@NonNull ServletContext servletContext) throws ServletException {
        System.setProperty("vaadin.copilot.enable", "false");
        XmlWebApplicationContext rootContext = new XmlWebApplicationContext();
        rootContext.setConfigLocations("/WEB-INF/classes/META-INF/spring/application-config.xml");

        servletContext.addListener(new ContextLoaderListener(rootContext));
        servletContext.addListener(new RequestContextListener());
        servletContext.addListener(new SessionTrackingListener(rootContext));
        servletContext.addServlet("HealthServlet", new HealthServlet()).addMapping(HealthServlet.PATH);
    }
}
