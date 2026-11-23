package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.defaults.JoiDefaults;
import com.github.enerccio.joi.domain.chat.ChatService;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.collections.ChatMessageType;
import com.github.enerccio.joi.domain.collections.JoiTaskStatus;
import com.github.enerccio.joi.domain.collections.SummaryType;
import com.github.enerccio.joi.domain.events.JoiEvents;
import com.github.enerccio.joi.domain.memory.SummaryCoverage;
import com.github.enerccio.joi.domain.jobs.JoiJobs;
import com.github.enerccio.marginalia.domain.service.job.JobCallType;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.model.impl.Summary;
import com.github.enerccio.joi.domain.model.impl.settings.JoiUserSetting;
import com.github.enerccio.joi.domain.persona.JoiPromptField;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.JoiTaskService;
import com.github.enerccio.joi.domain.service.NodeService;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.domain.service.QueuedEventService;
import com.github.enerccio.joi.domain.service.SummarizerService;
import com.github.enerccio.joi.domain.service.SummaryService;
import com.github.enerccio.joi.domain.service.SummaryService.Lane;
import com.github.enerccio.joi.domain.summary.ModelRound;
import com.github.enerccio.joi.domain.summary.SummaryDays;
import com.github.enerccio.joi.domain.summary.SummaryDays.Span;
import com.github.enerccio.joi.domain.summary.SummaryPlan;
import com.github.enerccio.joi.domain.summary.SummaryPlan.Cover;
import com.github.enerccio.joi.domain.summary.SummaryPlan.Kind;
import com.github.enerccio.joi.domain.summary.SummaryPlan.Settings;
import com.github.enerccio.joi.domain.summary.SummaryPlan.Unit;
import com.github.enerccio.joi.domain.summary.SummaryQueue;
import com.github.enerccio.joi.domain.summary.SummaryText;
import com.github.enerccio.joi.domain.templates.DaySummaryTemplateData;
import com.github.enerccio.joi.domain.templates.PeriodSummaryTemplateData;
import com.github.enerccio.joi.domain.templates.SummaryEventTemplateData;
import com.github.enerccio.joi.domain.templates.SummaryTemplateData;
import com.github.enerccio.marginalia.bound.ApiRequestAttributes;
import com.github.enerccio.marginalia.bound.SessionPoint;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.domain.security.service.UserService;
import com.github.enerccio.marginalia.domain.service.AIService;
import com.github.enerccio.marginalia.domain.service.InferenceService;
import com.github.enerccio.marginalia.domain.service.InferenceServices;
import com.github.enerccio.marginalia.ui.components.ThreadCopyRequestAttributes.InRequestScope;
import com.github.enerccio.marginalia.utils.AppClock;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link SummarizerService}: the minute tick that looks at every assistant, the queue ({@link SummaryQueue}) and the work of
 * a job. What is due is decided by the pure {@link SummaryPlan}; this class reads the state for it, runs the jobs and writes
 * the summaries.
 */
