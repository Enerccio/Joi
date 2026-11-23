package com.github.enerccio.joi.api;

import com.github.enerccio.joi.domain.chat.ChatImages;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The HTTP side of Joi's API: reads the call into an {@link ApiCall} and hands it to {@link JoiApi}. Mapped to
 * {@code /api/*}, {@code /oauth/*} and the OAuth metadata in {@code JoiApplicationInitializer}, before the {@code /*} of
 * the Vaadin servlet. It never makes a session of the container. A chat blocks the thread of the call while she
 * answers.
 */
public class JoiApiServlet extends HttpServlet {

    static final int MAX_BODY_BYTES = 1024 * 1024;

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        ServletSink sink = new ServletSink(response);
        WebApplicationContext context = WebApplicationContextUtils.getWebApplicationContext(getServletContext());
        if (context == null) {
            JoiApi.error(sink, 503, "unavailable", "The server is starting");
            return;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // an image is streamed to the disk by the call itself, after its credentials were checked: not read here
        boolean upload = "POST".equalsIgnoreCase(request.getMethod()) && JoiApi.isImageUpload(path);
        if (upload && request.getContentLengthLong() > ChatImages.MAX_BYTES) {
            JoiApi.error(sink, 413, "bad_image", "The image is larger than " + (ChatImages.MAX_BYTES / 1024 / 1024) + " MB");
            return;
        }
        if (!upload && request.getContentLengthLong() > MAX_BODY_BYTES) {
            JoiApi.error(sink, 413, "bad_request", "The body is larger than " + MAX_BODY_BYTES + " bytes");
            return;
        }
        byte[] body = upload ? new byte[0] : read(request.getInputStream());
        if (body == null) {
            JoiApi.error(sink, 413, "bad_request", "The body is larger than " + MAX_BODY_BYTES + " bytes");
            return;
        }
        Map<String, String> headers = new HashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        boolean local = context.getBean(ApiConfiguration.class).isLocalSocket(request.getLocalAddr(), request.getLocalPort());
        ApiCall call = new ApiCall(request.getMethod(), path, ApiCall.parse(request.getQueryString()), headers, body, baseUrl(request), local,
                upload ? request.getInputStream() : null);
        context.getBean(JoiApi.class).handle(call, sink);
    }

    /** The URL of the server as the client reached it (the Host it sent). */
    static String baseUrl(HttpServletRequest request) {
        String host = request.getHeader("Host");
        if (host == null || host.isBlank()) {
            int port = request.getServerPort();
            boolean standard = ("http".equals(request.getScheme()) && port == 80) || ("https".equals(request.getScheme()) && port == 443);
            host = request.getServerName() + (standard ? "" : ":" + port);
        }
        return request.getScheme() + "://" + host + request.getContextPath();
    }

    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int n;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > MAX_BODY_BYTES) {
                return null;
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** Writes to the servlet's response. */
    static final class ServletSink implements ApiSink {

        private final HttpServletResponse response;

        ServletSink(HttpServletResponse response) {
            this.response = response;
        }

        @Override
        public void respond(int status, String contentType, byte[] body, Map<String, String> headers) throws IOException {
            response.setStatus(status);
            response.setContentType(contentType);
            headers.forEach(response::setHeader);
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
        }

        @Override
        public void file(int status, String contentType, File file, Map<String, String> headers) throws IOException {
            response.setStatus(status);
            response.setContentType(contentType);
            headers.forEach(response::setHeader);
            response.setContentLengthLong(file.length());
            Files.copy(file.toPath(), response.getOutputStream());
        }

        @Override
        public EventStream events() throws IOException {
            response.setStatus(200);
            response.setContentType("text/event-stream;charset=UTF-8");
            // no buffering by a proxy (nginx)
            response.setHeader("X-Accel-Buffering", "no");
            OutputStream out = response.getOutputStream();
            response.flushBuffer();
            return new EventStream() {
                @Override
                public synchronized void send(String event, String json) throws IOException {
                    out.write(("event: " + event + "\ndata: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }

                @Override
                public synchronized void comment(String text) throws IOException {
                    out.write((": " + text + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            };
        }
    }
}
