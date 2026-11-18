package com.github.enerccio.joi.ui.main;

import com.vaadin.flow.server.VaadinServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Configurable;

import java.io.IOException;

/**
 * The Vaadin servlet of joi-server, on every path but the ones {@link JoiApplicationInitializer} maps.
 */
@WebServlet(urlPatterns = "/*", name = "JoiServlet", asyncSupported = true, loadOnStartup = 1)
@Configurable
public class JoiServlet extends VaadinServlet {
    private static final Logger log = LoggerFactory.getLogger(JoiServlet.class);

    @Override
    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException {
        try {
            super.service(req, res);
        } catch (Throwable t) {
            log.error(t.getMessage());
            log.debug(t.getMessage(), t);
            throw new ServletException(t);
        }
    }
}
