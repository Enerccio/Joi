package org.cef.browser;

import org.cef.CefClient;
import org.cef.callback.CefDragData;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefScreenInfo;

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * A Chromium browser rendered off screen into frames of pixels for the node's JavaFX pane (JCEF's own off-screen
 * browser draws into a JOGL canvas of AWT, which JavaFX can't hold). In JCEF's package because its base class is
 * package-private, as JetBrains' {@code CefBrowserOsrWithHandler}. Used through {@code node.fx.ChromiumPane}.
 */
public class JoiOsrBrowser extends CefBrowser_N implements CefRenderHandler {

    /** Takes a frame: BGRA pixels, valid only during the call. */
    public interface Frames {
        void paint(ByteBuffer bgra, int width, int height);
    }

    private final Frames frames;
    private volatile Rectangle view = new Rectangle(0, 0, 800, 600);
    private volatile double scale = 1;
    private volatile Point screen = new Point();

    public JoiOsrBrowser(CefClient client, String url, CefRequestContext context, Frames frames) {
        super(client, url, context, null, null, null);
        this.frames = frames;
    }

    /** The size of the view in points, and the pixels per point, before the browser is created. */
    public void size(int width, int height, double scale) {
        this.view = new Rectangle(0, 0, Math.max(1, width), Math.max(1, height));
        this.scale = scale;
    }

    /** A new size of the view in points (the scale stays: Chromium can't be told it changed). */
    public void resize(int width, int height) {
        this.view = new Rectangle(0, 0, Math.max(1, width), Math.max(1, height));
        wasResized(view.width, view.height);
        invalidate();
    }

    /** Where the view's corner is on the screen, in points. */
    public void moved(int x, int y) {
        screen = new Point(x, y);
    }

    @Override
    public void createImmediately() {
        createBrowser(getClient(), 0, getUrl(), true, false, null, getRequestContext());
    }

    @Override
    public Component getUIComponent() {
        return null;
    }

    @Override
    public CefRenderHandler getRenderHandler() {
        return this;
    }

    @Override
    protected CefBrowser_N createDevToolsBrowser(CefClient client, String url, CefRequestContext context, CefBrowser_N parent,
                                                 Point inspectAt) {
        return null;
    }

    @Override
    public CompletableFuture<BufferedImage> createScreenshot(boolean nativeResolution) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("no screenshots off screen"));
    }

    // ----------------------------------------------------------------------------------------------------------------
    // the render handler

    @Override
    public Rectangle getViewRect(CefBrowser browser) {
        return view;
    }

    @Override
    public boolean getScreenInfo(CefBrowser browser, CefScreenInfo info) {
        try {
            info.Set(scale, 32, 8, false, view, view);
            return true;
        } catch (Throwable e) {
            // an exception left in a call from Chromium's native code is lost: told here
            System.err.println("Chromium: getScreenInfo failed: " + e);
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public Point getScreenPoint(CefBrowser browser, Point viewPoint) {
        Point point = new Point(screen);
        point.translate(viewPoint.x, viewPoint.y);
        return point;
    }

    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {
    }

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {
    }

    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        if (!popup) {
            try {
                frames.paint(buffer, width, height);
            } catch (Throwable e) {
                System.err.println("Chromium: onPaint failed: " + e);
                e.printStackTrace();
            }
        }
    }

    @Override
    public void addOnPaintListener(Consumer<CefPaintEvent> listener) {
    }

    @Override
    public void setOnPaintListener(Consumer<CefPaintEvent> listener) {
    }

    @Override
    public void removeOnPaintListener(Consumer<CefPaintEvent> listener) {
    }

    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        return true;
    }

    @Override
    public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
        return false;
    }

    @Override
    public void updateDragCursor(CefBrowser browser, int operation) {
    }
}
