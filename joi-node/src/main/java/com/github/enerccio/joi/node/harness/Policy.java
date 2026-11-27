package com.github.enerccio.joi.node.harness;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The harness's policy, {@code <home>/policy.json}: what she may do with the files and the shell of this machine. Only the node's
 * own UI (or the user by hand) writes it; nothing the server sends reaches it. It is read again on every call, so a hand edit
 * applies at once. A list left out (null) is its default; an empty list is none.
 */
public final class Policy {

    /** The file's name in the node's home. */
    public static final String FILE = "policy.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** {@code on}: she may read, list and write files; {@code off} (null too): no file tools. */
    public String files;
    /** {@code ask}: she may run commands, each one asked about; {@code off} (null too): no shell tool. */
    public String shell;
    /**
     * Whether she may look at pictures ({@code view_image}, with files on): {@code allow} (anywhere but the denied paths, never asked),
     * {@code folders} (silent in the user's folders, asked elsewhere; also when null), {@code ask} (each one asked) or {@code off}.
     */
    public String images;
    /** The user's folders: reads and lists inside them are silent, everything else is asked about. */
    public List<String> roots;
    /** Never read nor written (credentials, browser profiles). */
    public List<String> denied;
    /** Readable, never written (the system's folders). */
    public List<String> deniedWrites;
    /** Programs a command may not name. */
    public List<String> deniedCommands;
    /** {@code on}: she may use the browser companion (the Firefox extension); {@code off} (null too): no browser tools. */
    public String browser;
    /** The tier of each browser tool ({@code allow}, {@code asked}, {@code ask}, {@code off}); a tool left out has its default. */
    public Map<String, String> browserTools;
    /** Sites the browser tools never work on, whatever the user granted in Firefox (a host and its subdomains). */
    public List<String> deniedSites;

    public boolean filesOn() {
        return "on".equalsIgnoreCase(files == null ? null : files.strip());
    }

    /** The tier of {@code view_image}: {@code allow}, {@code folders} (the default), {@code ask} or {@code off} (an unknown word too). */
    public String imagesTier() {
        if (images == null || images.isBlank()) {
            return "folders";
        }
        String t = images.strip().toLowerCase(java.util.Locale.ROOT);
        return List.of("allow", "folders", "ask", "off").contains(t) ? t : "off";
    }

    public boolean browserOn() {
        return "on".equalsIgnoreCase(browser == null ? null : browser.strip());
    }

    /** A browser tool's tier: the policy's, else the default (an unknown word is off). */
    public String browserTier(String tool, String otherwise) {
        String tier = browserTools == null ? null : browserTools.get(tool);
        if (tier == null || tier.isBlank()) {
            return otherwise;
        }
        String t = tier.strip().toLowerCase(java.util.Locale.ROOT);
        return List.of("allow", "asked", "ask", "off").contains(t) ? t : "off";
    }

    public List<String> deniedSites() {
        return deniedSites == null ? DEFAULT_DENIED_SITES : deniedSites.stream().filter(r -> r != null && !r.isBlank()).toList();
    }

    public boolean shellOn() {
        return "ask".equalsIgnoreCase(shell == null ? null : shell.strip());
    }

    public List<String> roots() {
        return roots == null ? List.of() : roots.stream().filter(r -> r != null && !r.isBlank()).toList();
    }

    public List<String> denied() {
        return denied == null ? DEFAULT_DENIED : denied.stream().filter(r -> r != null && !r.isBlank()).toList();
    }

    public List<String> deniedWrites() {
        return deniedWrites == null ? DEFAULT_DENIED_WRITES : deniedWrites.stream().filter(r -> r != null && !r.isBlank()).toList();
    }

    public List<String> deniedCommands() {
        return deniedCommands == null ? DEFAULT_DENIED_COMMANDS : deniedCommands.stream().filter(r -> r != null && !r.isBlank()).toList();
    }

    /** The paths that are never read nor written by default: credentials, keychains, browser profiles. */
    public static final List<String> DEFAULT_DENIED = List.of(
            "~/.ssh", "~/.gnupg", "~/.aws", "~/.azure", "~/.config/gcloud", "~/.kube", "~/.docker",
            "~/.netrc", "~/.git-credentials", "~/.config/gh", "~/.npmrc", "~/.pypirc",
            "~/.m2/settings.xml", "~/.m2/settings-security.xml", "~/.password-store",
            "~/Library/Keychains", "~/Library/Cookies", "~/Library/Safari",
            "~/Library/Application Support/Google/Chrome", "~/Library/Application Support/Chromium",
            "~/Library/Application Support/Firefox", "~/Library/Application Support/BraveSoftware",
            "~/Library/Application Support/Microsoft Edge",
            "~/.mozilla", "~/.config/google-chrome", "~/.config/chromium", "~/.config/BraveSoftware", "~/.config/microsoft-edge",
            "~/AppData/Local/Google/Chrome", "~/AppData/Roaming/Mozilla", "~/AppData/Local/Microsoft/Edge",
            "~/AppData/Local/BraveSoftware");

    /** The system's folders: readable, never written. */
    public static final List<String> DEFAULT_DENIED_WRITES = List.of(
            "/System", "/Library", "/usr", "/bin", "/sbin", "/etc", "/private/etc", "/private/var/db", "/private/var/root", "/opt/homebrew/bin", "/boot", "/lib", "/lib64",
            "C:/Windows", "C:/Program Files", "C:/Program Files (x86)");

    /** The sites the browser tools never work on by default: mail, password managers, payments. Sign-in pages follow the grant. */
    public static final List<String> DEFAULT_DENIED_SITES = List.of(
            "mail.google.com", "outlook.live.com", "outlook.office.com", "outlook.office365.com", "mail.proton.me", "app.fastmail.com",
            "mail.yahoo.com", "icloud.com", "vault.bitwarden.com", "1password.com", "lastpass.com", "keepersecurity.com",
            "paypal.com", "revolut.com", "wise.com", "dashboard.stripe.com");

    /** Programs a command may not name. */
    public static final List<String> DEFAULT_DENIED_COMMANDS = List.of("sudo", "su", "doas", "pkexec", "runas");

    /** Nothing on: the policy of a node that never had one, and of a file that cannot be read. */
    public static Policy defaults() {
        Policy policy = new Policy();
        policy.files = "off";
        policy.shell = "off";
        policy.browser = "off";
        policy.roots = new ArrayList<>();
        return policy;
    }

    /** The policy in the home; its defaults when there is no file, and nothing on when it cannot be read. */
    public static Policy load(Path home) {
        Path file = home.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return defaults();
        }
        try {
            Policy policy = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Policy.class);
            return policy == null ? defaults() : policy;
        } catch (IOException | JsonParseException e) {
            // a policy that cannot be read allows nothing
            return defaults();
        }
    }

    /** Writes the policy into the home, whole, through a temporary file. */
    public void save(Path home) throws IOException {
        Files.createDirectories(home);
        Path file = home.resolve(FILE);
        Path temp = Files.createTempFile(home, "policy", ".tmp");
        try {
            Files.writeString(temp, GSON.toJson(this), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
