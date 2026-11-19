package com.github.enerccio.joi.domain.chat;

import com.github.enerccio.joi.defaults.JoiDefaults;
import com.github.enerccio.joi.domain.collections.ChatMessageType;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.settings.JoiUserSetting;
import com.github.enerccio.joi.domain.persona.Mood;
import com.github.enerccio.joi.domain.service.BodyService;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.MessagePromptService;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.domain.service.TtsService;
import com.github.enerccio.joi.domain.templates.ChatTemplateData;
import com.github.enerccio.joi.tts.model.Actor;
import com.github.enerccio.joi.tts.model.TtsModel;
import com.github.enerccio.joi.tts.text.Delivery;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.domain.service.AIService;
import com.github.enerccio.marginalia.domain.service.InferenceService;
import com.github.enerccio.marginalia.domain.service.InferenceService.ChunkType;
import com.github.enerccio.marginalia.domain.service.InferenceService.InferenceRoundCallback;
import com.github.enerccio.marginalia.domain.service.InferenceService.RoundUsage;
import com.github.enerccio.marginalia.domain.service.InferenceServices;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMChatMessage;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMRole;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMToolCall;
import com.github.enerccio.marginalia.utils.AppClock;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** {@link ChatService} on the platform's inference, Joi's persona and the TTS models of the user. */
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    /** How long the model may take for a reply before the turn fails. */
    static final long TEXT_TIMEOUT_MINUTES = 15;
    /** The shortest piece of speech of a streamed turn: shorter sentences are joined. */
    static final int MIN_PIECE = 80;

    @Autowired
    private JoiService joiService;

    @Autowired
    private PersonaService personaService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private MessagePromptService messagePromptService;

    @Autowired
    private AIService aiService;

    @Autowired
    private InferenceServices inferenceServices;

    @Autowired
    private TtsService ttsService;

    @Autowired
    private BodyService bodyService;

    private final Map<Long, ReentrantLock> turns = new ConcurrentHashMap<>();
    private final Random random = new Random();

    /** What the model answered. */
    private record Answer(String text, String reasoning, RoundUsage usage) {
    }

    @Override
    public Result chat(Joi joi, String text, String node, boolean speak, boolean pieces, Listener listener) throws Exception {
        if (StringUtils.isBlank(text)) {
            throw new IllegalArgumentException("There is nothing to say");
        }
        AI ai = chatModel(joi);
        if (ai == null) {
            throw new NoModelException(joiService.name(joi) + " has no chat model: choose one in her About tab or in User Settings");
        }
        ReentrantLock turn = turns.computeIfAbsent(joi.getId(), _ -> new ReentrantLock(true));
        turn.lockInterruptibly();
        try {
            return turn(joiService.find(joi.getId()), ai, text.strip(), node, speak, pieces, listener);
        } finally {
            turn.unlock();
        }
    }

    private Result turn(Joi joi, AI ai, String text, String node, boolean speak, boolean pieces, Listener listener) throws Exception {
        Mood mood = todaysMood(joi);
        ChatTemplateData data = new ChatTemplateData();
        data.setNode(StringUtils.defaultIfBlank(node, null));
        data.setBody(bodyService.prompt(joi));
        Actor voice = ttsModel(joi) == null ? null : voiceActor(joi);
        data.setVoice(voice == null ? null : StringUtils.defaultIfBlank(voice.getDescription(), null));
        String system = personaService.renderChatSystemPrompt(joi, null, mood.key(), data);

        List<LLMChatMessage> payload = new ArrayList<>();
        payload.add(LLMChatMessage.of(LLMRole.SYSTEM, system));
        for (ChatMessage earlier : chatMessageService.findLatest(joi, HISTORY)) {
            if (StringUtils.isNotBlank(earlier.getInput())) {
                payload.add(LLMChatMessage.of(LLMRole.USER, earlier.getInput()));
            }
            if (StringUtils.isNotBlank(earlier.getResponse())) {
                payload.add(LLMChatMessage.of(LLMRole.ASSISTANT, earlier.getResponse()));
            }
        }
        payload.add(LLMChatMessage.of(LLMRole.USER, text));

        Date sent = AppClock.now();
        long start = System.nanoTime();
        AtomicLong firstToken = new AtomicLong(-1);
        Answer answer = ask(ai, payload, start, firstToken, listener);
        long textMs = millisSince(start);
        Date end = AppClock.now();

        ChatMessage message = new ChatMessage();
        message.setJoi(joi);
        message.setType(ChatMessageType.USER);
        message.setSentAt(sent);
        message.setInput(text);
        message.setNode(StringUtils.defaultIfBlank(node, null));
        message.setResponse(answer.text());
        message.setResponseReasoning(StringUtils.defaultIfBlank(answer.reasoning(), null));
        message.setMood(mood.key());
        message.setModelUsed(ai.getName());
        message.setPromptTokens(answer.usage() == null ? null : answer.usage().promptTokens());
        message.setTokenCount(answer.usage() == null ? null : answer.usage().completionTokens());
        message.setRequest(sent);
        if (firstToken.get() >= 0) {
            message.setTtft(new Date(sent.getTime() + firstToken.get()));
        }
        message.setResponseEnd(end);
        message = chatMessageService.save(message);
        messagePromptService.store(message, promptText(payload));
        listener.onReply(message);

        byte[] audio = null;
        String speechError = null;
        long speechMs = -1;
        if (speak) {
            long speechStart = System.nanoTime();
            try {
                SpeechOutcome outcome = speak(joi, mood, answer.text(), pieces, listener);
                audio = outcome.audio();
                speechError = outcome.error();
                if (outcome.spoken()) {
                    speechMs = millisSince(speechStart);
                }
            } catch (Exception e) {
                log.warn("The reply of {} could not be spoken: {}", joi.getUuid(), e.getMessage());
                speechError = reason(e);
            }
        }
        long prompt = answer.usage() == null ? 0 : answer.usage().promptTokens();
        long completion = answer.usage() == null ? 0 : answer.usage().completionTokens();
        return new Result(message, mood, ai, audio, speechError, prompt, completion, firstToken.get(), textMs, speechMs);
    }

    private Answer ask(AI ai, List<LLMChatMessage> payload, long start, AtomicLong firstToken, Listener listener) throws Exception {
        InferenceService service = inferenceServices.forAIOrThrow(ai);
        CompletableFuture<Answer> done = new CompletableFuture<>();
        service.round(payload, null, null, new InferenceRoundCallback() {
            @Override
            public void onChunk(ChunkType chunkType, String chunk) {
                firstToken.compareAndSet(-1, millisSince(start));
                try {
                    if (chunkType == ChunkType.REASONING) {
                        listener.onReasoning(chunk);
                    } else {
                        listener.onText(chunk);
                    }
                } catch (Exception e) {
                    log.debug("A listener of a chat failed: {}", e.getMessage());
                }
            }

            @Override
            public void onRound(String text, List<LLMToolCall> calls, RoundUsage usage) {
                onRound(text, null, calls, usage);
            }

            @Override
            public void onRound(String text, String reasoning, List<LLMToolCall> calls, RoundUsage usage) {
                done.complete(new Answer(StringUtils.strip(StringUtils.defaultString(text)), reasoning, usage));
            }

            @Override
            public void onCancel() {
                done.completeExceptionally(new CancellationException("The model stopped"));
            }

            @Override
            public void onError(Throwable exception) {
                done.completeExceptionally(exception);
            }
        });
        try {
            return done.get(TEXT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    /** What the speech of a turn gave. */
    private record SpeechOutcome(byte[] audio, String error, boolean spoken) {
    }

    private SpeechOutcome speak(Joi joi, Mood mood, String reply, boolean pieces, Listener listener) throws Exception {
        TtsModel model = ttsModel(joi);
        Actor actor = voiceActor(joi);
        if (model == null || actor == null) {
            return new SpeechOutcome(null, null, false);
        }
        Delivery delivery = mood.delivery();
        String voice = actor.pick(delivery == null ? null : delivery.name(), null);
        if (StringUtils.isBlank(voice)) {
            return new SpeechOutcome(null, "The voice actor " + actor.getName() + " has no voice", false);
        }
        String words = SpeechText.clean(reply);
        if (words.isEmpty()) {
            return new SpeechOutcome(null, null, false);
        }
        long timeout = Math.max(60, model.getTimeoutSeconds()) + 60L;
        if (!pieces) {
            byte[] audio = ttsService.speak(model, voice, SpeechText.forModel(model, words, delivery)).get(timeout, TimeUnit.SECONDS);
            return new SpeechOutcome(audio, null, true);
        }
        int index = 0;
        for (String piece : SpeechText.pieces(words, Math.max(MIN_PIECE, model.getChunkSize()))) {
            byte[] audio;
            try {
                audio = ttsService.speak(model, voice, SpeechText.forModel(model, piece, delivery)).get(timeout, TimeUnit.SECONDS);
            } catch (Exception e) {
                // what was spoken stays spoken; the rest is missing
                log.warn("A piece of the reply of {} could not be spoken: {}", joi.getUuid(), e.getMessage());
                return new SpeechOutcome(null, reason(e), index > 0);
            }
            listener.onAudio(index++, piece, audio);
        }
        return new SpeechOutcome(null, null, true);
    }

    @Override
    public AI chatModel(Joi joi) throws Exception {
        Long id = joi.getChatModel() != null ? joi.getChatModel() : personaService.getUserSetting().getChatModel();
        if (id == null) {
            return null;
        }
        return aiService.findAllForUser().stream().filter(ai -> id.equals(ai.getId())).findFirst().orElse(null);
    }

    @Override
    public TtsModel ttsModel(Joi joi) throws Exception {
        String id = StringUtils.defaultIfBlank(joi.getTtsModel(), personaService.getUserSetting().getTtsModel());
        return id == null ? null : ttsService.findModel(id);
    }

    @Override
    public Actor voiceActor(Joi joi) throws Exception {
        String id = StringUtils.defaultIfBlank(joi.getVoiceActor(), personaService.getUserSetting().getVoiceActor());
        return id == null ? null : ttsService.actors().stream().filter(a -> id.equals(a.getId())).findFirst().orElse(null);
    }

    @Override
    public Mood todaysMood(Joi joi) throws Exception {
        Mood current = joi.getMood() == null ? null : personaService.mood(joi.getMood());
        if (current != null && joi.getMoodSince() != null && !joi.getMoodSince().before(dayStart(joi))) {
            return current;
        }
        Mood rolled = personaService.rollMood(random);
        joi.setMood(rolled.key());
        joi.setMoodSince(AppClock.now());
        joiService.save(joi);
        return rolled;
    }

    /** When her day began: today at her day boundary, or yesterday when it is not that late yet. */
    Date dayStart(Joi joi) throws Exception {
        JoiUserSetting setting = personaService.getUserSetting();
        String boundary = StringUtils.defaultIfBlank(joi.getDayBoundary(),
                StringUtils.defaultIfBlank(setting.getDayBoundary(), JoiDefaults.DAY_BOUNDARY));
        LocalTime time;
        try {
            time = LocalTime.parse(boundary.strip());
        } catch (DateTimeParseException e) {
            time = LocalTime.parse(JoiDefaults.DAY_BOUNDARY);
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime now = LocalDateTime.ofInstant(AppClock.now().toInstant(), zone);
        LocalDateTime start = LocalDate.from(now).atTime(time);
        if (start.isAfter(now)) {
            start = start.minusDays(1);
        }
        return Date.from(start.atZone(zone).toInstant());
    }

    /** The prompt as it is stored with the message: the messages as JSON. */
    private static String promptText(List<LLMChatMessage> payload) {
        JsonArray messages = new JsonArray();
        for (LLMChatMessage message : payload) {
            JsonObject entry = new JsonObject();
            entry.addProperty("role", message.getRole().name().toLowerCase(Locale.ROOT));
            entry.addProperty("content", message.getContent());
            messages.add(entry);
        }
        return messages.toString();
    }

    private static String reason(Exception e) {
        Throwable root = ExceptionUtils.getRootCause(e);
        return StringUtils.defaultIfBlank((root == null ? e : root).getMessage(), e.getClass().getSimpleName());
    }

    private static long millisSince(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
