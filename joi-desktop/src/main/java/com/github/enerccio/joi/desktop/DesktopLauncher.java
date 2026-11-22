package com.github.enerccio.joi.desktop;

import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Button;
import java.awt.CheckboxMenuItem;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Label;
import java.awt.MenuItem;
import java.awt.Panel;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.ProcessBuilder.Redirect;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.UnixDomainSocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Desktop entry point of Joi: runs the server on the bundled Jetty ({@code jetty-home}, the same server as the Docker image) in this
 * JVM, with two doors: the web UI on a loopback port, where the user logs in as in the server, and the API on a Unix domain socket
 * in the data folder, where a node of this machine gets its token without a key or a login (see {@code joi.api.local-socket} and
 * {@code POST /oauth/local} in the protocol). The tray icon opens the UI, starts the node (the JavaFX app, a second program of the
 * same installation) and quits; only one node runs (it holds a lock in its home, see {@code NodeInstance} in joi-node).
 * <p>
 * Server files are in a {@code server/} directory ({@code jetty-home/} and {@code joi.war}) next to the launcher jar's directory
 * or above it: jpackage places app content differently per OS. The writable Jetty base (module configuration, deployment
 * descriptor, extracted webapp) lives in {@code <data>/desktop}, so the installation itself can be read-only (a macOS app bundle).
 * <p>
 * Deliberately depends on the JDK only: Jetty is started through {@code start.jar} by reflection and the node is a separate
 * process, so this class compiles without anything else on the class path.
 */
public final class DesktopLauncher {

