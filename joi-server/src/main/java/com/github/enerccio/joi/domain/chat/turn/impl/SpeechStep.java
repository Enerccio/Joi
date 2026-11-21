package com.github.enerccio.joi.domain.chat.turn.impl;

import com.github.enerccio.joi.domain.chat.ClipWriter;
import com.github.enerccio.joi.domain.chat.SpeechText;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.tts.audio.AudioKind;
import com.github.enerccio.joi.domain.chat.turn.TurnController;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.chat.turn.TurnProperties;
import com.github.enerccio.joi.domain.chat.turn.TurnStepBase;
import com.github.enerccio.joi.domain.chat.turn.TurnStepType;
import com.github.enerccio.joi.domain.service.TtsService;
import com.github.enerccio.joi.tts.model.Actor;
import com.github.enerccio.joi.tts.model.TtsModel;
import com.github.enerccio.joi.tts.text.Delivery;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The reply spoken with her voice, when the turn asks for it: whole, or in pieces (the first sentence first) that the
 * caller gets as they are rendered. A speech that fails leaves the turn stored; what is missing is the speech error.
 */
public class SpeechStep extends TurnStepBase {

    private static final Logger log = LoggerFactory.getLogger(SpeechStep.class);

    /** The shortest piece of speech: shorter sentences are joined. */
    static final int MIN_PIECE = 80;

    @Autowired
    private TtsService ttsService;

    @Autowired
    private ChatMessageService chatMessageService;

    /** The clip of a turn being written, by its run. */
    private final Map<String, ClipWriter> clips = new ConcurrentHashMap<>();

    @Override
    protected void onStep(TurnController controller) throws Exception {
        if (!controller.getInput().speak()) {
            controller.next();
            return;
        }
        TtsModel model = chatService.ttsModel(controller.getJoi());
        Actor actor = chatService.voiceActor(controller.getJoi());
        if (model == null || actor == null) {
            controller.next();
            return;
        }
        controller.getProperties().put(TurnProperties.SPEECH_TEXT, SpeechText.clean(controller.getReply()));
        controller.emitEvent(TurnEvents.BEFORE_SPEECH, () -> {
            String words = StringUtils.strip((String) controller.getProperties().get(TurnProperties.SPEECH_TEXT));
            if (StringUtils.isEmpty(words)) {
                spoken(controller, null, false);
                return;
            }
            Delivery delivery = controller.getMood().delivery();
            String voice = actor.pick(delivery == null ? null : delivery.name(), null);
            if (StringUtils.isBlank(voice)) {
                spoken(controller, "The voice actor " + actor.getName() + " has no voice", false);
                return;
            }
            long start = System.nanoTime();
            if (controller.getInput().pieces()) {
                List<String> pieces = SpeechText.pieces(words, Math.max(MIN_PIECE, model.getChunkSize()));
                piece(controller, model, voice, delivery, pieces, 0, start);
            } else {
                speak(model, voice, SpeechText.forModel(model, words, delivery)).whenComplete((audio, error) ->
                        controller.wrapCallback(() -> {
                            if (error != null) {
                                spoken(controller, reason(error), false);
                            } else {
                                controller.setAudio(audio);
                                keep(controller, audio);
                                controller.setSpeechMs(millisSince(start));
                                spoken(controller, null, true);
                            }
                        }).run());
            }
        });
    }

    private void piece(TurnController controller, TtsModel model, String voice, Delivery delivery, List<String> pieces,
                       int index, long start) {
        if (index >= pieces.size() || controller.getCancellationToken().isCancelled()) {
            controller.setSpeechMs(millisSince(start));
            spoken(controller, null, index > 0);
            return;
        }
        String piece = pieces.get(index);
        speak(model, voice, SpeechText.forModel(model, piece, delivery)).whenComplete((audio, error) ->
                controller.wrapCallback(() -> {
                    if (error != null) {
                        // what was spoken stays spoken; the rest is missing
                        log.warn("A piece of the reply of {} could not be spoken: {}", controller.getJoi().getUuid(), reason(error));
                        if (index > 0) {
                            controller.setSpeechMs(millisSince(start));
                        }
                        spoken(controller, reason(error), index > 0);
                        return;
                    }
                    keep(controller, audio);
                    try {
                        controller.getListener().onAudio(index, piece, audio);
                    } catch (Exception e) {
                        // a caller that is gone does not stop the speech: the clip is kept with the message
                        log.debug("A listener of a chat failed: {}", e.getMessage());
                    }
                    piece(controller, model, voice, delivery, pieces, index + 1, start);
                }).run());
    }

    private CompletableFuture<byte[]> speak(TtsModel model, String voice, String text) {
        long timeout = Math.max(60, model.getTimeoutSeconds()) + 60L;
        try {
            return ttsService.speak(model, voice, text).orTimeout(timeout, TimeUnit.SECONDS);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void spoken(TurnController controller, String error, boolean spoken) {
        if (error != null) {
            log.warn("The reply of {} could not be spoken: {}", controller.getJoi().getUuid(), error);
        }
        keepClip(controller);
        controller.setSpeechError(error);
        if (!spoken) {
            controller.setSpeechMs(-1);
        }
        controller.getProperties().put(TurnProperties.SPEECH_ERROR, error);
        controller.emitEvent(TurnEvents.AFTER_SPEECH, controller::next);
    }

    /** Copies the piece of speech into the clip of the turn (the pieces become one file, never all in memory). */
    private void keep(TurnController controller, byte[] audio) {
        String run = (String) controller.getProperties().get(TurnProperties.RUN_ID);
        try {
            ClipWriter clip = clips.get(run);
            if (clip == null) {
                clip = new ClipWriter(chatMessageService.newClipFile());
                clips.put(run, clip);
            }
            clip.add(audio);
        } catch (Exception e) {
            log.warn("The clip of the reply of {} could not be kept: {}", controller.getJoi().getUuid(), reason(e));
            ClipWriter broken = clips.remove(run);
            if (broken != null) {
                broken.discard();
            }
        }
    }

    /** The speech is over: the clip, when there is one, becomes the message's resource and the watchers are told. */
    private void keepClip(TurnController controller) {
        ClipWriter clip = clips.remove((String) controller.getProperties().get(TurnProperties.RUN_ID));
        if (clip == null) {
            return;
        }
        try {
            clip.close();
            ChatMessage message = controller.getMessage();
            if (!clip.hasAudio() || message == null) {
                clip.discard();
                return;
            }
            AudioKind kind = clip.kind();
            message = chatMessageService.attachClip(message, clip.file(), kind.extension(), kind.mime());
            controller.setMessage(message);
            chatService.voiced(message);
        } catch (Exception e) {
            log.warn("The clip of the reply of {} could not be stored: {}", controller.getJoi().getUuid(), reason(e));
            clip.discard();
        }
    }

    private static String reason(Throwable e) {
        Throwable root = ExceptionUtils.getRootCause(e);
        Throwable shown = root == null ? e : root;
        return StringUtils.defaultIfBlank(shown.getMessage(), shown.getClass().getSimpleName());
    }

    private static long millisSince(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    @Override
    public TurnStepType getType() {
        return TurnStepType.SPEECH;
    }
}
