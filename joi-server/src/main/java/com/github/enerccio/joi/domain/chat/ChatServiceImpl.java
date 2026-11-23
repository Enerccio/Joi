package com.github.enerccio.joi.domain.chat;

import com.github.enerccio.joi.defaults.JoiDefaults;
import com.github.enerccio.joi.domain.chat.turn.*;
import com.github.enerccio.marginalia.domain.service.job.JobStatus;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.EventChain;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;
import com.github.enerccio.joi.domain.events.JoiEvents;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.settings.JoiUserSetting;
import com.github.enerccio.joi.domain.persona.Mood;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.domain.service.TtsService;
import com.github.enerccio.joi.domain.templates.ChatTemplateData;
import com.github.enerccio.joi.tts.model.Actor;
import com.github.enerccio.joi.tts.model.TtsModel;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.domain.service.AIService;
import com.github.enerccio.marginalia.domain.service.CancellationToken;
import com.github.enerccio.marginalia.domain.service.InferenceService.RoundUsage;
import com.github.enerccio.marginalia.domain.service.function.FunctionSet;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMChatMessage;
import com.github.enerccio.marginalia.ui.components.ThreadCopyRequestAttributes.InRequestScope;
import com.github.enerccio.joi.domain.collections.ChatMessageType;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.marginalia.utils.AppClock;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link ChatService} on the platform's inference, Joi's persona and the TTS models of the user: the engine that runs
 * the {@link TurnStep}s of a turn (the beans listed under it in {@code services-config.xml}) and emits their events.
 */
