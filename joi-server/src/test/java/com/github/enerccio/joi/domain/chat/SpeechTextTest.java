package com.github.enerccio.joi.domain.chat;

import com.github.enerccio.joi.tts.model.TtsModel;
import com.github.enerccio.joi.tts.text.Delivery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What of a reply is spoken, and in which pieces. */
class SpeechTextTest {

    @Test
    void stageDirectionsMarkdownAndEmojiAreNotSpoken() {
        assertThat(SpeechText.clean("*smiles softly* Hi **Peter**! 😊 See [the docs](http://x).")).isEqualTo("Hi Peter! See the docs.");
        assertThat(SpeechText.clean("# Title\n- one\n- two")).isEqualTo("Title\none\ntwo");
        assertThat(SpeechText.clean("2 * 3 * 4 is 24")).isEqualTo("2 * 3 * 4 is 24");
        assertThat(SpeechText.clean(null)).isEmpty();
    }

    @Test
    void thePiecesAreWholeSentencesAndTheFirstIsOne() {
        List<String> pieces = SpeechText.pieces("Hi. How was your day? I fixed the lamp. It works now. Tea?", 40);
        assertThat(pieces).containsExactly("Hi.", "How was your day? I fixed the lamp.", "It works now. Tea?");
        assertThat(String.join(" ", pieces)).isEqualTo("Hi. How was your day? I fixed the lamp. It works now. Tea?");
        assertThat(SpeechText.pieces("One line\nanother line", 100)).containsExactly("One line", "another line");
        assertThat(SpeechText.pieces("", 100)).isEmpty();
    }

    @Test
    void higgsGetsTheMoodAsItsEmotion() {
        TtsModel higgs = new TtsModel();
        higgs.setModel(TtsModel.MODEL_HIGGS);
        assertThat(SpeechText.forModel(higgs, "Hello.", Delivery.JOYFUL)).isEqualTo("<|emotion:elation|>Hello.");
        TtsModel other = new TtsModel();
        other.setModel(TtsModel.MODEL_MULTILINGUAL);
        assertThat(SpeechText.forModel(other, "Hello [laugh].", Delivery.JOYFUL)).isEqualTo("Hello .");
    }
}
