package com.github.enerccio.joi.node.browser;

import com.github.enerccio.joi.node.NodeSettings;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * The native messaging host of the browser companion: Firefox starts it for the extension (only the extension its manifest names,
 * {@link BrowserHost}) and talks to it over stdin and stdout, each message a 4-byte length in the machine's byte order and then
 * JSON. It relays them to the running node over {@link BrowserLink}'s socket in the node's home, a line each, and back. While no
 * node runs it tells the extension so ({@code {"type":"node","state":"absent"}}) and tries again every few seconds; when it
 * connects it says {@code connected}, and the extension says hello again. It ends when Firefox closes stdin.
 * <p>
 * {@code java -cp ... NativeHost [--joi-home-dir <home>] [firefox's own arguments]}
 */
public final class NativeHost {

    /** The most bytes of one message to the extension (Firefox's limit is 1 MB). */
    static final int MAX_TO_BROWSER = 1024 * 1024;
    /** The most bytes of one message from the extension that are relayed. */
    static final int MAX_FROM_BROWSER = 64 * 1024 * 1024;
    static final long RETRY_MS = 3000;

    private final Path socket;
    private final InputStream in;
    private final OutputStream out;
    private volatile SocketChannel node;
    /** Firefox closed the port: the node's connection closes and no more tries are made. */
    private volatile boolean stopped;

    NativeHost(Path socket, InputStream in, OutputStream out) {
        this.socket = socket;
        this.in = in;
        this.out = out;
    }

    public static void main(String[] args) {
        // nothing but messages may reach stdout: Firefox reads it
        PrintStream stdout = System.out;
        System.setOut(System.err);
        Path home = NodeSettings.resolve(args, System.getenv(), System.getProperty("user.home"));
        NativeHost host = new NativeHost(home.resolve(BrowserLink.SOCKET), System.in, stdout);
        Thread.ofPlatform().daemon().name("Joi native host to node").start(host::toBrowser);
        host.fromBrowser();
        System.exit(0);
    }

    /** Reads the extension's messages until Firefox closes stdin, and relays each to the node (dropped while there is none). */
    void fromBrowser() {
        DataInputStream data = new DataInputStream(in);
        try {
            while (true) {
                byte[] message = read(data);
                SocketChannel to = node;
                if (to != null) {
                    try {
                        writeLine(to, new String(message, StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        // the node went away: the other thread sees it
                    }
                }
            }
        } catch (IOException e) {
            // Firefox closed the port
        } finally {
            stopped = true;
            SocketChannel to = node;
            if (to != null) {
                try {
                    to.close();
                } catch (IOException e) {
                    // closed anyway
                }
            }
        }
    }

    /** Connects to the node, again and again, and relays its lines to the extension. */
    void toBrowser() {
        String said = null;
        while (!stopped) {
            try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                channel.connect(UnixDomainSocketAddress.of(socket));
                node = channel;
                if (stopped) {
                    return;
                }
                said = state("connected", said);
                BufferedReader lines = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8));
                for (String line; (line = lines.readLine()) != null; ) {
                    if (!line.isBlank()) {
                        write(line.getBytes(StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException e) {
                // no node now
            } finally {
                node = null;
            }
            if (stopped) {
                return;
            }
            try {
                said = state("absent", said);
                Thread.sleep(RETRY_MS);
            } catch (IOException | InterruptedException e) {
                return;
            }
        }
    }

    private String state(String state, String said) throws IOException {
        if (!state.equals(said)) {
            JsonObject message = new JsonObject();
            message.addProperty("type", "node");
            message.addProperty("state", state);
            write(message.toString().getBytes(StandardCharsets.UTF_8));
        }
        return state;
    }

    private static void writeLine(SocketChannel channel, String json) throws IOException {
        // a line each: JSON from the extension has no raw line break, but one is never let through
        byte[] bytes = (json.replace("\n", " ").replace("\r", " ") + "\n").getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        synchronized (channel) {
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }
    }

    /** One message from the extension. */
    static byte[] read(DataInputStream data) throws IOException {
        byte[] length = new byte[4];
        data.readFully(length);
        int n = ByteBuffer.wrap(length).order(ByteOrder.nativeOrder()).getInt();
        if (n < 0 || n > MAX_FROM_BROWSER) {
            throw new EOFException("A message of " + n + " bytes");
        }
        byte[] message = new byte[n];
        data.readFully(message);
        return message;
    }

    /** One message to the extension; one too long for Firefox is replaced by an error the extension can show. */
    synchronized void write(byte[] message) throws IOException {
        byte[] sent = message;
        if (sent.length > MAX_TO_BROWSER) {
            sent = "{\"type\":\"error\",\"error\":\"A message too long for Firefox was dropped\"}".getBytes(StandardCharsets.UTF_8);
        }
        out.write(frame(sent));
        out.flush();
    }

    static byte[] frame(byte[] message) {
        ByteArrayOutputStream framed = new ByteArrayOutputStream(message.length + 4);
        framed.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(message.length).array());
        framed.writeBytes(message);
        return framed.toByteArray();
    }
}