public class SummarizerServiceImpl implements SummarizerService, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SummarizerServiceImpl.class);

    /** How often the assistants are looked at, in seconds; 0 for never (the tests ask for a look themselves). */
    public static final String TICK_PROPERTY = "joi.summary.tick-seconds";
    static final long TICK_SECONDS = 60;

    /** The kind of the stored messages that only answer a summary she was told about: they never make a summary themselves. */
    static final String NOISE = "node." + NodeServiceImpl.SUMMARY_CREATED;

    private static final Date EPOCH = new Date(0);
    private static final Date FAR = new Date(Long.MAX_VALUE / 4);
    /** Characters to the token, for the size of a conversation (no tokenizer is at hand: it is a guess, and a good enough one). */
    private static final int CHARS_PER_TOKEN = 4;
    /** What a model that does not say how long its context is is given of a conversation at once, in tokens. */
    private static final int DEFAULT_CHUNK_TOKENS = 24000;

    @Autowired
    private JoiService joiService;
    @Autowired
    private ChatMessageService chatMessageService;
    @Autowired
    private SummaryService summaryService;
    @Autowired
    private JoiTaskService joiTaskService;
    @Autowired
    private ChatService chatService;
    @Autowired
    private PersonaService personaService;
    @Autowired
    private AIService aiService;
    @Autowired
    private InferenceServices inferenceServices;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private QueuedEventService queuedEventService;
    @Autowired
    private UserService userService;
    @Autowired
    private User currentUser;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final SummaryQueue queue = new SummaryQueue(executor);
    private final Map<Long, List<Runnable>> watchers = new ConcurrentHashMap<>();
    private ScheduledExecutorService ticks;
    private Registration observer;

    @Override
    public void afterPropertiesSet() {
        queue.onChange(this::changed);
        long seconds = Long.getLong(TICK_PROPERTY, TICK_SECONDS);
        if (seconds <= 0) {
            return;
        }
        ticks = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Joi summaries");
            thread.setDaemon(true);
            return thread;
        });
        ticks.scheduleWithFixedDelay(this::tick, seconds, seconds, TimeUnit.SECONDS);
        // a conversation that grows is looked at after every turn, not only once a minute
        observer = chatService.addEventObserver(TurnEvents.AFTER_CLEANUP, observation -> {
            try {
                Joi joi = observation.getJoi();
                if (joi != null) {
                    User owner = userService.find(currentUser.getId());
                    Long id = joi.getId();
                    executor.execute(() -> inScope(owner, () -> {
                        try {
                            check(joiService.find(id));
                        } catch (Exception e) {
                            log.warn("The summaries of the assistant were not looked at: {}", e.getMessage());
                        }
                    }));
                }
            } catch (Exception e) {
                log.warn("The summaries of the assistant were not looked at: {}", e.getMessage());
            }
        });
    }

    @Override
    public void destroy() {
        if (observer != null) {
            observer.unregister();
        }
        if (ticks != null) {
            ticks.shutdownNow();
        }
        executor.shutdownNow();
    }

    // ----------------------------------------------------------------------------------------------------------------
    // what the user and the nodes see
    // ----------------------------------------------------------------------------------------------------------------

    @Override
    public List<JobInfo> jobs(Joi joi) {
        List<JobInfo> list = new ArrayList<>();
        queue.running(joi.getId()).forEach(job -> list.add(info(job, true)));
        queue.pending(joi.getId()).forEach(job -> list.add(info(job, false)));
        return list;
    }

    private static JobInfo info(SummaryQueue.Job job, boolean running) {
        SummaryPlan.Job plan = ((Queued) job).job;
        return new JobInfo(plan.kind(), plan.from() == null ? null : Date.from(plan.from()), plan.to() == null ? null : Date.from(plan.to()), running);
    }

    @Override
    public Date coveredUpTo(Joi joi) throws Exception {
        Instant upTo = SummaryPlan.coveredUpTo(covers(summaryService.findAllOf(joi)));
        return upTo == Instant.MIN ? EPOCH : Date.from(upTo);
    }

    @Override
    public String prompt(Joi joi) throws Exception {
        SummaryText text = new SummaryText(days(joi, personaService.getUserSetting()));
        List<SummaryText.Entry> entries = summaryService.findForPrompt(joi, EPOCH, FAR).stream()
                .filter(s -> s.getSummaryType() != SummaryType.ROLLING || StringUtils.isNotBlank(s.getSummary()))
                .map(s -> new SummaryText.Entry(s.getSummaryType(), s.isPartial(), s.getCoverFrom().toInstant(), s.getCoverTo().toInstant(), s.getSummary()))
                .toList();
        String list = text.list(entries);
        return list.isEmpty() ? null : list;
    }

    @Override
    public String period(Joi joi, Summary summary) throws Exception {
        return new SummaryText(days(joi, personaService.getUserSetting())).period(summary.getSummaryType(), summary.isPartial(),
                summary.getCoverFrom().toInstant(), summary.getCoverTo().toInstant());
    }

    @Override
    public List<ChatMessage> rangeUpTo(Joi joi, ChatMessage last) throws Exception {
        requireOwn(joi);
        return chatMessageService.findBetween(joi, coveredUpTo(joi), new Date(last.getSentAt().getTime() + 1));
    }

    @Override
    public void summarize(Joi joi, ChatMessage last) throws Exception {
        requireOwn(joi);
        if (rangeUpTo(joi, last).isEmpty()) {
            throw new IllegalArgumentException("There is nothing to summarize: the message is summarized already");
        }
        AI ai = summaryModel(joi);
        if (ai == null) {
            throw new IllegalStateException("There is no model to write the summary: choose a summary, auxiliary, deep reasoning or chat model");
        }
        submit(joi, ai, new SummaryPlan.Job(Kind.MANUAL, null, last.getSentAt().toInstant()));
    }

    @Override
    public Registration watch(Joi joi, Runnable onChange) throws Exception {
        requireOwn(joi);
        List<Runnable> list = watchers.computeIfAbsent(joi.getId(), id -> new CopyOnWriteArrayList<>());
        list.add(onChange);
        return () -> list.remove(onChange);
    }

    private void changed(long joiId) {
        List<Runnable> list = watchers.get(joiId);
        if (list != null) {
            for (Runnable watcher : list) {
                try {
                    watcher.run();
                } catch (RuntimeException e) {
                    log.warn("A watcher of the summaries failed: {}", e.getMessage());
                }
            }
        }
    }

    private void requireOwn(Joi joi) throws Exception {
        Joi stored = joi == null || joi.getId() == null ? null : joiService.find(joi.getId());
        if (stored == null || stored.isDeleted() || stored.getOwner() == null || !stored.getOwner().getId().equals(currentUser.getId())) {
            throw new SecurityException("Not an assistant of the user");
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // looking at the assistants
    // ----------------------------------------------------------------------------------------------------------------

    private void tick() {
        try {
            Map<Long, List<Long>> byOwner = new LinkedHashMap<>();
            for (Joi joi : joiService.findAll()) {
                if (!joi.isDeleted() && joi.getOwner() != null) {
                    byOwner.computeIfAbsent(joi.getOwner().getId(), id -> new ArrayList<>()).add(joi.getId());
                }
            }
            for (Map.Entry<Long, List<Long>> entry : byOwner.entrySet()) {
                User owner = userService.find(entry.getKey());
                if (owner == null) {
                    continue;
                }
                inScope(owner, () -> {
                    for (Long id : entry.getValue()) {
                        try {
                            check(joiService.find(id));
                        } catch (Exception e) {
                            log.warn("The summaries of the assistant {} were not looked at: {}", id, e.getMessage(), e);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            log.warn("The summaries were not looked at: {}", t.getMessage(), t);
        }
    }

    /** The name of the job in the log: whose summary and which. */
    private String label(Long joiId, SummaryPlan.Job job) {
        try {
            return joiService.name(joiService.find(joiId)) + " · " + job.kind().name().toLowerCase();
        } catch (Exception e) {
            return job.kind().name().toLowerCase();
        }
    }

    private void inScope(User owner, Runnable work) {
        try (ApiRequestAttributes attributes = new ApiRequestAttributes(owner, new SessionPoint());
             InRequestScope _ = new InRequestScope(attributes)) {
            work.run();
        } catch (Throwable t) {
            log.error("A summary job could not run", t);
        }
    }

    @Override
    public void check(Joi joi) {
        try {
            Joi stored = joi == null || joi.getId() == null ? null : joiService.find(joi.getId());
            if (stored == null || stored.isDeleted()) {
                return;
            }
            AI ai = summaryModel(stored);
            if (ai == null) {
                return;
            }
            Config config = config(stored);
            State state = state(stored, config);
            List<SummaryPlan.Job> due = SummaryPlan.plan(AppClock.now().toInstant(), config.settings(), config.days(), state.covers(),
                    state.units(), waiting(stored));
            for (SummaryPlan.Job job : due) {
                submit(stored, ai, job);
            }
        } catch (Exception e) {
            log.warn("The summaries of the assistant were not looked at: {}", e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Void> settle(Joi joi) {
        CompletableFuture<Void> settled = new CompletableFuture<>();
        if (joi == null || joi.getId() == null) {
            settled.complete(null);
            return settled;
        }
        Long id = joi.getId();
        check(joi);
        List<Runnable> list = watchers.computeIfAbsent(id, key -> new CopyOnWriteArrayList<>());
        Runnable[] watcher = new Runnable[1];
        watcher[0] = () -> {
            if (queue.pending(id).isEmpty() && queue.running(id).isEmpty()) {
                list.remove(watcher[0]);
                settled.complete(null);
            }
        };
        list.add(watcher[0]);
        // done already (nothing was due), or it ended before the watcher was there
        watcher[0].run();
        return settled;
    }

    private void submit(Joi joi, AI ai, SummaryPlan.Job job) throws Exception {
        Lane lane = summaryService.lane(ai);
        queue.submit(lane.key(), lane.limit(), new Queued(joi.getId(), currentUser.getId(), job));
    }

    /** A job in the queue: it runs in the request scope of the assistant's user. */
    private final class Queued implements SummaryQueue.Job {
        private final Long joiId;
        private final Long ownerId;
        private final SummaryPlan.Job job;

        Queued(Long joiId, Long ownerId, SummaryPlan.Job job) {
            this.joiId = joiId;
            this.ownerId = ownerId;
            this.job = job;
        }

        @Override
        public Long joiId() {
            return joiId;
        }

        @Override
        public int rank() {
            return job.kind().rank();
        }

        @Override
        public int priority() {
            return job.kind().priority();
        }

        @Override
        public String key() {
            return job.key();
        }

        @Override
        public void run() throws Exception {
            User owner = userService.find(ownerId);
            if (owner == null) {
                return;
            }
            try (ApiRequestAttributes attributes = new ApiRequestAttributes(owner, new SessionPoint());
                 InRequestScope _ = new InRequestScope(attributes)) {
                JoiJobs.run(JoiJobs.SUMMARY, label(joiId, job), JobCallType.LLM, () -> execute(joiId, job));
            }
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // the state and the settings
    // ----------------------------------------------------------------------------------------------------------------

    /** The settings along the chain (hers, the user's, the default) and the days they make. */
    private record Config(Settings settings, SummaryDays days, SummaryText text) {
    }

    /** What the rules look at: her summaries and the messages none of them covers. */
    private record State(List<Summary> summaries, List<Cover> covers, List<ChatMessage> messages, List<Unit> units) {
    }

    private Config config(Joi joi) throws Exception {
        JoiUserSetting setting = personaService.getUserSetting();
        int quiet = first(joi.getPauseMinutes(), setting.getPauseMinutes(), JoiDefaults.PAUSE_MINUTES);
        boolean auto = first(joi.getAutoSummary(), setting.getAutoSummary(), false);
        int tokens = first(joi.getAutoSummaryTokens(), setting.getAutoSummaryTokens(), JoiDefaults.AUTO_SUMMARY_TOKENS);
        // what she keeps unsummarized never passes what her chat model reads at all
        AI chat = chatService.chatModel(joi);
        if (chat != null && chat.getMaxContext() != null && chat.getMaxContext() > 0) {
            tokens = Math.min(tokens, chat.getMaxContext());
        }
        Settings settings = new Settings(quiet, auto, tokens,
                time(StringUtils.defaultIfBlank(joi.getWeeklySummaryTime(), setting.getWeeklySummaryTime()), JoiDefaults.SUMMARY_TIME),
                time(StringUtils.defaultIfBlank(joi.getMonthlySummaryTime(), setting.getMonthlySummaryTime()), JoiDefaults.SUMMARY_TIME),
                time(StringUtils.defaultIfBlank(joi.getYearlySummaryTime(), setting.getYearlySummaryTime()), JoiDefaults.SUMMARY_TIME));
        SummaryDays days = days(joi, setting);
        return new Config(settings, days, new SummaryText(days));
    }

    /** Her day: it begins at her day boundary (hers, the user's, the default) in the server's zone. */
    private static SummaryDays days(Joi joi, JoiUserSetting setting) {
        LocalTime boundary = time(StringUtils.defaultIfBlank(joi.getDayBoundary(), setting.getDayBoundary()), JoiDefaults.DAY_BOUNDARY);
        return new SummaryDays(boundary, AppClock.INSTANCE.getZone());
    }

    private static <T> T first(T own, T ofUser, T fallback) {
        return own != null ? own : ofUser != null ? ofUser : fallback;
    }

    private static LocalTime time(String text, String fallback) {
        try {
            return LocalTime.parse(StringUtils.defaultIfBlank(text, fallback).strip());
        } catch (DateTimeParseException e) {
            return LocalTime.parse(fallback);
        }
    }

    private State state(Joi joi, Config config) throws Exception {
        List<Summary> summaries = summaryService.findAllOf(joi);
        List<Cover> covers = covers(summaries);
        Instant upTo = SummaryPlan.coveredUpTo(covers);
        List<ChatMessage> messages = List.of();
        Date last = chatMessageService.findLastSentAt(List.of(joi)).get(joi.getId());
        // a conversation that is summarized to its end is not read again every minute
        if (last != null && (upTo == Instant.MIN || !last.toInstant().isBefore(upTo))) {
            messages = chatMessageService.findBetween(joi, upTo == Instant.MIN ? EPOCH : Date.from(upTo), FAR);
        }
        List<Unit> units = messages.stream().map(m -> new Unit(m.getSentAt().toInstant(), tokens(m), NOISE.equals(m.getEventKind()))).toList();
        return new State(summaries, covers, messages, units);
    }

    private static List<Cover> covers(List<Summary> summaries) {
        return summaries.stream().map(s -> new Cover(s.getSummaryType(), s.getCoverFrom().toInstant(), s.getCoverTo().toInstant(), s.isPartial())).toList();
    }

    /** About how many tokens the exchange takes in a prompt. */
    static int tokens(ChatMessage message) {
        int chars = StringUtils.length(message.getInput()) + StringUtils.length(message.getResponse());
        return (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    private boolean waiting(Joi joi) throws Exception {
        return !joiTaskService.findByStatus(joi, EnumSet.of(JoiTaskStatus.QUEUED, JoiTaskStatus.RUNNING, JoiTaskStatus.WAITING)).isEmpty();
    }

    /** Her summary model: hers, the user's; else the small-jobs model, the heavy one, the chat model, each hers first. */
    private AI summaryModel(Joi joi) throws Exception {
        JoiUserSetting setting = personaService.getUserSetting();
        List<AI> ais = aiService.findAllForUser();
        for (Long id : new Long[]{joi.getSummaryModel(), setting.getSummaryModel(), joi.getAuxiliaryModel(), setting.getAuxiliaryModel(),
                joi.getReasoningModel(), setting.getReasoningModel()}) {
            AI ai = id == null ? null : ais.stream().filter(a -> id.equals(a.getId())).findFirst().orElse(null);
            if (ai != null) {
                return ai;
            }
        }
        return chatService.chatModel(joi);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // a job
    // ----------------------------------------------------------------------------------------------------------------

    private void execute(Long joiId, SummaryPlan.Job job) throws Exception {
        Joi joi = joiService.find(joiId);
        if (joi == null || joi.isDeleted()) {
            return;
        }
        awaitIdle(joi);
        Config config = config(joi);
        State state = state(joi, config);
        if (job.kind() != Kind.MANUAL) {
            // what was due when it was queued may not be now: the user wrote again, a task was started, it was summarized already
            boolean due = SummaryPlan.plan(AppClock.now().toInstant(), config.settings(), config.days(), state.covers(), state.units(),
                    waiting(joi)).stream().anyMatch(j -> j.key().equals(job.key()));
            if (!due) {
                return;
            }
        }
        AI ai = summaryModel(joi);
        if (ai == null) {
            throw new IllegalStateException("There is no model to write the summary");
        }
        InferenceService service = inferenceServices.forAIOrThrow(ai);
        Work work = new Work(joi, ai, service, config, state);
        List<Summary> told = switch (job.kind()) {
            case DAY_ROLL -> work.rollDay(config.days().spanOf(SummaryType.DAY, job.from()));
            case QUIET -> work.summarizeOpen(null, false, "quiet");
            case AUTO -> work.summarizeOpen(null, true, "auto");
            case MANUAL -> work.summarizeOpen(job.to(), false, "manual");
            case WEEK, MONTH, YEAR -> work.rollPeriod(job.kind(), new Span(job.kind().periodType(), job.from(), job.to()));
        };
        for (Summary summary : told) {
            tell(joi, config, summary);
        }
        changed(joiId);
    }

    /** Waits for the reply that is going on (and what is queued behind it): a summary is made between her turns, not in one. */
    private void awaitIdle(Joi joi) throws Exception {
        CompletableFuture<Void> idle = new CompletableFuture<>();
        chatService.whenIdle(joi, () -> idle.complete(null));
        idle.get(10, TimeUnit.MINUTES);
    }

    /** The work of one job: the model it writes with and what it read. */
    private final class Work {
        private final Joi joi;
        private final AI ai;
        private final InferenceService service;
        private final Config config;
        private final State state;

        Work(Joi joi, AI ai, InferenceService service, Config config, State state) {
            this.joi = joi;
            this.ai = ai;
            this.service = service;
            this.config = config;
            this.state = state;
        }

        // ------------------------------------------------------------------------------------ from the messages

        /**
         * Summarizes the messages no summary covers (the ones up to {@code limit}, or the oldest of them when the
         * conversation grew): a summary of each day they were sent in, and of each part of a day too long for the model.
         */
        List<Summary> summarizeOpen(Instant limit, boolean oldest, String origin) throws Exception {
            List<ChatMessage> open = new ArrayList<>(state.messages());
            if (limit != null) {
                open.removeIf(m -> m.getSentAt().toInstant().isAfter(limit));
            }
            if (oldest) {
                open = oldest(open);
            }
            return summarize(open, origin, limit != null);
        }

        /** The oldest messages that leave her about half of what she may keep, never the newest one. */
        private List<ChatMessage> oldest(List<ChatMessage> open) {
            if (open.isEmpty()) {
                return open;
            }
            int keep = Math.max(1, config.settings().autoTokens() / 2);
            int start = open.size() - 1;
            long sum = tokens(open.get(start));
            while (start > 0 && sum + tokens(open.get(start - 1)) <= keep) {
                start--;
                sum += tokens(open.get(start));
            }
            return open.subList(0, start);
        }

        private List<Summary> summarize(List<ChatMessage> messages, String origin, boolean evenReactions) throws Exception {
            Map<Instant, List<ChatMessage>> byDay = new LinkedHashMap<>();
            for (ChatMessage message : messages) {
                byDay.computeIfAbsent(config.days().spanOf(SummaryType.DAY, message.getSentAt().toInstant()).from(), d -> new ArrayList<>()).add(message);
            }
            List<Summary> made = new ArrayList<>();
            for (List<ChatMessage> day : byDay.values()) {
                // the answers to summaries are not worth a summary of their own, unless the user asked for it
                if (!evenReactions && day.stream().allMatch(m -> NOISE.equals(m.getEventKind()))) {
                    continue;
                }
                for (List<ChatMessage> part : parts(day)) {
                    made.add(summarizeMessages(part, origin));
                }
            }
            return made;
        }

        /** The messages of a day in parts the model can read at once. */
        private List<List<ChatMessage>> parts(List<ChatMessage> day) {
            int budget = ai.getMaxContext() != null && ai.getMaxContext() > 0 ? ai.getMaxContext() / 2 : DEFAULT_CHUNK_TOKENS;
            List<List<ChatMessage>> parts = new ArrayList<>();
            List<ChatMessage> part = new ArrayList<>();
            long sum = 0;
            for (ChatMessage message : day) {
                int size = tokens(message);
                if (!part.isEmpty() && sum + size > budget) {
                    parts.add(part);
                    part = new ArrayList<>();
                    sum = 0;
                }
                part.add(message);
                sum += size;
            }
            if (!part.isEmpty()) {
                parts.add(part);
            }
            return parts;
        }

        private Summary summarizeMessages(List<ChatMessage> messages, String origin) throws Exception {
            Instant from = messages.getFirst().getSentAt().toInstant();
            Instant last = messages.getLast().getSentAt().toInstant();
            SummaryTemplateData data = new SummaryTemplateData();
            data.setConversation(transcript(messages));
            data.setSpan(config.text().span(from, last));
            ModelRound.Result result = ModelRound.complete(service, personaService.render(joi, JoiPromptField.SUMMARY_PROMPT, data), () -> false);
            Summary summary = new Summary();
            summary.setJoi(joi);
            summary.setSummaryType(SummaryType.DAY);
            summary.setCoverFrom(Date.from(from));
            summary.setCoverTo(Date.from(last.plusMillis(1)));
            summary.setPartial(true);
            summary.setOrigin(origin);
            summary.setMessageCount(messages.size());
            summary.setPrivateMode(messages.stream().anyMatch(ChatMessage::isPrivateMode));
            return keep(summary, result);
        }

        private String transcript(List<ChatMessage> messages) throws Exception {
            String character = joiService.name(joi);
            String user = StringUtils.defaultIfBlank(joi.getUserName(), personaService.userName());
            StringBuilder out = new StringBuilder();
            for (ChatMessage message : messages) {
                String when = config.text().moment(message.getSentAt().toInstant());
                if (StringUtils.isNotBlank(message.getInput()) || !message.imageUuids().isEmpty()) {
                    out.append('[').append(when).append("] ");
                    if (message.getType() == ChatMessageType.EVENT) {
                        out.append("(event").append(StringUtils.isBlank(message.getEventKind()) ? "" : " " + message.getEventKind()).append(") ");
                    } else {
                        out.append(user).append(": ");
                    }
                    out.append(StringUtils.defaultString(message.getInput()).strip());
                    // the image is not in a summary: what a model that sees wrote about it, else that there was one
                    List<String> notes = message.imageNoteTexts();
                    for (int i = 0; i < message.imageUuids().size(); i++) {
                        out.append(" [attached an image").append(i < notes.size() && StringUtils.isNotBlank(notes.get(i)) ? ": " + notes.get(i).strip() : "").append(']');
                    }
                    out.append('\n');
                }
                if (StringUtils.isNotBlank(message.getResponse())) {
                    out.append(character).append(": ").append(message.getResponse().strip()).append('\n');
                }
            }
            return out.toString().strip();
        }

        // ------------------------------------------------------------------------------------ from summaries

        /**
         * A day that ended: what is left of it is summarized, and its parts become one day summary. One part alone only
         * takes the whole day for its time (no model is asked).
         */
        List<Summary> rollDay(Span day) throws Exception {
            List<ChatMessage> left = state.messages().stream().filter(m -> day.contains(m.getSentAt().toInstant(), m.getSentAt().toInstant())).toList();
            List<Summary> made = new ArrayList<>(summarize(left, "roll", false));
            // the parts are what the day has now, the ones just made too
            List<Summary> parts = summaryService.findAllOf(joi).stream().filter(s -> s.getSummaryType() == SummaryType.DAY && s.isPartial()
                    && day.contains(s.getCoverFrom().toInstant(), s.getCoverTo().toInstant())).toList();
            if (parts.isEmpty()) {
                return List.of();
            }
            if (parts.size() == 1) {
                Summary only = parts.getFirst();
                only.setCoverFrom(Date.from(day.from()));
                only.setCoverTo(Date.from(day.to()));
                only.setPartial(false);
                Summary whole = summaryService.save(only);
                // a part that was only just made is news to her; one she was told about already is not
                return made.isEmpty() ? List.of() : List.of(whole);
            }
            PeriodData data = new PeriodData(parts, day);
            DaySummaryTemplateData template = new DaySummaryTemplateData();
            template.setDay(config.text().period(SummaryType.DAY, false, day.from(), day.to()));
            template.setSummaries(data.text(config));
            ModelRound.Result result = ModelRound.complete(service, personaService.render(joi, JoiPromptField.DAY_SUMMARY_PROMPT, template), () -> false);
            Summary merged = new Summary();
            merged.setJoi(joi);
            merged.setSummaryType(SummaryType.DAY);
            merged.setCoverFrom(Date.from(day.from()));
            merged.setCoverTo(Date.from(day.to()));
            merged.setOrigin("roll");
            merged.setMessageCount(parts.stream().mapToInt(p -> p.getMessageCount() == null ? 0 : p.getMessageCount()).sum());
            merged.setPrivateMode(parts.stream().anyMatch(Summary::isPrivateMode));
            Summary saved = keep(merged, result);
            for (Summary part : parts) {
                summaryService.delete(part, false);
            }
            return List.of(saved);
        }

        /** A week, a month or a year that ended: its summaries (the longest of each time, the shorter ones inside it are covered) in one. */
        List<Summary> rollPeriod(Kind kind, Span period) throws Exception {
            SummaryType type = kind.periodType();
            List<Summary> inside = summaryService.findAllOf(joi).stream().filter(s -> s.getSummaryType() != SummaryType.ROLLING
                    && s.getSummaryType().getRank() < type.getRank() && !s.isPartial()
                    && period.contains(s.getCoverFrom().toInstant(), s.getCoverTo().toInstant())).toList();
            List<Summary> sources = SummaryCoverage.uncovered(inside);
            if (sources.isEmpty()) {
                return List.of();
            }
            Summary summary = new Summary();
            summary.setJoi(joi);
            summary.setSummaryType(type);
            summary.setCoverFrom(Date.from(period.from()));
            summary.setCoverTo(Date.from(period.to()));
            summary.setOrigin("roll");
            summary.setPrivateMode(sources.stream().anyMatch(Summary::isPrivateMode));
            if (sources.size() == 1) {
                // nothing to put together: the one summary takes the whole time
                summary.setSummary(sources.getFirst().getSummary());
                summary.setModelUsed(sources.getFirst().getModelUsed());
                summaryService.save(summary);
                return List.of();
            }
            PeriodSummaryTemplateData data = new PeriodSummaryTemplateData();
            data.setPeriod(config.text().period(type, false, period.from(), period.to()));
            data.setSummaries(new PeriodData(sources, period).text(config));
            JoiPromptField field = switch (kind) {
                case WEEK -> JoiPromptField.WEEK_SUMMARY_PROMPT;
                case MONTH -> JoiPromptField.MONTH_SUMMARY_PROMPT;
                default -> JoiPromptField.YEAR_SUMMARY_PROMPT;
            };
            return List.of(keep(summary, ModelRound.complete(service, personaService.render(joi, field, data), () -> false)));
        }

        private Summary keep(Summary summary, ModelRound.Result result) throws Exception {
            summary.setSummary(result.text());
            summary.setReasoning(StringUtils.defaultIfBlank(result.reasoning(), null));
            summary.setSummaryTokens(result.completionTokens());
            summary.setModelUsed(ai.getName());
            return summaryService.save(summary);
        }
    }

    /** The summaries a longer one is made of, as the prompt reads them. */
    private record PeriodData(List<Summary> summaries, Span period) {

        String text(Config config) {
            return config.text().list(summaries.stream().sorted(java.util.Comparator.comparing(Summary::getCoverFrom))
                    .map(s -> new SummaryText.Entry(s.getSummaryType(), s.isPartial(), s.getCoverFrom().toInstant(), s.getCoverTo().toInstant(), s.getSummary()))
                    .toList());
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // telling her
    // ----------------------------------------------------------------------------------------------------------------

    /** She is told what was summarized, in one event: through a node that is connected, else when one connects. */
    private void tell(Joi joi, Config config, Summary summary) {
        try {
            SummaryEventTemplateData data = new SummaryEventTemplateData();
            data.setKind(SummaryText.name(summary.getSummaryType(), summary.isPartial()));
            data.setSpan(config.text().period(summary.getSummaryType(), summary.isPartial(), summary.getCoverFrom().toInstant(), summary.getCoverTo().toInstant()));
            data.setDigest(summary.getSummary());
            String text = personaService.render(joi, JoiPromptField.SUMMARY_EVENT, data).strip();
            if (!nodeService.tell(joi, text, NodeServiceImpl.SUMMARY_CREATED)) {
                JsonObject payload = new JsonObject();
                payload.addProperty("summary", summary.getUuid());
                payload.addProperty("type", summary.getSummaryType().name().toLowerCase());
                payload.addProperty("partial", summary.isPartial());
                QueuedEvent event = new QueuedEvent();
                event.setJoi(joi);
                event.setKind(JoiEvents.SUMMARY_CREATED);
                event.setOccurredAt(AppClock.now());
                event.setText(text);
                event.setPayload(payload.toString());
                queuedEventService.save(event);
            }
        } catch (Exception e) {
            log.warn("She was not told that the summary {} was made: {}", summary.getUuid(), e.getMessage(), e);
        }
    }
}
