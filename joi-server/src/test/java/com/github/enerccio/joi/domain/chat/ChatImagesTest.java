package com.github.enerccio.joi.domain.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the server does with an image before it keeps it: reads it, scales it, turns it upright, writes the JPEG. */
class ChatImagesTest {

    @TempDir
    File folder;

    private File file(String name, BufferedImage image, String format) throws Exception {
        File file = new File(folder, name);
        ImageIO.write(image, format, file);
        return file;
    }

    private static BufferedImage image(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        return image;
    }

    @Test
    void aLargeImageIsScaledToTheLimitAndKeptAsJpeg() throws Exception {
        File in = file("big.png", image(4000, 3000, Color.RED), "png");
        File out = new File(folder, "out.jpg");

        ChatImages.Info info = ChatImages.normalize(in, out);

        assertThat(info.width()).isEqualTo(ChatImages.MAX_SIDE);
        assertThat(info.height()).isEqualTo(1176);
        assertThat(info.size()).isEqualTo(out.length());
        assertThat(ChatImages.mimeType(out)).isEqualTo("image/jpeg");
        BufferedImage read = ImageIO.read(out);
        assertThat(read.getWidth()).isEqualTo(1568);
        assertThat(new Color(read.getRGB(10, 10)).getRed()).isGreaterThan(200);
    }

    @Test
    void aSmallImageKeepsItsSizeAndTransparencyBecomesWhite() throws Exception {
        BufferedImage transparent = new BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB);
        File in = file("small.png", transparent, "png");
        File out = new File(folder, "out.jpg");

        ChatImages.Info info = ChatImages.normalize(in, out);

        assertThat(info.width()).isEqualTo(40);
        assertThat(info.height()).isEqualTo(30);
        assertThat(new Color(ImageIO.read(out).getRGB(5, 5)).getBlue()).isGreaterThan(240);
    }

    @Test
    void theSameImageGivesTheSameBytesEveryTime() throws Exception {
        // the model's cache of the prompt only keeps what is byte for byte the same
        File in = file("a.png", image(900, 700, Color.BLUE), "png");
        File first = new File(folder, "1.jpg");
        File second = new File(folder, "2.jpg");
        ChatImages.normalize(in, first);
        ChatImages.normalize(in, second);
        assertThat(Files.readAllBytes(first.toPath())).isEqualTo(Files.readAllBytes(second.toPath()));
    }

    @Test
    void whatIsNoImageIsRefusedByItsContentNotByItsName() throws Exception {
        File text = new File(folder, "photo.jpg");
        Files.writeString(text.toPath(), "this is text");
        assertThatThrownBy(() -> ChatImages.normalize(text, new File(folder, "o.jpg"))).isInstanceOf(ChatImages.BadImageException.class);
        assertThat(new File(folder, "o.jpg")).doesNotExist();
    }

    @Test
    void aFileLargerThanTheLimitIsRefusedForItsSize() throws Exception {
        File big = new File(folder, "big.bin");
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(big, "rw")) {
            f.setLength(ChatImages.MAX_BYTES + 1);
        }
        assertThatThrownBy(() -> ChatImages.normalize(big, new File(folder, "o.jpg")))
                .isInstanceOfSatisfying(ChatImages.BadImageException.class, e -> assertThat(e.tooLarge()).isTrue());
    }

    @Test
    void anImageWithTooManyPixelsIsRefusedBeforeItIsDecoded() throws Exception {
        // 9000 x 9000 is 81 megapixels; a flat PNG of it is small
        File in = file("huge.png", new BufferedImage(9000, 9000, BufferedImage.TYPE_BYTE_BINARY), "png");
        assertThatThrownBy(() -> ChatImages.normalize(in, new File(folder, "o.jpg")))
                .isInstanceOfSatisfying(ChatImages.BadImageException.class, e -> assertThat(e.tooLarge()).isFalse());
    }

    @Test
    void theOrientationOfAPhotoTurnsItUpright() {
        // 3 wide, 2 high; the corner (0,0) is red, the others white
        BufferedImage source = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                source.setRGB(x, y, Color.WHITE.getRGB());
            }
        }
        source.setRGB(0, 0, Color.RED.getRGB());
        // 6: the photo was taken turned a quarter, rotate 90 clockwise: the top left goes to the top right of a 2 x 3 image
        BufferedImage six = ChatImages.turned(source, 6);
        assertThat(six.getWidth()).isEqualTo(2);
        assertThat(six.getHeight()).isEqualTo(3);
        assertThat(six.getRGB(1, 0)).isEqualTo(Color.RED.getRGB());
        // 3: half a turn, to the bottom right
        BufferedImage three = ChatImages.turned(source, 3);
        assertThat(three.getRGB(2, 1)).isEqualTo(Color.RED.getRGB());
        // 8: a quarter the other way, to the bottom left of the 2 x 3 image
        BufferedImage eight = ChatImages.turned(source, 8);
        assertThat(eight.getRGB(0, 2)).isEqualTo(Color.RED.getRGB());
        // 2: mirrored, to the top right
        assertThat(ChatImages.turned(source, 2).getRGB(2, 0)).isEqualTo(Color.RED.getRGB());
        // 1 (and anything else): as it is
        assertThat(ChatImages.turned(source, 1)).isSameAs(source);
    }

    @Test
    void theOrientationIsReadFromTheExifOfAJpeg() throws Exception {
        // an APP1 segment of a big endian TIFF with one entry: orientation 6
        byte[] exif = {'E', 'x', 'i', 'f', 0, 0, 'M', 'M', 0, 0x2A, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0, 0, 0};
        assertThat(ChatImages.orientation(exif)).isEqualTo(6);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0, (byte) (exif.length + 2)});
        out.write(exif);
        out.write(new byte[]{(byte) 0xFF, (byte) 0xD9});
        File jpeg = new File(folder, "exif.jpg");
        Files.write(jpeg.toPath(), out.toByteArray());
        assertThat(ChatImages.orientation(jpeg)).isEqualTo(6);
        assertThat(ChatImages.orientation(new byte[]{1, 2, 3})).isEqualTo(1);
    }
}
