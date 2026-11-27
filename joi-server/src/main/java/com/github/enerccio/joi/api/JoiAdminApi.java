package com.github.enerccio.joi.api;

import com.github.enerccio.joi.api.JoiApi.ApiException;
import com.github.enerccio.joi.domain.service.AccessTokenService.Principal;
import com.github.enerccio.joi.protocol.http.AdminScope;
import com.github.enerccio.joi.protocol.http.ExtensionInfo;
import com.github.enerccio.joi.protocol.http.ExtensionList;
import com.github.enerccio.joi.protocol.http.UpdateStatus;
import com.github.enerccio.marginalia.domain.service.ExtensionVerificationException;
import com.github.enerccio.marginalia.domain.service.OsgiService;
import com.github.enerccio.marginalia.domain.service.UpdateService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.lang3.StringUtils;
import org.osgi.framework.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * The administration calls of Joi's API ({@code /api/v1/admin}, since protocol 1.13), as Marginalia's {@code ApiDispatcher} has
 * them: the update of the installation ({@link AdminScope#UPDATE}) and the extensions ({@link AdminScope#EXTENSIONS}). The call's
 * token must have the scope, and its owner must be an administrator ({@link Principal#scopes()} is empty otherwise). The JAR of
 * an install is streamed to a temporary file first. Without OSGi (the tests) the extension calls answer 503.
 */
public class JoiAdminApi {

    private static final Logger log = LoggerFactory.getLogger(JoiAdminApi.class);

    static final String JSON = "application/json;charset=UTF-8";
    static final String TEXT = "text/plain;charset=UTF-8";
    static final long MAX_EXTENSION_BYTES = 256L * 1024 * 1024;
    static final int DEFAULT_LOG_BYTES = 64 * 1024;
    static final int MAX_LOG_BYTES = 4 * 1024 * 1024;

    @Autowired
    @Lazy
    private UpdateService updateService;

    /** Not in the tests (no OSGi): the extension calls then answer 503. */
    @Autowired
    private ObjectProvider<OsgiService> osgiService;

    /** Nulls written out: a client sees every field of the schema. */
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    /** Whether the call is the install of an extension ({@code PUT /api/v1/admin/extensions/{jar}}): its body is streamed to the disk. */
    static boolean isExtensionUpload(String method, String path) {
        return "PUT".equalsIgnoreCase(method) && path != null && path.matches("/api/v1/admin/extensions/[^/]+/?");
    }

    /**
     * Answers a call under {@code admin}.
     *
     * @param path the segments after {@code /api/v1}, starting with {@code admin}
     */
    void handle(ApiCall call, List<String> path, Principal principal, ApiSink sink) throws Exception {
        String part = path.size() > 1 ? path.get(1) : null;
        if ("update".equals(part)) {
            require(principal, AdminScope.UPDATE);
            update(call, path, principal, sink);
        } else if ("extensions".equals(part)) {
            require(principal, AdminScope.EXTENSIONS);
            extensions(call, path, principal, sink);
        } else {
            throw notFound(call);
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // the update of the installation
    // ----------------------------------------------------------------------------------------------------------------

    private void update(ApiCall call, List<String> path, Principal principal, ApiSink sink) throws Exception {
        if (is(call, path, "GET", "admin", "update")) {
            json(sink, 200, updateStatus(updateService.getStatus()));
        } else if (is(call, path, "POST", "admin", "update")) {
            if (!updateService.getStatus().canRequest()) {
                throw new ApiException(409, "conflict", "An update cannot be asked for now: no watcher runs, or an update runs or was asked for");
            }
            updateService.requestUpdate();
            log.info("API: update of the installation requested by {}", principal.user().getLogin());
            json(sink, 202, updateStatus(updateService.getStatus()));
        } else if (is(call, path, "GET", "admin", "update", "log")) {
            text(sink, updateService.readLog(logBytes(call)));
        } else {
            throw notFound(call);
        }
    }

    private static UpdateStatus updateStatus(UpdateService.UpdateStatus status) {
        return new UpdateStatus(status.watching(), status.running(), status.requested(), status.updateAvailable(), status.canRequest(),
                status.runningVersion(), status.runningCommit(), status.branch(), status.upstream(), status.remoteCommit(),
                status.fetchFailed(), iso(status.checked()), iso(status.lastRun()), status.lastRun() == null ? null : status.lastRunOk());
    }

    // ----------------------------------------------------------------------------------------------------------------
    // the extensions
    // ----------------------------------------------------------------------------------------------------------------

    private void extensions(ApiCall call, List<String> path, Principal principal, ApiSink sink) throws Exception {
        if (is(call, path, "GET", "admin", "extensions")) {
            OsgiService osgi = osgi();
            List<ExtensionInfo> list = new ArrayList<>();
            for (Bundle bundle : osgi.getBundles()) {
                if (bundle.getBundleId() != 0) {
                    list.add(extension(osgi, bundle));
                }
            }
            json(sink, 200, new ExtensionList(list));
        } else if (is(call, path, "PUT", "admin", "extensions", "*")) {
            install(call, path.get(2), principal, sink);
        } else if (is(call, path, "GET", "admin", "extensions", "*")) {
            OsgiService osgi = osgi();
            json(sink, 200, extension(osgi, bundle(osgi, path.get(2))));
        } else if (is(call, path, "DELETE", "admin", "extensions", "*")) {
            OsgiService osgi = osgi();
            osgi.uninstallPackage(bundle(osgi, path.get(2)));
            log.info("API: extension {} uninstalled by {}", path.get(2), principal.user().getLogin());
            sink.respond(204, JSON, new byte[0], Map.of());
        } else if (is(call, path, "POST", "admin", "extensions", "*", "stop")) {
            OsgiService osgi = osgi();
            Bundle bundle = bundle(osgi, path.get(2));
            osgi.stopExtension(bundle);
            log.info("API: extension {} stopped by {}", path.get(2), principal.user().getLogin());
            json(sink, 200, extension(osgi, bundle));
        } else if (is(call, path, "POST", "admin", "extensions", "*", "start")) {
            OsgiService osgi = osgi();
            Bundle bundle = bundle(osgi, path.get(2));
            osgi.startExtension(bundle);
            log.info("API: extension {} started by {}", path.get(2), principal.user().getLogin());
            json(sink, 200, extension(osgi, bundle));
        } else if (is(call, path, "GET", "admin", "extensions", "*", "log")) {
            OsgiService osgi = osgi();
            text(sink, osgi.readLog(bundle(osgi, path.get(2)), logBytes(call)));
        } else {
            throw notFound(call);
        }
    }

    private void install(ApiCall call, String jarName, Principal principal, ApiSink sink) throws Exception {
        if (jarName == null || !jarName.toLowerCase().endsWith(".jar") || jarName.contains("..") || jarName.contains("\\")) {
            throw new ApiException(400, "bad_request", "The name of the extension is its JAR file, it ends with .jar");
        }
        OsgiService osgi = osgi();
        Path file = Files.createTempFile("joi-extension-", ".jar");
        try {
            long size = receive(call, file);
            if (size == 0) {
                throw new ApiException(400, "bad_request", "The body is the JAR of the extension");
            }
            Bundle bundle;
            try {
                // the platform installs from bytes (OsgiService.installPackage); the JAR is read from the disk only for it
                bundle = osgi.installPackage(jarName, Files.readAllBytes(file));
            } catch (ExtensionVerificationException e) {
                log.warn("API: extension {} was rejected:\n{}", e.getExtension(), e.getReport());
                throw new ApiException(422, "extension_rejected",
                        "The extension does not fit this version of Joi" + (StringUtils.isBlank(e.getReport()) ? "" : ":\n" + e.getReport()));
            }
            log.info("API: extension {} installed by {}", jarName, principal.user().getLogin());
            json(sink, 200, extension(osgi, bundle));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Streams the body into the file, at most {@value #MAX_EXTENSION_BYTES} bytes; how many there were. */
    private static long receive(ApiCall call, Path file) throws IOException, ApiException {
        try (InputStream in = call.bodyStream(); OutputStream out = Files.newOutputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > MAX_EXTENSION_BYTES) {
                    throw new ApiException(413, "bad_request", "The JAR is larger than " + (MAX_EXTENSION_BYTES / 1024 / 1024) + " MB");
                }
                out.write(buffer, 0, n);
            }
            return total;
        }
    }

    private OsgiService osgi() throws ApiException {
        OsgiService osgi = osgiService.getIfAvailable();
        if (osgi == null) {
            throw new ApiException(503, "unavailable", "Extensions are not available");
        }
        return osgi;
    }

    private static Bundle bundle(OsgiService osgi, String jarName) throws ApiException {
        Bundle bundle = jarName == null ? null : osgi.findBundleByJar(jarName);
        if (bundle == null || bundle.getBundleId() == 0) {
            throw new ApiException(404, "not_found", "No such extension: " + jarName);
        }
        return bundle;
    }

    private static ExtensionInfo extension(OsgiService osgi, Bundle bundle) {
        OsgiService.ExtensionReport report = osgi.getVerificationReport(bundle);
        String sha256;
        try {
            sha256 = osgi.getSha256(bundle);
        } catch (Exception e) {
            sha256 = null;
        }
        return new ExtensionInfo(jarName(bundle), bundle.getSymbolicName(), bundle.getHeaders().get("Bundle-Name"),
                bundle.getVersion() == null ? null : bundle.getVersion().toString(), state(bundle.getState()), osgi.isStopped(bundle),
                osgi.getLoadedExtensions().stream().anyMatch(e -> e.bundle().getBundleId() == bundle.getBundleId()),
                report == null ? null : report.valid(), sha256);
    }

    private static String jarName(Bundle bundle) {
        String location = StringUtils.defaultString(bundle.getLocation()).replace("file:", "").replace('\\', '/');
        return new File(location).getName();
    }

    private static String state(int state) {
        return switch (state) {
            case Bundle.UNINSTALLED -> "UNINSTALLED";
            case Bundle.INSTALLED -> "INSTALLED";
            case Bundle.RESOLVED -> "RESOLVED";
            case Bundle.STARTING -> "STARTING";
            case Bundle.STOPPING -> "STOPPING";
            case Bundle.ACTIVE -> "ACTIVE";
            default -> String.valueOf(state);
        };
    }

    // ----------------------------------------------------------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------------------------------------------------------

    private static void require(Principal principal, AdminScope scope) throws ApiException {
        if (!principal.has(scope)) {
            throw new ApiException(403, "forbidden", "The token does not have the scope " + scope.name().toLowerCase()
                    + ", or its owner is not an administrator");
        }
    }

    /** {@code path} is the call: its method and segments, {@code *} for any one. */
    private static boolean is(ApiCall call, List<String> path, String method, String... segments) {
        if (!call.method().equalsIgnoreCase(method) || path.size() != segments.length) {
            return false;
        }
        for (int i = 0; i < segments.length; i++) {
            if (!segments[i].equals("*") && !segments[i].equals(path.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static int logBytes(ApiCall call) {
        String asked = call.query().get("bytes");
        if (StringUtils.isBlank(asked)) {
            return DEFAULT_LOG_BYTES;
        }
        try {
            int bytes = Integer.parseInt(asked.strip());
            if (bytes < 1 || bytes > MAX_LOG_BYTES) {
                throw new IllegalArgumentException("bytes is 1 to " + MAX_LOG_BYTES);
            }
            return bytes;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bytes is not a number: " + asked);
        }
    }

    private static ApiException notFound(ApiCall call) {
        return new ApiException(404, "not_found", "Not found: " + call.method() + " " + call.path());
    }

    private void json(ApiSink sink, int status, Object value) throws IOException {
        sink.respond(status, JSON, gson.toJson(value).getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private static void text(ApiSink sink, String text) throws IOException {
        sink.respond(200, TEXT, StringUtils.defaultString(text).getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private static String iso(Date date) {
        return date == null ? null : date.toInstant().toString();
    }
}