    private static final String APP_NAME = "Joi";
    private static final int DEFAULT_PORT = 8766;
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);
    /** Jetty's modules; the last one opens the Unix domain socket of the API. */
    private static final String MODULES = "server,http,unixdomain-http,ee11-deploy,ee11-webapp,ee11-annotations,"
            + "ee11-websocket-jakarta,ee11-jsp";
    /** The longest socket path that every system accepts (the limit is 104 bytes on macOS and 108 on Linux, with the end mark). */
    private static final int MAX_SOCKET_PATH = 100;
    private static final String NODE_MAIN = "com.github.enerccio.joi.node.fx.Launcher";
    private static final String NODE_LOCK = "node.lock";

    private final Options options;
    private final Path serverDir;
    private Path dataDir;
    private Path desktopDir;
    private Path socket;
    private URI uri;
    private Process node;
    private TrayIcon trayIcon;
    private Frame fallbackWindow;
    private final Properties preferences = new Properties();

    private DesktopLauncher(Options options, Path serverDir) {
        this.options = options;
        this.serverDir = serverDir;
    }

    public static void main(String[] args) throws Exception {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println();
            System.err.println(Options.USAGE);
            System.exit(2);
            return;
        }
        if (options.help) {
            System.out.println(Options.USAGE);
            return;
        }
        try {
            new DesktopLauncher(options, serverDirectory()).run();
        } catch (IllegalStateException e) {
            // expected startup problems (port taken, broken installation): a message is enough
            System.err.println("Joi cannot start: " + e.getMessage());
            System.exit(1);
        }
    }

    private void run() throws Exception {
        dataDir = dataDirectory(options);
        // the server reads the folder from joi.data (JOI_DATA), the same as in the Docker setup
        System.setProperty("joi.data", dataDir.toString());
        desktopDir = dataDir.resolve("desktop");
        createPrivateDirectory(desktopDir);
        redirectOutput(desktopDir.resolve("logs"));
        loadPreferences();

        Path jettyHome = serverDir.resolve("jetty-home");
        Path war = serverDir.resolve("joi.war");
        require(jettyHome.resolve("start.jar"), "Jetty");
        require(war, "web application");

        int port = options.port;
        uri = uri(options.host, port);
        if (!isPortFree(options.host, port)) {
            if (isJoi(uri)) {
                log("Joi is already running at " + uri);
                if (options.openBrowser) {
                    openBrowser();
                }
                return;
            }
            if (options.portExplicit) {
                throw new IllegalStateException("Port " + port + " is used by another application");
            }
            port = freePort(options.host);
            uri = uri(options.host, port);
            log("Port " + options.port + " is used by another application, using " + port);
        }
        if (!isLoopback(options.host)) {
            log("WARNING: listening on " + options.host + " makes the web UI reachable from the network without HTTPS");
        }

        socket = socketPath(desktopDir);
        if (Files.exists(socket)) {
            if (answers(socket)) {
                throw new IllegalStateException("Another Joi runs with the data folder " + dataDir + " (its socket " + socket
                        + " answers)");
            }
            // left by a server that did not stop cleanly
            Files.delete(socket);
        }
        // the API's local door: a call that comes in on it is the user's own, the server hands it a token
        System.setProperty("joi.api.local-socket", socket.toString());

        Path base = prepareJettyBase(desktopDir.resolve("jetty-base"), war);
        showControls("Starting " + APP_NAME + "...");
        log("Starting " + APP_NAME + " (data in " + dataDir + ", API socket " + socket + ")");

        startJetty(jettyHome, base, options.host, port, socket);

        if (!waitUntilReady(uri)) {
            log("Joi did not start in time, see the log in " + desktopDir.resolve("logs"));
            showControls("Failed to start - see the log");
            return;
        }
        log("Joi is running at " + uri);
        showControls(APP_NAME + " is running at " + uri);
        if (options.openBrowser) {
            openBrowser();
        }
        if (options.node && Boolean.parseBoolean(preferences.getProperty("node.autostart", "true"))) {
            startNode();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // jetty
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Writes the Jetty base: enabled modules and the webapp descriptor. The webapp is extracted once into {@code work/} and reused
     * while the WAR doesn't change.
     */
    static Path prepareJettyBase(Path base, Path war) throws IOException {
        Files.createDirectories(base.resolve("start.d"));
        Files.createDirectories(base.resolve("webapps"));
        // the deployer warns when it's missing
        Files.createDirectories(base.resolve("environments"));
        Path work = base.resolve("work").resolve("joi");
        Files.createDirectories(work);

        // properties in start.d are not applied when started in-process, host, port and socket are passed as arguments
        Files.writeString(base.resolve("start.d").resolve("joi.ini"), "--module=" + MODULES + "\n", StandardCharsets.UTF_8);
        Files.writeString(base.resolve("webapps").resolve("ROOT.xml"), """
                <?xml version="1.0"?>
                <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure_10_0.dtd">
                <Configure class="org.eclipse.jetty.ee11.webapp.WebAppContext">
                  <Set name="contextPath">/</Set>
                  <Set name="war">%s</Set>
                  <Set name="tempDirectory">%s</Set>
                  <Set name="tempDirectoryPersistent">true</Set>
                </Configure>
                """.formatted(xml(war.toAbsolutePath().toString()), xml(work.toAbsolutePath().toString())), StandardCharsets.UTF_8);
        return base;
    }

    private static void startJetty(Path jettyHome, Path base, String host, int port, Path socket) throws Exception {
        URLClassLoader loader = new URLClassLoader(new URL[] {jettyHome.resolve("start.jar").toUri().toURL()},
                DesktopLauncher.class.getClassLoader());
        Class<?> main = loader.loadClass("org.eclipse.jetty.start.Main");
        Method mainMethod = main.getMethod("main", String[].class);
        String[] args = {
                "jetty.home=" + jettyHome.toAbsolutePath(),
                "jetty.base=" + base.toAbsolutePath(),
                "jetty.http.host=" + host,
                "jetty.http.port=" + port,
                "jetty.unixdomain.path=" + socket,
                "jetty.deploy.scanInterval=0",
        };
        // the annotation scanner reports duplicate classes of the WAR's jars in thousands of warnings on every start
        System.setProperty("org.eclipse.jetty.annotations.AnnotationParser.LEVEL", "ERROR");
        Thread.currentThread().setContextClassLoader(loader);
        // returns once the server is started, Jetty's threads keep the JVM running
        mainMethod.invoke(null, (Object) args);
    }

    private static boolean waitUntilReady(URI uri) {
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (isJoi(uri)) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static boolean isJoi(URI uri) {
        try {
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(5000);
            if (connection.getResponseCode() != 200) {
                return false;
            }
            try (InputStream in = connection.getInputStream()) {
                String page = new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8);
                return page.contains("<title>" + APP_NAME + "</title>");
            }
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether something accepts connections on this socket (a socket file stays behind when its server is killed). */
    private static boolean answers(Path socket) {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // the node
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Starts the node of this machine if none runs: the {@code JoiNode} program next to this one (the second launcher of the
     * installation, it has its own icon and, on macOS, its own place in the privacy settings of the microphone), else a JVM of
     * this runtime on the jars of the launcher's folder (the portable folder). It is told the socket and the address of the web
     * UI, nothing else: no server address, no token.
     */
    private synchronized void startNode() {
        try {
            Path home = nodeHome();
            if (nodeRunning(home)) {
                log("The Joi node already runs (" + home + ")");
                tell("The Joi node is already running");
                return;
            }
            if (node != null && node.isAlive()) {
                return;
            }
            List<String> command = nodeCommand();
            command.addAll(List.of("--local", socket.toString(), "--web", uri.toString().replaceAll("/$", ""),
                    "--joi-home-dir", home.toString()));
            Path log = desktopDir.resolve("logs").resolve("node.log");
            Files.createDirectories(log.getParent());
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(Redirect.appendTo(log.toFile()));
            node = builder.start();
            log("Started the Joi node: " + String.join(" ", command));
        } catch (IOException | RuntimeException e) {
            log("Cannot start the Joi node: " + e.getMessage());
            tell("The Joi node did not start: " + e.getMessage());
        }
    }

    private List<String> nodeCommand() throws IOException {
        String own = ProcessHandle.current().info().command().orElse(null);
        if (own != null) {
            Path ownPath = Path.of(own);
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            Path sibling = ownPath.resolveSibling("JoiNode" + (windows ? ".exe" : ""));
            if (!ownPath.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("java") && Files.isExecutable(sibling)) {
                return new ArrayList<>(List.of(sibling.toString()));
            }
        }
        // the portable folder: the node's jars are with the launcher's, the runtime is the one running
        Path appDir = launcherDirectory();
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            // JCEF on macOS reaches into AWT's internals
            command.addAll(List.of("--add-opens=java.desktop/sun.awt=ALL-UNNAMED", "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
                    "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED"));
        }
        command.addAll(List.of("-cp", appDir.resolve("*").toString(), NODE_MAIN));
        return command;
    }

    /**
     * The node's home: the same as the node resolves it ({@code JOI_HOME_DIR}, {@code JOI_CLIENT}, else {@code ~/.joi-client}); it
     * is passed to the node as a flag, so the lock this launcher looks at is the one the node takes.
     */
    private static Path nodeHome() {
        for (String name : new String[] {"JOI_HOME_DIR", "JOI_CLIENT"}) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return expand(value.strip());
            }
        }
        return Path.of(System.getProperty("user.home"), ".joi-client").toAbsolutePath().normalize();
    }

    private static Path expand(String path) {
        String home = System.getProperty("user.home");
        if (path.equals("~")) {
            return Path.of(home);
        }
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return Path.of(home, path.substring(2)).toAbsolutePath().normalize();
        }
        return Path.of(path).toAbsolutePath().normalize();
    }

    /** Whether a node holds the lock of its home (the file is locked for as long as the node runs, the system frees it at the end). */
    private static boolean nodeRunning(Path home) throws IOException {
        Path file = home.resolve(NODE_LOCK);
        if (!Files.exists(file)) {
            return false;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            try (FileLock probe = channel.tryLock()) {
                return probe == null;
            } catch (OverlappingFileLockException e) {
                return true;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // desktop integration
    // ------------------------------------------------------------------------------------------------------------

    private void showControls(String status) {
        if (!options.controls || GraphicsEnvironment.isHeadless()) {
            return;
        }
        EventQueue.invokeLater(() -> {
            try {
                if (SystemTray.isSupported()) {
                    showTray(status);
                } else {
                    showWindow(status);
                }
            } catch (Exception e) {
                log("Cannot show desktop controls: " + e.getMessage());
            }
        });
    }

    private void showTray(String status) throws AWTException {
        if (trayIcon == null) {
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Open " + APP_NAME + " in the browser");
            open.addActionListener(_ -> openBrowser());
            menu.add(open);
            if (options.node) {
                MenuItem nodeItem = new MenuItem("Open the " + APP_NAME + " node");
                nodeItem.addActionListener(_ -> new Thread(this::startNode, "start node").start());
                menu.add(nodeItem);
                CheckboxMenuItem autostart = new CheckboxMenuItem("Start the node with " + APP_NAME,
                        Boolean.parseBoolean(preferences.getProperty("node.autostart", "true")));
                autostart.addItemListener(_ -> {
                    preferences.setProperty("node.autostart", Boolean.toString(autostart.getState()));
                    savePreferences();
                });
                menu.add(autostart);
            }
            menu.addSeparator();
            MenuItem quit = new MenuItem("Quit " + APP_NAME);
            quit.addActionListener(_ -> quit());
            menu.add(quit);
            trayIcon = new TrayIcon(icon(64), status, menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(_ -> openBrowser());
            SystemTray.getSystemTray().add(trayIcon);
        }
        trayIcon.setToolTip(status);
    }

    /** Desktops without a system tray (some Linux environments) get a small window instead. */
    private void showWindow(String status) {
        if (fallbackWindow == null) {
            fallbackWindow = new Frame(APP_NAME);
            fallbackWindow.setIconImage(icon(64));
            fallbackWindow.setLayout(new BorderLayout(8, 8));
            Label label = new Label(status, Label.CENTER);
            label.setName("status");
            Panel buttons = new Panel(new FlowLayout());
            Button open = new Button("Open in browser");
            open.addActionListener(_ -> openBrowser());
            buttons.add(open);
            if (options.node) {
                Button nodeButton = new Button("Open the node");
                nodeButton.addActionListener(_ -> new Thread(this::startNode, "start node").start());
                buttons.add(nodeButton);
            }
            Button quit = new Button("Quit");
            quit.addActionListener(_ -> quit());
            buttons.add(quit);
            fallbackWindow.add(label, BorderLayout.CENTER);
            fallbackWindow.add(buttons, BorderLayout.SOUTH);
            fallbackWindow.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    quit();
                }
            });
            fallbackWindow.setSize(460, 140);
            fallbackWindow.setLocationRelativeTo(null);
            fallbackWindow.setVisible(true);
        }
        for (Component component : fallbackWindow.getComponents()) {
            if (component instanceof Label label) {
                label.setText(status);
            }
        }
    }

    private void tell(String message) {
        if (trayIcon != null) {
            trayIcon.displayMessage(APP_NAME, message, TrayIcon.MessageType.INFO);
        }
    }

    private void quit() {
        log("Quitting " + APP_NAME);
        Process started = node;
        if (started != null && started.isAlive()) {
            // the node this launcher started goes with it, one that the user started apart is left alone
            started.destroy();
        }
        // Jetty stops gracefully from its shutdown hook
        new Thread(() -> System.exit(0), "quit").start();
    }

    private void openBrowser() {
        if (uri == null) {
            return;
        }
        try {
            if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
                return;
            }
        } catch (Exception e) {
            log("Desktop browser integration failed: " + e.getMessage());
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command = os.contains("win") ? List.of("rundll32", "url.dll,FileProtocolHandler", uri.toString())
                : os.contains("mac") ? List.of("open", uri.toString())
                : List.of("xdg-open", uri.toString());
        try {
            new ProcessBuilder(command).inheritIO().start();
        } catch (IOException e) {
            log("Open " + uri + " in your browser");
        }
    }

    /** The application icon at {@code size} pixels: {@code joi.png} (in the launcher jar) scaled down smoothly, or a drawn tile. */
    static BufferedImage icon(int size) {
        try (InputStream in = DesktopLauncher.class.getResourceAsStream("/joi.png")) {
            if (in != null) {
                BufferedImage source = javax.imageio.ImageIO.read(in);
                if (source != null) {
                    BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = image.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    g.drawImage(source.getScaledInstance(size, size, Image.SCALE_AREA_AVERAGING), 0, 0, null);
                    g.dispose();
                    return image;
                }
            }
        } catch (IOException | RuntimeException _) {
            // fall back to the drawn icon
        }
        return fallbackIcon(size);
    }

    private static BufferedImage fallbackIcon(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x3b, 0x3f, 0x8f));
        g.fillRoundRect(0, 0, size, size, size / 4, size / 4);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) (size * 0.72)));
        FontMetrics metrics = g.getFontMetrics();
        String letter = "J";
        g.drawString(letter, (size - metrics.stringWidth(letter)) / 2, (size - metrics.getHeight()) / 2 + metrics.getAscent());
        g.dispose();
        return image;
    }

    // ------------------------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------------------------

    /** The folder of the launcher's jar, where jpackage puts every jar of the application (the node's too). */
    private static Path launcherDirectory() {
        try {
            Path location = Path.of(DesktopLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isDirectory(location) ? location : location.getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("The place of the launcher is not known: " + e.getMessage());
        }
    }

    /**
     * The {@code server/} directory: {@code -Djoi.serverDir}, or the nearest one next to the launcher jar's directory or one of its
     * parents (portable folder: {@code app/../server}, macOS app: {@code Contents/server}, Windows/Linux app images: the image
     * root or {@code lib/}).
     */
    private static Path serverDirectory() {
        String override = System.getProperty("joi.serverDir");
        if (override != null) {
            return Path.of(override);
        }
        Path dir = launcherDirectory();
        for (Path candidate = dir; candidate != null; candidate = candidate.getParent()) {
            Path server = candidate.resolve("server");
            if (Files.exists(server.resolve("jetty-home").resolve("start.jar"))) {
                return server;
            }
            if (candidate.getNameCount() < dir.getNameCount() - 3) {
                break;
            }
        }
        throw new IllegalStateException("Joi server files (server/jetty-home, server/joi.war) not found near " + dir);
    }

    /** The data folder: {@code --data}, else {@code -Djoi.data} / {@code JOI_DATA} (as the server reads it), else {@code ~/.joi}. */
    static Path dataDirectory(Options options) {
        String folder = options.data != null ? options.data.toString() : System.getProperty("joi.data", System.getenv("JOI_DATA"));
        if (folder == null || folder.isBlank()) {
            return Path.of(System.getProperty("user.home"), ".joi").toAbsolutePath().normalize();
        }
        return expand(folder.strip());
    }

    /**
     * Where the API's socket is: {@code <data>/desktop/api.sock}, or, when that path is too long for a socket, a folder of the
     * temporary directory named after the data folder. Whoever can open the socket is the user (the server gives them a token),
     * so the folder holding it is the user's alone.
     */
    static Path socketPath(Path desktopDir) throws IOException {
        Path preferred = desktopDir.resolve("api.sock");
        if (preferred.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_SOCKET_PATH) {
            return preferred;
        }
        String id;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(desktopDir.toString().getBytes(StandardCharsets.UTF_8));
            id = HexFormat.of().formatHex(hash, 0, 6);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        Path folder = Path.of(System.getProperty("java.io.tmpdir")).resolve("joi-" + System.getProperty("user.name", "user") + "-" + id);
        createPrivateDirectory(folder);
        Path fallback = folder.resolve("api.sock");
        if (fallback.toString().getBytes(StandardCharsets.UTF_8).length > MAX_SOCKET_PATH) {
            throw new IllegalStateException("No path for the API socket is short enough (" + fallback + "); use --data with a shorter folder");
        }
        return fallback;
    }

    /** A folder only its owner may open (where the file system has owners). */
    static void createPrivateDirectory(Path dir) throws IOException {
        Files.createDirectories(dir);
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException e) {
            // Windows: the folder is in the user's profile, which the others cannot open
        }
    }

    private void loadPreferences() {
        Path file = desktopDir.resolve("launcher.properties");
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                preferences.load(in);
            } catch (IOException e) {
                log("Cannot read " + file + ": " + e.getMessage());
            }
        }
    }

    private void savePreferences() {
        try (OutputStream out = Files.newOutputStream(desktopDir.resolve("launcher.properties"))) {
            preferences.store(out, "The desktop launcher of Joi");
        } catch (IOException e) {
            log("Cannot save the preferences: " + e.getMessage());
        }
    }

    private static void require(Path path, String what) {
        if (!Files.exists(path)) {
            throw new IllegalStateException("Bundled " + what + " not found: " + path);
        }
    }

    /**
     * A port is taken when something accepts connections on it. Binding a test socket would also report ports of a just stopped
     * instance (TCP TIME_WAIT) as taken, while Jetty binds them fine with SO_REUSEADDR.
     */
    static boolean isPortFree(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName(host), port), 500);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static int freePort(String host) throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName(host), 0));
            return socket.getLocalPort();
        }
    }

    private static URI uri(String host, int port) {
        return URI.create("http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + port + "/");
    }

    private static boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Desktop launches have no console: everything also goes to {@code logs/joi.log} (the previous run is kept as {@code joi.log.1}). */
    private static void redirectOutput(Path logDir) throws IOException {
        Files.createDirectories(logDir);
        Path log = logDir.resolve("joi.log");
        if (Files.exists(log)) {
            Files.move(log, logDir.resolve("joi.log.1"), StandardCopyOption.REPLACE_EXISTING);
        }
        OutputStream file = new BufferedOutputStream(Files.newOutputStream(log), 8192) {
            @Override
            public synchronized void write(byte[] b, int off, int len) throws IOException {
                super.write(b, off, len);
                flush();
            }
        };
        System.setOut(new PrintStream(new TeeOutputStream(System.out, file), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new TeeOutputStream(System.err, file), true, StandardCharsets.UTF_8));
    }

    private static void log(String message) {
        System.out.println(LocalDateTime.now().withNano(0) + " [launcher] " + message);
    }

    private static final class TeeOutputStream extends OutputStream {
        private final OutputStream first;
        private final OutputStream second;

        TeeOutputStream(OutputStream first, OutputStream second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            first.write(b);
            second.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            first.write(b, off, len);
            second.write(b, off, len);
        }

        @Override
        public synchronized void flush() throws IOException {
            first.flush();
            second.flush();
        }
    }

    static final class Options {
        static final String USAGE = """
                Usage: joi [options]
                  --port <port>     port of the web UI (default %d, a free one is picked when taken)
                  --host <address>  address of the web UI (default 127.0.0.1, others expose it to the network)
                  --data <dir>      Joi's data folder (default: JOI_DATA, else .joi in your home directory)
                  --no-browser      don't open the browser
                  --no-node         don't offer or start the node (the program with the avatar)
                  --no-tray         no tray icon / window, stop with Ctrl+C
                  --help            show this help

                The API of the nodes on this machine is a Unix domain socket in the data folder (desktop/api.sock); a node there needs
                no key and no login. The web UI has its login as the server has.
                """.formatted(DEFAULT_PORT);

        int port = DEFAULT_PORT;
        boolean portExplicit;
        String host = "127.0.0.1";
        Path data;
        boolean openBrowser = true;
        boolean controls = true;
        boolean node = true;
        boolean help;

        static Options parse(String[] args) {
            Options options = new Options();
            List<String> list = new ArrayList<>(List.of(args));
            for (int i = 0; i < list.size(); i++) {
                String arg = list.get(i);
                switch (arg) {
                    case "--port" -> {
                        try {
                            options.port = Integer.parseInt(value(list, ++i, arg));
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("Invalid port: " + list.get(i));
                        }
                        if (options.port < 1 || options.port > 65535) {
                            throw new IllegalArgumentException("Invalid port: " + options.port);
                        }
                        options.portExplicit = true;
                    }
                    case "--host" -> options.host = value(list, ++i, arg);
                    case "--data" -> options.data = Path.of(value(list, ++i, arg));
                    case "--no-browser" -> options.openBrowser = false;
                    case "--no-node" -> options.node = false;
                    case "--no-tray" -> options.controls = false;
                    case "--help", "-h" -> options.help = true;
                    // macOS passes a process serial number to apps started from Finder
                    default -> {
                        if (!arg.startsWith("-psn_")) {
                            throw new IllegalArgumentException("Unknown option: " + arg);
                        }
                    }
                }
            }
            return options;
        }

        private static String value(List<String> list, int i, String option) {
            if (i >= list.size()) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return list.get(i);
        }
    }
}