public class ChatServiceImpl implements ChatService, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    @Autowired
    private JoiService joiService;

    @Autowired
    private PersonaService personaService;

    @Autowired
    private com.github.enerccio.joi.domain.service.ProfileService profileService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private com.github.enerccio.joi.domain.service.ChatImageService chatImageService;

    @Autowired
    private com.github.enerccio.joi.domain.service.QueuedEventService queuedEventService;

    @Autowired
    private AIService aiService;

    @Autowired
    private TtsService ttsService;

    private List<TurnStep> installedSteps = List.of();
    private final Map<TurnStepType, TurnStep> steps = new EnumMap<>(TurnStepType.class);
    private final Map<TurnEvents, List<TurnEvent>> listeners = new ConcurrentHashMap<>();
    private final Map<TurnEvents, List<TurnObserver>> observers = new ConcurrentHashMap<>();
    /** The end of the last turn of each assistant: the next one starts after it. */
    private final Map<Long, CompletableFuture<Void>> lastTurns = new HashMap<>();
    private final Map<Long, List<Feed>> feeds = new ConcurrentHashMap<>();
    /** The turns that wait or run now, for the page of the jobs. */
    private final java.util.Set<TurnEngine> running = ConcurrentHashMap.newKeySet();
    private final List<java.util.function.Consumer<ChatMessage>> logWatchers = new CopyOnWriteArrayList<>();
    private final List<java.util.function.Consumer<Joi>> moodWatchers = new CopyOnWriteArrayList<>();
    private final Random random = new Random();
    private ExecutorService executor;

    public void setInstalledSteps(List<TurnStep> installedSteps) {
        this.installedSteps = List.copyOf(installedSteps);
    }

    @Override
    public void afterPropertiesSet() {
        for (TurnStep step : installedSteps) {
            if (steps.putIfAbsent(step.getType(), step) != null) {
                throw new IllegalStateException("Two steps of the type " + step.getType());
            }
        }
        for (TurnStepType type : TurnStepType.values()) {
            if (!steps.containsKey(type)) {
                throw new IllegalStateException("No step of the type " + type);
            }
        }
        executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("Joi turn ", 0).factory());
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    @Override
    public Registration addEventListener(TurnEvents event, TurnEvent listener) {
        if (event.isObservedOnly()) {
            throw new IllegalArgumentException(event + " is for observers only");
        }
        List<TurnEvent> list = listeners.computeIfAbsent(event, _ -> new CopyOnWriteArrayList<>());
        list.add(listener);
        return () -> list.remove(listener);
    }

    @Override
    public Registration watch(Joi joi, Feed feed) {
        List<Feed> list = feeds.computeIfAbsent(joi.getId(), _ -> new CopyOnWriteArrayList<>());
        list.add(feed);
        return () -> list.remove(feed);
    }

    @Override
    public ChatMessage log(Joi joi, String kind, String text) throws Exception {
        ChatMessage message = new ChatMessage();
        message.setJoi(joi);
        message.setType(ChatMessageType.LOG);
        message.setSentAt(AppClock.now());
        message.setEventKind(kind);
        message.setInput(text);
        ChatMessage saved = chatMessageService.save(message);
        tell(joi.getId(), feed -> feed.logged(saved));
        for (java.util.function.Consumer<ChatMessage> watcher : logWatchers) {
            try {
                watcher.accept(saved);
            } catch (Exception e) {
                log.debug("A watcher of the notes failed", e);
            }
        }
        return saved;
    }

    @Override
    public Registration watchLogs(java.util.function.Consumer<ChatMessage> watcher) {
        logWatchers.add(watcher);
        return () -> logWatchers.remove(watcher);
    }

    @Override
    public void voiced(ChatMessage message) {
        tell(message.getJoi().getId(), feed -> feed.voiced(message));
    }

    private void tell(Long joi, java.util.function.Consumer<Feed> what) {
        for (Feed feed : feeds.getOrDefault(joi, List.of())) {
            try {
                what.accept(feed);
            } catch (Exception e) {
                log.debug("A watcher of the conversation failed", e);
            }
        }
    }

    @Override
    public Registration addEventObserver(TurnEvents event, TurnObserver observer) {
        List<TurnObserver> list = observers.computeIfAbsent(event, _ -> new CopyOnWriteArrayList<>());
        list.add(observer);
        return () -> list.remove(observer);
    }

    @Override
    public List<RunningTurn> running() {
        List<RunningTurn> turns = new ArrayList<>();
        for (TurnEngine engine : running) {
            turns.add(new RunningTurn(engine.joi, engine.started ? engine.current : null, engine.sent));
        }
        turns.sort(Comparator.comparing(RunningTurn::since));
        return turns;
    }

    /** The name of the job of a turn: hers. */
    private String label(Joi joi) {
        try {
            return joiService.name(joi);
        } catch (Exception e) {
            return "Joi";
        }
    }

    @Override
    public CompletableFuture<Result> chat(Joi joi, TurnInput input, Listener listener) throws Exception {
        if (input == null || (StringUtils.isBlank(input.text()) && input.images().isEmpty())) {
            throw new IllegalArgumentException("There is nothing to say");
        }
        AI ai = chatModel(joi);
        if (ai == null) {
            throw new NoModelException(joiService.name(joi) + " has no chat model: choose one in her About tab or in User Settings");
        }
        if (input.isEvent() && !input.images().isEmpty()) {
            throw new IllegalArgumentException("An event has no images");
        }
        List<String> images = checkImages(joi, input.images());
        TurnEngine engine = new TurnEngine(joi, ai,
                new TurnInput(StringUtils.defaultString(input.text()).strip(), StringUtils.defaultIfBlank(input.node(), null), input.speak(),
                        input.pieces(), StringUtils.defaultIfBlank(input.eventKind(), null), images,
                        // a turn from no node is in the profile she was in last
                        input.profile() != null ? input.profile() : profileService.last(joi)),
                listener == null ? new Listener() {
                } : listener, RequestContextHolder.getRequestAttributes());
        running.add(engine);
        CompletableFuture<Void> before;
        synchronized (lastTurns) {
            before = lastTurns.getOrDefault(joi.getId(), CompletableFuture.completedFuture(null));
            lastTurns.put(joi.getId(), engine.ended);
        }
        engine.result.whenComplete((_, _) -> {
            if (engine.result.isCancelled()) {
                engine.token.cancel();
            }
        });
        tell(joi.getId(), feed -> feed.queued(engine.runId, engine.input, engine.sent));
        engine.ended.whenComplete((_, _) -> {
            if (engine.told.compareAndSet(false, true)) {
                tell(joi.getId(), feed -> feed.finished(engine.runId, null));
            }
        });
        before.whenCompleteAsync((_, _) -> engine.start(), executor);
        return engine.result;
    }

    @Override
    public List<String> checkImages(Joi joi, List<String> images) throws Exception {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        AI ai = chatModel(joi);
        if (ai == null) {
            throw new NoModelException(joiService.name(joi) + " has no chat model: choose one in her About tab or in User Settings");
        }
        if (!chatImageService.canSee(ai)) {
            throw new NoVisionException(joiService.name(joi) + " cannot look at images: her chat model does not take images, and none of your "
                    + "models that do is set as the deep reasoning model to describe them for her. Mark a model Multimodal in the Models tab.");
        }
        return chatImageService.check(images);
    }

    @Override
    public void whenIdle(Joi joi, Runnable action) {
        CompletableFuture<Void> last;
        synchronized (lastTurns) {
            last = lastTurns.get(joi.getId());
        }
        if (last == null || last.isDone()) {
            action.run();
            return;
        }
        // a turn asked for meanwhile is waited for too
        last.whenCompleteAsync((_, _) -> whenIdle(joi, action), executor);
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
        joiService.saveMood(joi, rolled.key(), AppClock.now());
        moodChanged(joi);
        return rolled;
    }

    @Override
    public Mood changeMood(Joi joi, String key, boolean byUser) throws Exception {
        Mood target = personaService.mood(key);
        if (target == null) {
            throw new IllegalArgumentException("There is no mood \"" + key + "\". The moods: "
                    + personaService.moods().stream().map(Mood::key).collect(java.util.stream.Collectors.joining(", ")));
        }
        Mood before = joi.getMood() == null ? null : personaService.mood(joi.getMood());
        if (before != null && before.key().equals(key)) {
            return target;
        }
        joiService.saveMood(joi, key, AppClock.now());
        log(joi, byUser ? JoiEvents.MOOD_CHANGED : "mood.chosen",
                "Her mood is now " + target.name() + (before == null ? "" : " (was " + before.name() + ")")
                        + (byUser ? ": you changed it." : ": she chose it."));
        if (byUser && chatMessageService.hasAny(joi)) {
            com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
            payload.addProperty("from", before == null ? null : before.key());
            payload.addProperty("to", target.key());
            QueuedEvent event = new QueuedEvent();
            event.setJoi(joi);
            event.setKind(JoiEvents.MOOD_CHANGED);
            event.setOccurredAt(AppClock.now());
            event.setText(personaService.defaults().eventMoodChanged(before == null ? null : before.name(), target.name()));
            event.setPayload(payload.toString());
            queuedEventService.save(event);
        }
        moodChanged(joi);
        return target;
    }

    @Override
    public Registration watchMoods(java.util.function.Consumer<Joi> watcher) {
        moodWatchers.add(watcher);
        return () -> moodWatchers.remove(watcher);
    }

    private void moodChanged(Joi joi) {
        for (java.util.function.Consumer<Joi> watcher : moodWatchers) {
            try {
                watcher.accept(joi);
            } catch (Exception e) {
                log.debug("A watcher of the mood failed", e);
            }
        }
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

    /** One turn: its state, its way through the steps and its events. */
    private class TurnEngine implements TurnController {

        private final String runId = UUID.randomUUID().toString();
        // a value may be null (no speech error)
        private final Map<String, Object> properties = Collections.synchronizedMap(new HashMap<>());
        private final AI ai;
        private final TurnInput input;
        private final Listener listener;
        private final RequestAttributes requestAttributes;
        private final CancellationToken token = new CancellationToken();
        private final CompletableFuture<Result> result = new CompletableFuture<>();
        /** Completed when the cleanup is done, whatever the caller did with {@link #result}. */
        private final CompletableFuture<Void> ended = new CompletableFuture<>();
        private final AtomicBoolean finished = new AtomicBoolean();
        /** Whether the watchers of the conversation were told how the turn ended. */
        private final AtomicBoolean told = new AtomicBoolean();
        private final Executor scoped;
        /** The job of the turn in the administrator's log (one request after another, her speech too). */
        private final TurnJob job;
        private final long startNanos = System.nanoTime();
        private final Date sent = AppClock.now();
        private final AtomicLong firstToken = new AtomicLong(-1);

        private volatile TurnStepType current = TurnStepType.PREPARE;
        /** Whether the turn left the line of the turns before it. */
        private volatile boolean started;
        private volatile Joi joi;
        private volatile Mood mood;
        private volatile ChatTemplateData templateData;
        private volatile List<LLMChatMessage> payload;
        private volatile FunctionSet functions = FunctionSet.empty();
        private volatile String reply = "";
        private volatile String reasoning;
        private final List<ToolUsage> toolUsages = new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile ChatMessage message;
        /** A failed turn is stored once, as far as it got ({@link #keepWhatHappened}). */
        private final AtomicBoolean partialTried = new AtomicBoolean();
        private volatile long promptTokens;
        private volatile long completionTokens;
        private volatile long textMs = -1;
        private volatile byte[] audio;
        private volatile String speechError;
        private volatile long speechMs = -1;
        private volatile Throwable failure;

        TurnEngine(Joi joi, AI ai, TurnInput input, Listener listener, RequestAttributes requestAttributes) {
            this.joi = joi;
            this.ai = ai;
            this.input = input;
            this.listener = listener;
            this.requestAttributes = requestAttributes;
            this.job = new TurnJob(label(joi));
            this.scoped = runnable -> executor.execute(() -> {
                try (InRequestScope _ = new InRequestScope(requestAttributes); TurnJob.Left _ = job.enter()) {
                    runnable.run();
                }
            });
            properties.put(TurnProperties.RUN_ID, runId);
        }

        void start() {
            started = true;
            executeStep();
        }

        // ---------------------------------------------------------------------------------------------- the steps

        @Override
        public void next() {
            current = current.next();
            executeStep();
        }

        @Override
        public void jumpTo(TurnStepType stepType) {
            current = stepType;
            executeStep();
        }

        private void executeStep() {
            executor.execute(() -> {
                try (InRequestScope _ = new InRequestScope(requestAttributes); TurnJob.Left _ = job.enter()) {
                    TurnStepType type = current;
                    if (type == TurnStepType.CLEANUP && keepWhatHappened()) {
                        current = type = TurnStepType.STORE;
                    }
                    if (type == null) {
                        finish();
                        return;
                    }
                    if (token.isCancelled() && type != TurnStepType.CLEANUP) {
                        current = type = TurnStepType.CLEANUP;
                    }
                    try {
                        steps.get(type).step(this);
                    } catch (Throwable e) {
                        failed(e);
                    }
                }
            });
        }

        /**
         * A round failed after others had answered: her words and the functions she called so far did happen (a node showed and spoke them,
         * a clipboard offer was made), so they are stored, with her prompt, instead of being lost; the turn still ends as a failure.
         * A turn that is stopped, or failed before it did anything, stores nothing.
         */
        private boolean keepWhatHappened() {
            if (failure == null || message != null || token.isCancelled()
                    || StringUtils.isBlank(reply) && toolUsages.isEmpty() || !partialTried.compareAndSet(false, true)) {
                return false;
            }
            properties.put(TurnProperties.PARTIAL, true);
            return true;
        }

        /** Something failed outside a step's own handling: the cleanup, or the end when it was the cleanup. */
        private void failed(Throwable e) {
            log.error("A turn of {} failed in {}: {}", joi.getUuid(), current, e.getMessage(), e);
            fail(e);
            if (current != TurnStepType.CLEANUP && current != null) {
                current = TurnStepType.CLEANUP;
                executeStep();
            } else {
                finish();
            }
        }

        private void finish() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            boolean partial = Boolean.TRUE.equals(properties.get(TurnProperties.PARTIAL));
            try {
                job.finish(partial ? JobStatus.FAILED : message != null ? JobStatus.COMPLETED : failure != null ? JobStatus.FAILED : JobStatus.CANCELLED);
                running.remove(this);
                if (partial && failure != null) {
                    noteFailure();
                    result.completeExceptionally(failure);
                } else if (message != null) {
                    long first = firstToken.get();
                    result.complete(new Result(message, mood, ai, audio, speechError, promptTokens, completionTokens, first,
                            textMs, speechMs));
                } else if (failure != null) {
                    result.completeExceptionally(failure);
                } else {
                    result.completeExceptionally(new CancellationException("The turn was stopped"));
                }
            } finally {
                ended.complete(null);
            }
        }

        /** Tells the conversation why a turn that is kept ends here: a note, not for the model (the clients get it as a `log` frame). */
        private void noteFailure() {
            try {
                ChatServiceImpl.this.log(joi, "turn.failed", "Her turn failed after she had answered in part, what she said and did is kept: "
                        + StringUtils.defaultIfBlank(failure.getMessage(), failure.getClass().getSimpleName()));
            } catch (Exception e) {
                log.warn("The note of a failed turn was not written: {}", e.getMessage());
            }
        }

        // ---------------------------------------------------------------------------------------------- the events

        @Override
        public void emitEvent(TurnEvents event, FromEventCallback continuation) {
            notifyObservers(event);
            if (event.isObservedOnly()) {
                if (continuation != null) {
                    invokeContinuation(event, continuation);
                }
                return;
            }
            List<TurnEvent> eventListeners = listeners.get(event);
            if (eventListeners == null || eventListeners.isEmpty()) {
                invokeContinuation(event, continuation);
                return;
            }
            Iterator<TurnEvent> iterator = eventListeners.iterator();
            EventView view = new EventView(this, event);
            AtomicBoolean done = new AtomicBoolean();
            EventChain chain = new EventChain() {
                @Override
                public void next() {
                    executor.execute(() -> {
                        try (InRequestScope _ = new InRequestScope(requestAttributes); TurnJob.Left _ = job.enter()) {
                            if (token.isCancelled() && !event.isTerminal() || !iterator.hasNext() || view.terminated) {
                                terminate();
                                return;
                            }
                            TurnEvent next = iterator.next();
                            try {
                                next.onEvent(view, this);
                            } catch (Throwable e) {
                                log.error("A listener of {} failed: {}", event, e.getMessage(), e);
                                next();
                            }
                        }
                    });
                }

                @Override
                public void terminate() {
                    if (done.compareAndSet(false, true)) {
                        invokeContinuation(event, continuation);
                    }
                }
            };
            chain.next();
        }

        private void notifyObservers(TurnEvents event) {
            List<TurnObserver> eventObservers = observers.get(event);
            if (eventObservers == null || eventObservers.isEmpty()) {
                return;
            }
            TurnObservation observation = new Observation(this, event);
            for (TurnObserver observer : eventObservers) {
                try {
                    observer.observe(observation);
                } catch (Throwable e) {
                    log.warn("An observer of {} failed: {}", event, e.getMessage());
                    log.debug(e.getMessage(), e);
                }
            }
        }

        /** Goes on after the event; a turn stopped meanwhile goes to the cleanup instead (except after the last event). */
        private void invokeContinuation(TurnEvents event, FromEventCallback continuation) {
            if (token.isCancelled() && !event.isTerminal() && current != TurnStepType.CLEANUP) {
                jumpTo(TurnStepType.CLEANUP);
                return;
            }
            wrapCallback(continuation).run();
        }

        @Override
        public Runnable wrapCallback(FromEventCallback callback) {
            return () -> executor.execute(() -> {
                try (InRequestScope _ = new InRequestScope(requestAttributes); TurnJob.Left _ = job.enter()) {
                    callback.returnFromEvent();
                } catch (Throwable e) {
                    failed(e);
                }
            });
        }

        // ---------------------------------------------------------------------------------------------- the state

        @Override
        public String getRunId() {
            return runId;
        }

        @Override
        public Map<String, Object> getProperties() {
            return properties;
        }

        @Override
        public TurnInput getInput() {
            return input;
        }

        @Override
        public Joi getJoi() {
            return joi;
        }

        @Override
        public void setJoi(Joi joi) {
            this.joi = joi;
        }

        @Override
        public AI getAi() {
            return ai;
        }

        @Override
        public Mood getMood() {
            return mood;
        }

        @Override
        public void setMood(Mood mood) {
            this.mood = mood;
        }

        @Override
        public ChatTemplateData getTemplateData() {
            return templateData;
        }

        @Override
        public void setTemplateData(ChatTemplateData templateData) {
            this.templateData = templateData;
        }

        @Override
        public List<LLMChatMessage> getPayload() {
            return payload;
        }

        @Override
        public void setPayload(List<LLMChatMessage> payload) {
            this.payload = payload == null ? null : new ArrayList<>(payload);
        }

        @Override
        public FunctionSet getFunctions() {
            return functions;
        }

        @Override
        public void setFunctions(FunctionSet functions) {
            this.functions = functions == null ? FunctionSet.empty() : functions;
        }

        @Override
        public String getReply() {
            return reply;
        }

        @Override
        public void setReply(String reply) {
            this.reply = StringUtils.defaultString(reply);
        }

        @Override
        public List<ToolUsage> getToolUsages() {
            return toolUsages;
        }

        @Override
        public String getReasoning() {
            return reasoning;
        }

        @Override
        public void setReasoning(String reasoning) {
            this.reasoning = reasoning;
        }

        @Override
        public ChatMessage getMessage() {
            return message;
        }

        @Override
        public void setMessage(ChatMessage message) {
            this.message = message;
            if (message != null && told.compareAndSet(false, true)) {
                tell(joi.getId(), feed -> feed.finished(runId, message));
            }
        }

        @Override
        public Date getSent() {
            return sent;
        }

        @Override
        public long getFirstTokenMs() {
            return firstToken.get();
        }

        @Override
        public void markFirstToken() {
            if (firstToken.get() < 0) {
                firstToken.compareAndSet(-1, millisSinceStart());
            }
        }

        @Override
        public synchronized void addUsage(RoundUsage usage) {
            if (usage != null) {
                promptTokens += usage.promptTokens();
                completionTokens += usage.completionTokens();
            }
        }

        @Override
        public long getPromptTokens() {
            return promptTokens;
        }

        @Override
        public long getCompletionTokens() {
            return completionTokens;
        }

        @Override
        public long getTextMs() {
            return textMs;
        }

        @Override
        public void markTextDone() {
            textMs = millisSinceStart();
        }

        @Override
        public byte[] getAudio() {
            return audio;
        }

        @Override
        public void setAudio(byte[] audio) {
            this.audio = audio;
        }

        @Override
        public String getSpeechError() {
            return speechError;
        }

        @Override
        public void setSpeechError(String speechError) {
            this.speechError = speechError;
        }

        @Override
        public long getSpeechMs() {
            return speechMs;
        }

        @Override
        public void setSpeechMs(long speechMs) {
            this.speechMs = speechMs;
        }

        @Override
        public Listener getListener() {
            return listener;
        }

        @Override
        public CancellationToken getCancellationToken() {
            return token;
        }

        @Override
        public RequestAttributes getRequestAttributes() {
            return requestAttributes;
        }

        @Override
        public Executor getExecutor() {
            return scoped;
        }

        @Override
        public synchronized void fail(Throwable failure) {
            if (this.failure == null) {
                this.failure = failure;
            }
        }

        @Override
        public Throwable getFailure() {
            return failure;
        }

        private long millisSinceStart() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        }
    }

    /** What a listener sees: the turn, through the event. */
    private static final class EventView implements TurnControllerEvent {

        private final TurnEngine turn;
        private final TurnEvents event;
        private volatile boolean terminated;

        EventView(TurnEngine turn, TurnEvents event) {
            this.turn = turn;
            this.event = event;
        }

        @Override
        public TurnEvents getEvent() {
            return event;
        }

        @Override
        public Joi getJoi() {
            return turn.getJoi();
        }

        @Override
        public AI getAi() {
            return turn.getAi();
        }

        @Override
        public TurnInput getInput() {
            return turn.getInput();
        }

        @Override
        public Mood getMood() {
            return turn.getMood();
        }

        @Override
        public ChatTemplateData getTemplateData() {
            return turn.getTemplateData();
        }

        @Override
        public List<LLMChatMessage> getPayload() {
            return turn.getPayload();
        }

        @Override
        public void setPayload(List<LLMChatMessage> payload) {
            turn.setPayload(payload);
        }

        @Override
        public FunctionSet getFunctions() {
            return turn.getFunctions();
        }

        @Override
        public void setFunctions(FunctionSet functions) {
            turn.setFunctions(functions);
        }

        @Override
        public String getReply() {
            return turn.getReply();
        }

        @Override
        public void setReply(String reply) {
            turn.setReply(reply);
        }

        @Override
        public ChatMessage getMessage() {
            return turn.getMessage();
        }

        @Override
        public Map<String, Object> getProperties() {
            return turn.getProperties();
        }

        @Override
        public CancellationToken getCancellationToken() {
            return turn.getCancellationToken();
        }

        @Override
        public void terminateEvents() {
            terminated = true;
        }
    }

    /** What an observer sees: the turn, read-only. */
    private record Observation(TurnEngine turn, TurnEvents event) implements TurnObservation {

        @Override
        public TurnEvents getEvent() {
            return event;
        }

        @Override
        public String getRunId() {
            return turn.getRunId();
        }

        @Override
        public Joi getJoi() {
            return turn.getJoi();
        }

        @Override
        public AI getAi() {
            return turn.getAi();
        }

        @Override
        public TurnInput getInput() {
            return turn.getInput();
        }

        @Override
        public String getReply() {
            return turn.getReply();
        }

        @Override
        public ChatMessage getMessage() {
            return turn.getMessage();
        }

        @Override
        public Map<String, Object> getProperties() {
            return Collections.unmodifiableMap(turn.getProperties());
        }
    }
}
