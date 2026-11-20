package com.github.enerccio.joi.node;

import com.github.enerccio.joi.protocol.http.ApiError;
import com.github.enerccio.joi.protocol.http.AudioChunk;
import com.github.enerccio.joi.protocol.http.ChatReply;
import com.github.enerccio.joi.protocol.http.JoiAnimations;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** What her body plays: idle, talking while she speaks, and sleeping through her first turn until she wakes up. */
class AvatarDirectorTest {

    private final List<String> log = new ArrayList<>();

    private final AvatarDirector.Avatar avatar = new AvatarDirector.Avatar() {
        @Override
        public void play(String animation, boolean loop) {
            log.add("play " + animation + (loop ? " loop" : ""));
        }

        @Override
        public void text(String turn, String delta) {
            log.add("text " + delta);
        }

        @Override
        public void reply(String turn, ChatReply reply) {
            log.add("reply " + reply.text());
        }

        @Override
        public void speak(String turn, AudioChunk chunk) {
            log.add("speak " + chunk.index());
        }

        @Override
        public void failed(String turn, ApiError error) {
            log.add("failed " + error.code());
        }
    };

    private static ChatReply reply(String text) {
        return new ChatReply("m1", text, null, null, null, null, null, null);
    }

    private static final JoiAnimations ALL = new JoiAnimations("sleep", null, "wake", List.of("idle"), List.of("talk"));

    @Test
    void sheSleepsThroughHerFirstTurnAndSpeaksOnceAwake() {
        AvatarDirector director = new AvatarDirector(avatar, ALL, new Random(1));
        director.start();
        director.turnStarted("s1", AvatarDirector.FIRST_BOOT, true);
        director.text("s1", "Hel");
        director.reply("s1", reply("Hello."));
        director.audio("s1", new AudioChunk(0, "Hello.", "audio/wav", ""));
        // the words wait while she sleeps
        assertThat(log).containsExactly("play idle loop", "play sleep loop");
        assertThat(director.state()).isEqualTo(AvatarDirector.State.SLEEPING);

        director.done("s1");
        assertThat(director.state()).isEqualTo(AvatarDirector.State.WAKING_UP);
        assertThat(log.getLast()).isEqualTo("play wake");

        director.animationEnded();
        assertThat(log.subList(3, log.size())).containsExactly("play idle loop", "text Hel", "reply Hello.", "play talk loop", "speak 0");
        assertThat(director.state()).isEqualTo(AvatarDirector.State.TALKING);
        director.speechEnded();
        assertThat(director.state()).isEqualTo(AvatarDirector.State.IDLE);
    }

    @Test
    void aLostConnectionWakesHerAndShowsWhatCame() {
        AvatarDirector director = new AvatarDirector(avatar, ALL, new Random(1));
        director.start();
        director.turnStarted("s1", AvatarDirector.FIRST_BOOT, true);
        director.text("s1", "Hel");
        director.reply("s1", reply("Hello."));

        director.disconnected();
        assertThat(log).containsExactly("play idle loop", "play sleep loop", "play idle loop", "text Hel", "reply Hello.");
        assertThat(director.state()).isEqualTo(AvatarDirector.State.IDLE);
    }

    @Test
    void herWordsWaitForHerVoice() {
        AvatarDirector director = new AvatarDirector(avatar, ALL, new Random(1));
        director.start();
        director.turnStarted("t1", NodeSocket.SAY, true);
        director.text("t1", "Hel");
        director.text("t1", "lo.");
        director.reply("t1", reply("Hello."));
        assertThat(log).containsExactly("play idle loop");

        director.audio("t1", new AudioChunk(0, "Hello.", "audio/wav", ""));
        director.audio("t1", new AudioChunk(1, "Hi.", "audio/wav", ""));
        assertThat(log).containsExactly("play idle loop", "text Hel", "text lo.", "reply Hello.", "play talk loop", "speak 0", "speak 1");

        // a turn whose voice never comes shows its words when it ends
        log.clear();
        director.speechEnded();
        director.turnStarted("t2", NodeSocket.SAY, true);
        director.text("t2", "Hm.");
        director.reply("t2", reply("Hm."));
        assertThat(log).containsExactly("play idle loop");
        director.done("t2");
        assertThat(log).containsExactly("play idle loop", "text Hm.", "reply Hm.");
        assertThat(director.state()).isEqualTo(AvatarDirector.State.IDLE);
    }

    @Test
    void withoutSpeechSheIsIdleWhenTheTurnIsDone() {
        AvatarDirector director = new AvatarDirector(avatar, ALL, new Random(1));
        director.start();
        director.turnStarted("t1", NodeSocket.SAY, false);
        director.reply("t1", reply("Sure."));
        assertThat(director.state()).isEqualTo(AvatarDirector.State.TALKING);
        director.done("t1");
        assertThat(director.state()).isEqualTo(AvatarDirector.State.IDLE);

        // without the sleeping animation the first boot is a plain turn
        AvatarDirector bare = new AvatarDirector(avatar, new JoiAnimations(null, null, null, List.of(), List.of()), new Random(1));
        bare.start();
        bare.turnStarted("s1", AvatarDirector.FIRST_BOOT, false);
        bare.reply("s1", reply("Hi."));
        assertThat(log.getLast()).isEqualTo("play null loop");
        bare.done("s1");
        assertThat(bare.state()).isEqualTo(AvatarDirector.State.IDLE);
    }

    @Test
    void aFailedFirstTurnWakesHerToWait() {
        AvatarDirector director = new AvatarDirector(avatar, ALL, new Random(1));
        director.turnStarted("s1", AvatarDirector.FIRST_BOOT, true);
        director.text("s1", "Hel");
        director.failed("s1", new ApiError("model_failed", "down"));
        assertThat(log).containsSubsequence("play sleep loop", "text Hel", "failed model_failed", "play idle loop");
        assertThat(director.state()).isEqualTo(AvatarDirector.State.IDLE);
    }

    @Test
    void anotherIdleEachTimeWhenThereAreSeveral() {
        AvatarDirector director = new AvatarDirector(avatar, new JoiAnimations(null, null, null, List.of("a", "b", "c"), List.of()),
                new Random(7));
        String last = null;
        for (int i = 0; i < 20; i++) {
            director.turnStarted("t" + i, NodeSocket.SAY, false);
            director.reply("t" + i, reply("."));
            director.done("t" + i);
            String played = log.getLast();
            assertThat(played).isNotEqualTo(last);
            last = played;
        }
    }
}
