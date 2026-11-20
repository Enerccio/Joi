package com.github.enerccio.joi.node.fx;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.CefBuildInfo;
import me.friwi.jcefmaven.EnumPlatform;
import me.friwi.jcefmaven.impl.step.check.CefInstallationChecker;
import me.friwi.jcefmaven.impl.step.extract.TarGzExtractor;
import me.friwi.jcefmaven.impl.util.FileUtils;
import me.friwi.jcefmaven.impl.util.macos.UnquarantineUtil;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.cef.CefApp;
import org.cef.CefClient;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Chromium (JCEF) for the renderer page, started once: its natives come from the {@code jcef-natives-*} jar on the
 * class path, unpacked into {@code jcef/} of the node's folder on the first start; it never downloads them. Off-screen
 * rendering only (see {@link ChromiumPane}).
 */
final class Chromium {

    private static CefApp app;

    private Chromium() {
    }

    /** Starts Chromium (slow the first time: it unpacks); call it off the UI thread. */
    static synchronized CefClient client(Path nodeDir) throws Exception {
        if (app == null) {
            Path dir = nodeDir.resolve("jcef");
            File bundle = dir.resolve("bundle").toFile();
            install(bundle);
            CefAppBuilder builder = new CefAppBuilder();
            builder.setInstallDir(bundle);
            builder.setMirrors(List.of());
            builder.getCefSettings().windowless_rendering_enabled = true;
            builder.getCefSettings().root_cache_path = dir.resolve("profile").toString();
            builder.getCefSettings().log_file = dir.resolve("chromium.log").toString();
            builder.setAppHandler(new MavenCefAppHandlerAdapter() {
            });
            app = builder.build();
        }
        return app.createClient();
    }

    /**
     * Unpacks the natives as jcefmaven would, from the natives jar wherever it is: jcefmaven itself looks only on the
     * class path, and the jar may be on the module path.
     */
    private static void install(File bundle) throws Exception {
        if (CefInstallationChecker.checkInstallation(bundle)) {
            return;
        }
        FileUtils.deleteDir(bundle);
        if (!bundle.mkdirs()) {
            throw new IOException("Could not create " + bundle);
        }
        String name = "jcef-natives-" + EnumPlatform.getCurrentPlatform().getIdentifier() + "-"
                + CefBuildInfo.fromClasspath().getReleaseTag() + ".tar.gz";
        try (InputStream in = natives(name)) {
            if (in == null) {
                throw new IOException("Chromium's natives are not on the class path: " + name);
            }
            TarGzExtractor.extractTarGZ(bundle, in);
        }
        if (EnumPlatform.getCurrentPlatform().getOs().isMacOSX()) {
            UnquarantineUtil.unquarantine(bundle);
        }
        if (!new File(bundle, "install.lock").createNewFile()) {
            throw new IOException("Could not finish " + bundle);
        }
    }

    private static InputStream natives(String name) throws IOException {
        InputStream in = ClassLoader.getSystemResourceAsStream(name);
        if (in != null) {
            return in;
        }
        for (Module module : ModuleLayer.boot().modules()) {
            in = module.getResourceAsStream(name);
            if (in != null) {
                return in;
            }
        }
        return null;
    }

    /** Stops Chromium, when it was started. */
    static synchronized void dispose() {
        if (app != null) {
            app.dispose();
            app = null;
        }
    }
}
