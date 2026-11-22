package com.github.enerccio.joi.ui.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** The link of the QR code for a phone, and the code itself. */
class PairPhoneDialogTest {

    @Test
    void theLinkHasTheServerAndTheOffer() {
        assertThat(PairPhoneDialog.link("http://192.168.1.5:8080", "aB-_9"))
                .isEqualTo("joi://pair?server=http%3A%2F%2F192.168.1.5%3A8080&offer=aB-_9");
    }

    @Test
    void aPhoneCannotReachTheMachinesOwnAddress() {
        assertThat(PairPhoneDialog.reachable("http://192.168.1.5:8080")).isTrue();
        assertThat(PairPhoneDialog.reachable("https://joi.example.com")).isTrue();
        assertThat(PairPhoneDialog.reachable("http://localhost:8090")).isFalse();
        assertThat(PairPhoneDialog.reachable("http://127.0.0.1:8090")).isFalse();
        assertThat(PairPhoneDialog.reachable("")).isFalse();
    }

    @Test
    void theCodeReadsBackAsTheLink() throws Exception {
        String link = PairPhoneDialog.link("http://192.168.1.5:8080", "aB-_9xyz");
        var image = ImageIO.read(new ByteArrayInputStream(PairPhoneDialog.png(link)));
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        var source = new RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels);
        assertThat(new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText()).isEqualTo(link);
    }
}
