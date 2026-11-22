package com.github.enerccio.joi.node.local;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A WebSocket client (RFC 6455) over a Unix domain socket, as the JDK's {@link WebSocket}: the node WebSocket of a server on the
 * node's own machine ({@link UnixHttp} says why the JDK's own client is not used). It sends text and close frames (masked, as a client
 * must) and answers pings; it reads text frames (a message in fragments is put together, and handed over whole), pings, pongs and
 * the close. Binary messages and extensions are not used by the node protocol: a binary message is handed over to the listener and
 * the extensions are not asked for. The listener is called on the thread that reads, one frame at a time, so {@link #request} has nothing
 * to wait for and does nothing.
 */
public final class UnixWebSocket implements WebSocket {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final long MAX_MESSAGE = 64L * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final int CONTINUATION = 0, TEXT = 1, BINARY = 2, CLOSE = 8, PING = 9, PONG = 10;

    private final SocketChannel channel;
    private final Listener listener;
    private final OutputStream out;
    private final Object writing = new Object();
    private final AtomicBoolean outputClosed = new AtomicBoolean();
    private final AtomicBoolean inputClosed = new AtomicBoolean();
    private final AtomicBoolean told = new AtomicBoolean();
    /** The message being sent in parts: its next frame continues it. */
    private boolean sendingParts;

    private UnixWebSocket(SocketChannel channel, Listener listener) {
        this.channel = channel;
        this.listener = listener;
        this.out = Channels.newOutputStream(channel);
    }

    /**
     * Connects and does the handshake, then reads on a thread of its own.
     *
     * @param socket   the Unix domain socket of the server
     * @param uri      the URI of the WebSocket ({@code ws://localhost/api/v1/node}); only its path and query are used
     * @param headers  the headers of the handshake ({@code Authorization}...)
     * @param listener told what comes; {@code onOpen} first
     * @return the open WebSocket, or the failure (the socket cannot be reached, the server did not switch protocols)
     */
    public static CompletableFuture<WebSocket> connectAsync(Path socket, URI uri, Map<String, String> headers, Listener listener) {
        CompletableFuture<WebSocket> result = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            SocketChannel channel = null;
            try {
                channel = UnixHttp.connect(socket);
                InputStream in = new BufferedInputStream(Channels.newInputStream(channel), 64 * 1024);
                handshake(channel, in, uri, headers);
                UnixWebSocket webSocket = new UnixWebSocket(channel, listener);
                result.complete(webSocket);
                webSocket.read(in);
            } catch (IOException | RuntimeException e) {
                if (channel != null) {
                    UnixHttp.close(channel);
                }
                // before the handshake is done the caller hears of it; after, the listener does (read)
                result.completeExceptionally(e);
            }
        }, "Joi node socket");
        thread.setDaemon(true);
        thread.start();
        return result;
    }

    private static void handshake(SocketChannel channel, InputStream in, URI uri, Map<String, String> headers) throws IOException {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }
        StringBuilder head = new StringBuilder();
        head.append("GET ").append(path).append(" HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n");
        head.append("Sec-WebSocket-Key: ").append(key).append("\r\nSec-WebSocket-Version: 13\r\n");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            head.append(UnixHttp.token(header.getKey())).append(": ").append(UnixHttp.value(header.getValue())).append("\r\n");
        }
        head.append("\r\n");
        OutputStream out = Channels.newOutputStream(channel);
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        UnixHttp.Head answer = UnixHttp.readHead(in);
        if (answer.status() != 101) {
            throw new IOException("The server did not switch to a WebSocket: HTTP " + answer.status());
        }
        try {
            String expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1)));
            if (!expected.equals(answer.headers().get("sec-websocket-accept"))) {
                throw new IOException("The answer of the server to the WebSocket handshake is not valid");
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // reading
    // ----------------------------------------------------------------------------------------------------------------

    private void read(InputStream stream) {
        DataInputStream in = new DataInputStream(stream);
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        boolean binary = false;
        try {
            listener.onOpen(this);
            while (true) {
                int first = in.readUnsignedByte();
                int second = in.readUnsignedByte();
                boolean fin = (first & 0x80) != 0;
                int opcode = first & 0x0F;
                if ((first & 0x70) != 0) {
                    throw new IOException("The server used a WebSocket extension that was not asked for");
                }
                long length = second & 0x7F;
                if (length == 126) {
                    length = in.readUnsignedShort();
                } else if (length == 127) {
                    length = in.readLong();
                    if (length < 0) {
                        throw new IOException("A frame of the server is too long");
                    }
                }
                boolean masked = (second & 0x80) != 0;
                if (masked) {
                    throw new IOException("The server masked a frame");
                }
                boolean control = opcode >= CLOSE;
                if (control && (!fin || length > 125)) {
                    throw new IOException("A control frame of the server is not valid");
                }
                if (length > MAX_MESSAGE || message.size() + length > MAX_MESSAGE) {
                    throw new IOException("A message of the server is longer than " + MAX_MESSAGE + " bytes");
                }
                byte[] payload = new byte[(int) length];
                in.readFully(payload);
                switch (opcode) {
                    case TEXT, BINARY, CONTINUATION -> {
                        if (opcode != CONTINUATION) {
                            if (message.size() > 0) {
                                throw new IOException("A new message began before the last one ended");
                            }
                            binary = opcode == BINARY;
                        }
                        message.write(payload);
                        if (fin) {
                            byte[] whole = message.toByteArray();
                            message.reset();
                            if (binary) {
                                listener.onBinary(this, ByteBuffer.wrap(whole), true);
                            } else {
                                listener.onText(this, decode(whole), true);
                            }
                        }
                    }
                    case PING -> {
                        sendFrame(PONG, payload, true);
                        listener.onPing(this, ByteBuffer.wrap(payload));
                    }
                    case PONG -> listener.onPong(this, ByteBuffer.wrap(payload));
                    case CLOSE -> {
                        inputClosed.set(true);
                        int code = payload.length >= 2 ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : 1005;
                        String reason = payload.length > 2 ? new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8) : "";
                        if (outputClosed.compareAndSet(false, true)) {
                            // the answer to the close of the other end: the same code
                            try {
                                sendFrame(CLOSE, payload.length >= 2 ? new byte[]{payload[0], payload[1]} : new byte[0], true);
                            } catch (IOException e) {
                                // it is gone already
                            }
                        }
                        UnixHttp.close(channel);
                        if (told.compareAndSet(false, true)) {
                            listener.onClose(this, code, reason);
                        }
                        return;
                    }
                    default -> throw new IOException("The server sent a frame of the kind " + opcode);
                }
            }
        } catch (IOException | RuntimeException e) {
            inputClosed.set(true);
            outputClosed.set(true);
            UnixHttp.close(channel);
            if (told.compareAndSet(false, true)) {
                listener.onError(this, e instanceof EOFException ? new IOException("The connection to the server closed") : e);
            }
        }
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    // ----------------------------------------------------------------------------------------------------------------
    // writing
    // ----------------------------------------------------------------------------------------------------------------

    private void sendFrame(int opcode, byte[] payload, boolean fin) throws IOException {
        byte[] header = new byte[14];
        int n = 0;
        header[n++] = (byte) ((fin ? 0x80 : 0) | opcode);
        if (payload.length < 126) {
            header[n++] = (byte) (0x80 | payload.length);
        } else if (payload.length <= 0xFFFF) {
            header[n++] = (byte) (0x80 | 126);
            header[n++] = (byte) (payload.length >> 8);
            header[n++] = (byte) payload.length;
        } else {
            header[n++] = (byte) (0x80 | 127);
            long length = payload.length;
            for (int shift = 56; shift >= 0; shift -= 8) {
                header[n++] = (byte) (length >> shift);
            }
        }
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask);
        System.arraycopy(mask, 0, header, n, 4);
        n += 4;
        byte[] masked = new byte[payload.length];
        for (int i = 0; i < payload.length; i++) {
            masked[i] = (byte) (payload[i] ^ mask[i & 3]);
        }
        synchronized (writing) {
            out.write(header, 0, n);
            out.write(masked);
            out.flush();
        }
    }

    private CompletableFuture<WebSocket> send(int opcode, byte[] payload, boolean fin) {
        try {
            if (outputClosed.get()) {
                throw new IllegalStateException("The output is closed");
            }
            sendFrame(opcode, payload, fin);
            return CompletableFuture.completedFuture(this);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        } catch (IllegalStateException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
        synchronized (writing) {
            int opcode = sendingParts ? CONTINUATION : TEXT;
            sendingParts = !last;
            return send(opcode, data.toString().getBytes(StandardCharsets.UTF_8), last);
        }
    }

    @Override
    public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
        synchronized (writing) {
            int opcode = sendingParts ? CONTINUATION : BINARY;
            sendingParts = !last;
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            return send(opcode, bytes, last);
        }
    }

    @Override
    public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
        byte[] bytes = new byte[message.remaining()];
        message.get(bytes);
        return send(PING, bytes, true);
    }

    @Override
    public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
        byte[] bytes = new byte[message.remaining()];
        message.get(bytes);
        return send(PONG, bytes, true);
    }

    @Override
    public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
        if (!outputClosed.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(this);
        }
        byte[] text = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(text.length, 123);
        byte[] payload = new byte[2 + length];
        payload[0] = (byte) (statusCode >> 8);
        payload[1] = (byte) statusCode;
        System.arraycopy(text, 0, payload, 2, length);
        try {
            sendFrame(CLOSE, payload, true);
        } catch (IOException e) {
            UnixHttp.close(channel);
            return CompletableFuture.failedFuture(e);
        }
        // the other end answers with its close frame, which ends the reading; if it does not, the connection is cut
        Thread cut = new Thread(() -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                return;
            }
            UnixHttp.close(channel);
        }, "Joi node socket close");
        cut.setDaemon(true);
        cut.start();
        return CompletableFuture.completedFuture(this);
    }

    @Override
    public void request(long n) {
        // frames are handed over as they come
    }

    @Override
    public String getSubprotocol() {
        return "";
    }

    @Override
    public boolean isOutputClosed() {
        return outputClosed.get();
    }

    @Override
    public boolean isInputClosed() {
        return inputClosed.get();
    }

    @Override
    public void abort() {
        outputClosed.set(true);
        inputClosed.set(true);
        UnixHttp.close(channel);
    }
}
