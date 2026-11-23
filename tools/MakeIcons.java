import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/**
 * Draws the icon of the desktop app and writes it in the three formats jpackage wants: joi.png (Linux, and the tray icon the
 * launcher reads from its JAR), joi.ico (Windows) and joi.icns (macOS). The icon is a placeholder: a round tile with a ring and a "J", the
 * same drawing as the SVG that is the web page's favicon (joi-server/.../webapp/icons/joi.svg, the master). Run this
 * again after a change of the SVG, with the same shapes written here.
 *
 * <pre>java tools/MakeIcons.java joi-desktop/src/main/icons</pre>
 */
public final class MakeIcons {

    public static void main(String[] args) throws IOException {
        System.setProperty("java.awt.headless", "true");
        Path out = Path.of(args.length > 0 ? args[0] : ".");
        Files.createDirectories(out);

        byte[] png1024 = png(draw(1024));
        byte[] png512 = png(draw(512));
        byte[] png256 = png(draw(256));
        byte[] png128 = png(draw(128));
        byte[] png64 = png(draw(64));
        byte[] png32 = png(draw(32));

        Files.write(out.resolve("joi.png"), png512);
        Files.write(out.resolve("joi.ico"), ico(new byte[][] {png256, png64, png32}, new int[] {256, 64, 32}));
        Files.write(out.resolve("joi.icns"), icns(new String[] {"ic07", "ic08", "ic09", "ic10"},
                new byte[][] {png128, png256, png512, png1024}));
        System.out.println("Icons written to " + out.toAbsolutePath());
    }

    private static BufferedImage draw(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        // the shapes of joi-server/src/main/webapp/icons/joi.svg, the master: change both together
        double k = size / 1024.0;
        g.scale(k, k);
        g.setPaint(new GradientPaint(0, 0, new Color(0x5b, 0x4b, 0xd6), 0, 1024, new Color(0x1f, 0x9d, 0xc7)));
        g.fill(new RoundRectangle2D.Double(61, 61, 902, 902, 504, 504));
        // a soft ring behind the letter, as the glow of a hologram
        g.setColor(new Color(255, 255, 255, 69));
        g.setStroke(new BasicStroke(36f));
        g.draw(new Ellipse2D.Double(512 - 297, 512 - 297, 594, 594));
        // the "J": a stem and a half circle, one round stroke
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(110f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D j = new Path2D.Double();
        j.moveTo(640, 280);
        j.lineTo(640, 610);
        j.append(new Arc2D.Double(380, 480, 260, 260, 0, -180, Arc2D.OPEN), true);
        g.draw(j);
        g.dispose();
        return image;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** An ICO whose images are PNGs (Windows Vista and later read them). */
    private static byte[] ico(byte[][] images, int[] sizes) {
        int header = 6 + 16 * images.length;
        int total = header;
        for (byte[] image : images) {
            total += image.length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) 0).putShort((short) 1).putShort((short) images.length);
        int offset = header;
        for (int i = 0; i < images.length; i++) {
            buffer.put((byte) (sizes[i] >= 256 ? 0 : sizes[i])).put((byte) (sizes[i] >= 256 ? 0 : sizes[i]));
            buffer.put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32);
            buffer.putInt(images[i].length).putInt(offset);
            offset += images[i].length;
        }
        for (byte[] image : images) {
            buffer.put(image);
        }
        return buffer.array();
    }

    /** An ICNS whose images are PNGs: ic07 128, ic08 256, ic09 512, ic10 1024 pixels. */
    private static byte[] icns(String[] types, byte[][] images) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(body);
        for (int i = 0; i < images.length; i++) {
            data.writeBytes(types[i]);
            data.writeInt(8 + images[i].length);
            data.write(images[i]);
        }
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(file);
        out.writeBytes("icns");
        out.writeInt(8 + body.size());
        out.write(body.toByteArray());
        return file.toByteArray();
    }
}
