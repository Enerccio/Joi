package com.github.enerccio.joi.ui.main;

import com.github.enerccio.joi.api.JoiApiServlet;
import com.github.enerccio.joi.api.NodeEndpoint;
import com.github.enerccio.joi.domain.service.NodeService;
import com.github.enerccio.joi.protocol.JoiProtocol;
import com.github.enerccio.marginalia.bound.SessionCookie;
import com.github.enerccio.marginalia.bound.SessionTrackingListener;
import com.github.enerccio.marginalia.ui.main.HealthServlet;
import com.github.enerccio.marginalia.utils.BuildInfo;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import org.jspecify.annotations.NonNull;
import org.springframework.web.WebApplicationInitializer;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.request.RequestContextListener;
import org.springframework.web.context.support.WebApplicationContextUtils;
import org.springframework.web.context.support.XmlWebApplicationContext;

/**
 * Starts the Spring context of joi-server from its XML ({@code META-INF/spring/application-config.xml}) when the
 * servlet container starts the WAR; there is no web.xml. The Vaadin servlet is {@link JoiServlet}.
 */
public class JoiApplicationInitializer implements WebApplicationInitializer {

    /** The default name of the session cookie. */
    static final String SESSION_COOKIE = "JOISESSIONID";

    /**
     * The name of the session cookie: {@code joi.session-cookie} (a {@code -D} property) or {@code JOI_SESSION_COOKIE}, else
     * {@value #SESSION_COOKIE}. The platform's own setting ({@code MARGINALIA_SESSION_COOKIE}) still wins over it.
     */
    static String sessionCookieName() {
        String name = System.getProperty("joi.session-cookie");
        if (name == null || name.isBlank()) {
            name = System.getenv("JOI_SESSION_COOKIE");
        }
        return name == null || name.isBlank() ? SESSION_COOKIE : name.trim();
    }

    @Override
    public void onStartup(@NonNull ServletContext servletContext) throws ServletException {
        System.setProperty("vaadin.copilot.enable", "false");
        // the name and version the login, the footer and the API show, and a session cookie that is not Marginalia's JSESSIONID
        // (cookies are scoped to the host, not to the port: two applications on one host replace each other's session)
        BuildInfo.install(BuildInfo.load("Joi", BuildInfo.BUILD_INFO, "joi", BuildInfo.GIT_INFO, JoiApplicationInitializer.class.getClassLoader()));
        SessionCookie.configure(servletContext, sessionCookieName());
        XmlWebApplicationContext rootContext = new XmlWebApplicationContext();
        rootContext.setConfigLocations("/WEB-INF/classes/META-INF/spring/application-config.xml");

        servletContext.addListener(new ContextLoaderListener(rootContext));
        servletContext.addListener(new RequestContextListener());
        servletContext.addListener(new SessionTrackingListener(rootContext));
        servletContext.addServlet("HealthServlet", new HealthServlet()).addMapping(HealthServlet.PATH);
        // the API of joi-protocol; prefix mappings win over the /* of the Vaadin servlet
        servletContext.addServlet("JoiApiServlet", new JoiApiServlet())
                .addMapping("/api/*", "/oauth/*", JoiProtocol.OAUTH_METADATA);
        // the node WebSocket, once the Spring context above is up (listeners start in the order they were added)
        servletContext.addListener(new ServletContextListener() {
            @Override
            public void contextInitialized(ServletContextEvent event) {
                ServletContext context = event.getServletContext();
                NodeEndpoint.register(context, WebApplicationContextUtils.getRequiredWebApplicationContext(context).getBean(NodeService.class));
            }
        });
    }
}
