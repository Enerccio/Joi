package com.github.enerccio.joi.node.fx;

import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import org.cef.CefClient;
import org.cef.browser.JoiOsrBrowser;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * A web page in a JavaFX pane: Chromium renders it off screen ({@link JoiOsrBrowser}) and each frame is copied into
 * the image shown here, at the screen's pixels. The browser is made once the pane is shown, at its size and the
 * screen's scale (made again when the scale changes: Chromium can't be told). It only shows: the mouse and the keys
 * don't reach the page.
 */
class ChromiumPane extends Region {

    private final ImageView view = new ImageView();
    private final CefClient client;
    private final String url;
    /** Made once the pane is in a window, at its size and the screen's scale; again when the scale changes. */
    private JoiOsrBrowser browser;
    /** The last frame from Chromium, waiting for the UI thread. */
    private final Object lock = new Object();
    private ByteBuffer staged;
    private int stagedWidth;
    private int stagedHeight;
    private boolean scheduled;
    private PixelBuffer<IntBuffer> pixels;
    private int lastWidth = -1;
    private int lastHeight = -1;
    private double lastScale = -1;

    ChromiumPane(CefClient client, String url) {
        getStyleClass().add("joi-chromium");
        setStyle("-fx-background-color: #111114;");
        view.setPreserveRatio(false);
        getChildren().add(view);
        this.client = client;
        this.url = url;
    }

    /** Saves the frame shown now as a PNG (for development). */
    void saveFrame(java.nio.file.Path file) {
        javafx.scene.image.Image image = view.getImage();
        if (image == null) {
            System.err.println("no frame yet");
            return;
        }
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            image.getPixelReader().getPixels(0, y, w, 1, PixelFormat.getIntArgbInstance(), row, 0, w);
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        try {
            javax.imageio.ImageIO.write(out, "png", file.toFile());
            System.err.println("frame " + w + "x" + h + " saved to " + file);
        } catch (java.io.IOException e) {
            System.err.println("frame not saved: " + e);
        }
    }

    /** Closes the page. */
    void close() {
        if (browser != null) {
            browser.close(true);
            browser = null;
        }
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double height = getHeight();
        view.relocate(0, 0);
        view.setFitWidth(width);
        view.setFitHeight(height);
        if (getScene() == null || getScene().getWindow() == null || !getScene().getWindow().isShowing()) {
            return;
        }
        double scale = getScene().getWindow().getOutputScaleX();
        int w = (int) Math.round(width);
        int h = (int) Math.round(height);
        if (w <= 0 || h <= 0 || w == lastWidth && h == lastHeight && scale == lastScale) {
            return;
        }
        try {
            if (browser == null || scale != lastScale) {
                close();
                browser = new JoiOsrBrowser(client, url, null, this::frame);
                browser.size(w, h, scale);
                browser.createImmediately();
                System.err.println("Chromium: opened " + url + " at " + w + "x" + h + ", scale " + scale);
            } else {
                browser.resize(w, h);
            }
        } catch (Throwable e) {
            System.err.println("Chromium: resize failed: " + e);
            e.printStackTrace();
        }
        lastWidth = w;
        lastHeight = h;
        lastScale = scale;
    }

    /** A frame from Chromium (on its thread): kept, and shown on the UI thread. */
    private boolean firstFrame = true;

    private long frames;

    private void frame(ByteBuffer bgra, int width, int height) {
        try {
            copy(bgra, width, height);
        } catch (Throwable e) {
            System.err.println("Chromium: frame failed: " + e);
            e.printStackTrace();
        }
    }

    private void copy(ByteBuffer bgra, int width, int height) {
        if (firstFrame) {
            firstFrame = false;
            System.err.println("Chromium: first frame " + width + "x" + height + " on " + Thread.currentThread().getName());
        }
        if (++frames % 300 == 0) {
            System.err.println("Chromium: " + frames + " frames, " + width + "x" + height);
        }
        synchronized (lock) {
            int size = width * height * 4;
            if (staged == null || staged.capacity() < size) {
                staged = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
            }
            staged.clear();
            ByteBuffer source = bgra.duplicate();
            source.rewind().limit(Math.min(source.capacity(), size));
            staged.put(source);
            staged.flip();
            stagedWidth = width;
            stagedHeight = height;
            if (scheduled) {
                return;
            }
            scheduled = true;
        }
        Platform.runLater(this::show);
    }

    private void show() {
        try {
            showNow();
        } catch (Throwable e) {
            System.err.println("Chromium: show failed: " + e);
            e.printStackTrace();
        }
    }

    private void showNow() {
        synchronized (lock) {
            scheduled = false;
            if (staged == null) {
                return;
            }
            if (pixels == null || pixels.getWidth() != stagedWidth || pixels.getHeight() != stagedHeight) {
                IntBuffer buffer = ByteBuffer.allocateDirect(stagedWidth * stagedHeight * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
                pixels = new PixelBuffer<>(stagedWidth, stagedHeight, buffer, PixelFormat.getIntArgbPreInstance());
                view.setImage(new WritableImage(pixels));
            }
            pixels.updateBuffer(buffer -> {
                IntBuffer target = buffer.getBuffer();
                target.clear();
                target.put(staged.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer());
                // JavaFX uploads from the buffer's position
                target.rewind();
                return new Rectangle2D(0, 0, stagedWidth, stagedHeight);
            });
        }
    }
}
