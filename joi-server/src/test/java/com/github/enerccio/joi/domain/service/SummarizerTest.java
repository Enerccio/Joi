package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.defaults.JoiDefaults;
import com.github.enerccio.joi.domain.chat.turn.TurnInput;
import com.github.enerccio.joi.domain.collections.ChatMessageType;
import com.github.enerccio.joi.domain.collections.JoiTaskStatus;
import com.github.enerccio.joi.domain.collections.SummaryType;
import com.github.enerccio.joi.domain.collections.TextLLMType;
import com.github.enerccio.joi.domain.chat.ChatService;
import com.github.enerccio.joi.domain.events.JoiEvents;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.JoiTask;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.model.impl.Summary;
import com.github.enerccio.joi.domain.summary.SummaryDays;
import com.github.enerccio.joi.domain.summary.SummaryDays.Span;
import com.github.enerccio.joi.domain.summary.SummaryPlan.Kind;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.domain.model.impl.Gpu;
import com.github.enerccio.marginalia.domain.model.impl.GpuLock;
import com.github.enerccio.marginalia.domain.service.AIService;
import com.github.enerccio.marginalia.domain.service.GpuService;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.github.enerccio.marginalia.test.llm.MockLLMResponse;
import com.github.enerccio.marginalia.utils.AppClock;
import org.junit.jupiter.api.BeforeEach;
import com.github.enerccio.marginalia.domain.service.job.FinishedJob;
import com.github.enerccio.marginalia.domain.service.job.JobCallType;
import com.github.enerccio.marginalia.domain.service.job.JobStatus;
import com.github.enerccio.marginalia.domain.service.job.Jobs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Date;
import java.util.function.Consumer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The summaries end to end, with the fake model: after a pause, when the conversation grows, by hand, a day that ended, a week;
 * the event she is told; and the lane of the model's queue. The minute tick is off in tests ({@code joi.summary.tick-seconds}):
 * a test asks for a look with {@code check}.
 */
class SummarizerTest extends PlatformTestBase {

    @Autowired
    private SummarizerService summarizer;
    @Autowired
    private SummaryService summaries;
    @Autowired
    private ChatMessageService chatMessages;
    @Autowired
    private JoiService joiService;
    @Autowired
    private JoiTaskService tasks;
    @Autowired
    private QueuedEventService queuedEvents;
    @Autowired
    private ChatService chatService;
    @Autowired
    private AIService aiService;
    @Autowired
    private GpuService gpuService;

    private final SummaryDays days = new SummaryDays(LocalTime.parse(JoiDefaults.DAY_BOUNDARY), AppClock.INSTANCE.getZone());
    private Joi joi;
    private AI model;

    @BeforeEach
    void setUp() throws Exception {
        login();
        model = createAI();
        joi = joiService.create("Mira");
        joi.setChatModel(model.getId());
        joi = joiService.save(joi);
    }

    // ----------------------------------------------------------------------------------------------------------------

    private static Instant ago(Duration duration) {
        return AppClock.now().toInstant().minus(duration);
    }

    private ChatMessage message(Instant at, String input, String response) throws Exception {
        ChatMessage message = new ChatMessage();
        message.setJoi(joi);
        message.setType(ChatMessageType.USER);
        message.setSentAt(Date.from(at));
        message.setInput(input);
        message.setResponse(response);
        return chatMessages.save(message);
    }

    private Summary summary(SummaryType type, Instant from, Instant to, boolean partial, String text) throws Exception {
        Summary summary = new Summary();
        summary.setJoi(joi);
        summary.setSummaryType(type);
        summary.setCoverFrom(Date.from(from));
        summary.setCoverTo(Date.from(to));
        summary.setPartial(partial);
        summary.setSummary(text);
        return summaries.save(summary);
    }

    private List<Summary> all() throws Exception {
        return summaries.findAllOf(joi);
    }

    private List<QueuedEvent> told() throws Exception {
        return queuedEvents.findWaiting(joi).stream().filter(e -> JoiEvents.SUMMARY_CREATED.equals(e.getKind())).toList();
    }

    private void answers(String... texts) {
        for (String text : texts) {
            llm.enqueue(MockLLMResponse.text(text));
        }
    }

    private <T> T await(Callable<T> found, String what) throws Exception {
        long until = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < until) {
            T value = found.call();
            if (value != null) {
                return value;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Waited for " + what);
    }

    private void awaitNoJobs() throws Exception {
        await(() -> summarizer.jobs(joi).isEmpty() ? Boolean.TRUE : null, "the summary jobs to end");
    }

    private String state() throws Exception {
        return all().stream().map(s -> s.getId() + ":" + s.isPartial() + ":" + s.getCoverFrom().getTime() + ":" + s.getCoverTo().getTime())
                .collect(Collectors.joining(",")) + "/" + llm.getCompletionRequests().size();
    }

    /** Looks at her again and again until nothing more is due. */
    private void settle() throws Exception {
        for (int round = 0; round < 10; round++) {
            String before = state();
            summarizer.check(joi);
            awaitNoJobs();
            if (before.equals(state())) {
                return;
            }
        }
        throw new AssertionError("The summaries never settled");
    }

    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void aPauseInTheConversationIsSummarizedAndSheIsTold() throws Exception {
        message(ago(Duration.ofMinutes(180)), "Hello there", "Hi, I am Mira.");
        message(ago(Duration.ofMinutes(175)), "What is the plan for Friday?", "A dentist at ten.");
        answers("They met and planned Friday: a dentist at ten.");

        settle();

        List<Summary> made = all();
        assertThat(made).isNotEmpty();
        assertThat(made).allSatisfy(s -> {
            assertThat(s.getSummaryType()).isEqualTo(SummaryType.DAY);
            assertThat(s.isPartial()).isTrue();
            assertThat(s.getOrigin()).isEqualTo("quiet");
            assertThat(s.getSummary()).isEqualTo("They met and planned Friday: a dentist at ten.");
            assertThat(s.getModelUsed()).isEqualTo(model.getName());
        });
        assertThat(made.stream().mapToInt(Summary::getMessageCount).sum()).isEqualTo(2);
        // what the model read: the transcript, with the names and the time
        String prompt = llm.getCompletionRequests().getFirst().getPromptText();
        assertThat(prompt).contains("Hello there").contains("What is the plan for Friday?").contains("A dentist at ten.").contains("Mira:");
        // no node is connected, so she is told when one connects
        assertThat(told()).hasSize(made.size());
        assertThat(told().getFirst().getText()).contains("dentist at ten").contains("[Memory]");
        assertThat(told().getFirst().getPayload()).contains(made.getFirst().getUuid());
        // asked again nothing is summarized twice
        int requests = llm.getCompletionRequests().size();
        summarizer.check(joi);
        awaitNoJobs();
        assertThat(llm.getCompletionRequests()).hasSize(requests);
    }

    @Test
    void aSummaryIsAJobInTheAdministratorsLog() throws Exception {
        List<FinishedJob> jobs = new CopyOnWriteArrayList<>();
        Consumer<FinishedJob> sink = jobs::add;
        Jobs.addSink(sink);
        try {
            message(ago(Duration.ofMinutes(180)), "Hello there", "Hi, I am Mira.");
            message(ago(Duration.ofMinutes(175)), "What is the plan for Friday?", "A dentist at ten.");
            answers("They met and planned Friday.");

            settle();

            List<FinishedJob> summaries = jobs.stream().filter(job -> "summary".equals(job.kind())).toList();
            assertThat(summaries).hasSize(1);
            FinishedJob job = summaries.getFirst();
            assertThat(job.label()).contains("Mira").contains("quiet");
            assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
            // the user of the assistant, though the work runs on a thread of the server's own
            assertThat(job.userId()).isNotNull();
            assertThat(job.login()).isNotBlank();
            // the request to the model is a call of the job, not a job of its own: and no text is kept
            assertThat(job.calls()).hasSize(1);
            assertThat(job.calls().getFirst().type()).isEqualTo(JobCallType.LLM);
            assertThat(job.calls().getFirst().backend()).isEqualTo(model.getName());
            assertThat(jobs.stream().filter(j -> "untagged".equals(j.kind()))).isEmpty();
        } finally {
            Jobs.removeSink(sink);
        }
    }

    @Test
    void theSummaryStandsInHerPromptInPlaceOfTheMessagesItCovers() throws Exception {
        message(ago(Duration.ofMinutes(180)), "My cat is called Biscuit", "A lovely name.");
        answers("The user has a cat called Biscuit.");
        settle();
        assertThat(summarizer.prompt(joi)).contains("The user has a cat called Biscuit.");

        llm.enqueue(MockLLMResponse.text("Hello again."));
        chatService.chat(joi, new TurnInput("Hi", null, false, false), null).get(30, TimeUnit.SECONDS);
        String body = llm.getCompletionRequests().getLast().getRawBody();
        assertThat(body).contains("WHAT HAPPENED BEFORE").contains("The user has a cat called Biscuit.");
        // the messages themselves are not in the history any more
        assertThat(body).doesNotContain("A lovely name.");
        // the summary and her turn each have their own cache key (X-Session-ID)
        String summaryKey = llm.getCompletionRequests().getFirst().getHeader(com.github.enerccio.marginalia.domain.service.InferenceSession.HEADER);
        String chatKey = llm.getCompletionRequests().getLast().getHeader(com.github.enerccio.marginalia.domain.service.InferenceSession.HEADER);
        assertThat(summaryKey).isNotNull();
        assertThat(chatKey).isNotNull().isNotEqualTo(summaryKey);
    }

    @Test
    void theConversationIsLeftAloneWhileTheUserIsStillTalking() throws Exception {
        message(ago(Duration.ofMinutes(10)), "Still here", "Yes.");
        summarizer.check(joi);
        awaitNoJobs();
        assertThat(all()).isEmpty();
        assertThat(llm.getCompletionRequests()).isEmpty();
    }

    @Test
    void aTaskThatIsOpenHoldsTheSummaryBack() throws Exception {
        message(ago(Duration.ofMinutes(180)), "Look it up", "On it.");
        JoiTask task = new JoiTask();
        task.setJoi(joi);
        task.setRole(TextLLMType.DEEP_REASONING);
        task.setTool("web_search");
        task.setTitle("a question");
        task.setStatus(JoiTaskStatus.WAITING);
        task = tasks.save(task);

        summarizer.check(joi);
        awaitNoJobs();
        assertThat(all()).isEmpty();

        task.setStatus(JoiTaskStatus.DONE);
        tasks.save(task);
        answers("She was asked to look something up.");
        settle();
        assertThat(all()).isNotEmpty();
    }

    @Test
    void thePauseIsTheOneInHerSettingsAndZeroTurnsItOff() throws Exception {
        message(ago(Duration.ofMinutes(180)), "Hello", "Hi.");
        joi.setPauseMinutes(0);
        joi = joiService.save(joi);
        summarizer.check(joi);
        awaitNoJobs();
        assertThat(all()).isEmpty();

        // her own setting comes before the user's: a pause longer than the silence is no pause yet
        joi.setPauseMinutes(240);
        joi = joiService.save(joi);
        summarizer.check(joi);
        awaitNoJobs();
        assertThat(all()).isEmpty();
    }

    @Test
    void whenTheConversationGrowsTheOldestMessagesAreSummarized() throws Exception {
        joi.setAutoSummary(true);
        joi.setAutoSummaryTokens(1000);
        joi = joiService.save(joi);
        String text = "x".repeat(2400);
        // 600 tokens each: three are 1800, over the 1000 she may keep; the last one stays and so does about half of the limit
        message(ago(Duration.ofMinutes(30)), text, "ok");
        message(ago(Duration.ofMinutes(29)), text, "ok");
        message(ago(Duration.ofMinutes(28)), text, "ok");
        answers("Long talk, summarized.");

        summarizer.check(joi);
        await(() -> all().isEmpty() ? null : Boolean.TRUE, "the summary");
        awaitNoJobs();

        List<Summary> made = all();
        assertThat(made.stream().mapToInt(Summary::getMessageCount).sum()).isEqualTo(2);
        assertThat(made).allSatisfy(s -> assertThat(s.getOrigin()).isEqualTo("auto"));
        assertThat(told()).isNotEmpty();
        // what is left is below the limit
        assertThat(summarizer.coveredUpTo(joi)).isAfter(Date.from(ago(Duration.ofMinutes(29)).minusSeconds(1)));
    }

    @Test
    void autoSummaryIsOffUnlessAsked() throws Exception {
        String text = "x".repeat(2400);
        message(ago(Duration.ofMinutes(30)), text, "ok");
        message(ago(Duration.ofMinutes(29)), text, "ok");
        message(ago(Duration.ofMinutes(28)), text, "ok");
        summarizer.check(joi);
        awaitNoJobs();
        assertThat(all()).isEmpty();
    }

    @Test
    void theUserCanSummarizeUpToAMessageWithoutGaps() throws Exception {
        ChatMessage first = message(ago(Duration.ofMinutes(20)), "one", "1");
        ChatMessage second = message(ago(Duration.ofMinutes(19)), "two", "2");
        ChatMessage third = message(ago(Duration.ofMinutes(18)), "three", "3");
        // from the start to the second message: the first is in it though nobody chose it
        assertThat(summarizer.rangeUpTo(joi, second)).extracting(ChatMessage::getId).containsExactly(first.getId(), second.getId());
        answers("The first two.");

        summarizer.summarize(joi, second);
        await(() -> all().isEmpty() ? null : Boolean.TRUE, "the summary");
        awaitNoJobs();

        List<Summary> made = all();
        assertThat(made).hasSize(1);
        assertThat(made.getFirst().getOrigin()).isEqualTo("manual");
        assertThat(made.getFirst().getMessageCount()).isEqualTo(2);
        assertThat(made.getFirst().getCoverTo().getTime()).isEqualTo(second.getSentAt().getTime() + 1);
        assertThat(told()).hasSize(1);
        // the next one starts where this one ended
        assertThat(summarizer.rangeUpTo(joi, third)).extracting(ChatMessage::getId).containsExactly(third.getId());
        assertThatThrownBy(() -> summarizer.summarize(joi, first)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDayThatEndedBecomesOneDaySummary() throws Exception {
        Span day = days.spanOf(SummaryType.DAY, ago(Duration.ofDays(3)));
        Instant noon = day.from().plus(Duration.ofHours(8));
        message(noon, "Morning talk", "Yes.");
        message(noon.plus(Duration.ofHours(1)), "Another one", "Sure.");
        answers("A day of two short talks.");

        settle();

        List<Summary> made = all().stream().filter(s -> s.getCoverFrom().toInstant().equals(day.from())).toList();
        assertThat(made).hasSize(1);
        assertThat(made.getFirst().isPartial()).isFalse();
        assertThat(made.getFirst().getCoverTo().toInstant()).isEqualTo(day.to());
        assertThat(made.getFirst().getSummary()).isEqualTo("A day of two short talks.");
        assertThat(told()).hasSize(1);
    }

    @Test
    void thePartsOfADayAreMergedIntoOne() throws Exception {
        Span day = days.spanOf(SummaryType.DAY, ago(Duration.ofDays(3)));
        Summary morning = summary(SummaryType.DAY, day.from().plus(Duration.ofHours(3)), day.from().plus(Duration.ofHours(4)), true, "In the morning they planned a trip.");
        Summary evening = summary(SummaryType.DAY, day.from().plus(Duration.ofHours(14)), day.from().plus(Duration.ofHours(15)), true, "In the evening they booked it.");
        answers("They planned a trip and booked it.");

        settle();

        List<Summary> remaining = all();
        assertThat(remaining).hasSize(1);
        Summary merged = remaining.getFirst();
        assertThat(merged.isPartial()).isFalse();
        assertThat(merged.getCoverFrom().toInstant()).isEqualTo(day.from());
        assertThat(merged.getCoverTo().toInstant()).isEqualTo(day.to());
        assertThat(merged.getSummary()).isEqualTo("They planned a trip and booked it.");
        assertThat(merged.getId()).isNotIn(morning.getId(), evening.getId());
        String prompt = llm.getCompletionRequests().getFirst().getPromptText();
        assertThat(prompt).contains("In the morning they planned a trip.").contains("In the evening they booked it.");
        assertThat(told()).hasSize(1);
    }

    @Test
    void aSingleOnlyPartOfADayJustTakesTheWholeDay() throws Exception {
        Span day = days.spanOf(SummaryType.DAY, ago(Duration.ofDays(3)));
        Summary only = summary(SummaryType.DAY, day.from().plus(Duration.ofHours(3)), day.from().plus(Duration.ofHours(4)), true, "A quiet day.");

        settle();

        // no model was asked
        assertThat(llm.getCompletionRequests()).isEmpty();
        Summary whole = summaries.find(only.getId());
        assertThat(whole.isPartial()).isFalse();
        assertThat(whole.getCoverFrom().toInstant()).isEqualTo(day.from());
        assertThat(whole.getCoverTo().toInstant()).isEqualTo(day.to());
        assertThat(told()).isEmpty();
    }

    @Test
    void aWeekThatEndedIsSummarizedAndItsDaysStayButAreCovered() throws Exception {
        Span week = days.spanOf(SummaryType.WEEK, ago(Duration.ofDays(14)));
        Span monday = days.spanOf(SummaryType.DAY, week.from().plus(Duration.ofHours(6)));
        Span tuesday = days.next(monday);
        Span newer = days.spanOf(SummaryType.DAY, week.to().plus(Duration.ofHours(30)));
        Summary d1 = summary(SummaryType.DAY, monday.from(), monday.to(), false, "Monday: they fixed the bike.");
        Summary d2 = summary(SummaryType.DAY, tuesday.from(), tuesday.to(), false, "Tuesday: they rode it.");
        Summary d3 = summary(SummaryType.DAY, newer.from(), newer.to(), false, "The week after: a quiet day.");
        for (int i = 0; i < 6; i++) {
            answers("The week they fixed the bike and rode it.");
        }

        settle();

        Summary weekly = all().stream().filter(s -> s.getSummaryType() == SummaryType.WEEK && s.getCoverFrom().toInstant().equals(week.from())).findFirst()
                .orElseThrow();
        assertThat(weekly.getCoverTo().toInstant()).isEqualTo(week.to());
        assertThat(weekly.getSummary()).isEqualTo("The week they fixed the bike and rode it.");
        String prompt = llm.getCompletionRequests().stream().map(r -> r.getPromptText()).filter(p -> p.contains("Monday: they fixed the bike.")).findFirst()
                .orElseThrow();
        assertThat(prompt).contains("Tuesday: they rode it.").doesNotContain("The week after");
        // the days are still there, and the prompt shows the week and the day after it
        assertThat(all()).extracting(Summary::getId).contains(d1.getId(), d2.getId(), d3.getId());
        List<Summary> shown = summaries.findForPrompt(joi, new Date(0), new Date(Long.MAX_VALUE / 4));
        assertThat(shown).extracting(Summary::getId).contains(weekly.getId(), d3.getId()).doesNotContain(d1.getId(), d2.getId());
        assertThat(shown).anyMatch(s -> s.getSummaryType() == SummaryType.DAY);
    }

    @Test
    void theWeekThatIsGoingOnIsNeverSummarized() throws Exception {
        Span week = days.spanOf(SummaryType.WEEK, AppClock.now().toInstant());
        Span day = days.spanOf(SummaryType.DAY, week.from().plus(Duration.ofHours(6)));
        summary(SummaryType.DAY, day.from(), day.to(), false, "A day of this week.");
        settle();
        assertThat(all()).extracting(Summary::getSummaryType).containsOnly(SummaryType.DAY);
        assertThat(llm.getCompletionRequests()).isEmpty();
    }

    @Test
    void aWeekWaitsForADaySummaryOfTheTimeAfterIt() throws Exception {
        Span week = days.spanOf(SummaryType.WEEK, ago(Duration.ofDays(14)));
        Span monday = days.spanOf(SummaryType.DAY, week.from().plus(Duration.ofHours(6)));
        summary(SummaryType.DAY, monday.from(), monday.to(), false, "Monday.");
        Span tuesday = days.next(monday);
        summary(SummaryType.DAY, tuesday.from(), tuesday.to(), false, "Tuesday.");
        // nothing after the week: there must always be a day summary left
        settle();
        assertThat(all()).extracting(Summary::getSummaryType).containsOnly(SummaryType.DAY);
        assertThat(llm.getCompletionRequests()).isEmpty();
    }

    @Test
    void aSummaryThatReactsToASummaryDoesNotStartAChain() throws Exception {
        ChatMessage reaction = message(ago(Duration.ofMinutes(180)), "[Memory] the day was summarized", "Noted.");
        reaction.setType(ChatMessageType.EVENT);
        reaction.setEventKind("node.summary_created");
        chatMessages.save(reaction);
        summarizer.check(joi);
        awaitNoJobs();
        assertThat(all()).isEmpty();
        assertThat(llm.getCompletionRequests()).isEmpty();
    }

    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void aModelWithoutALockHasItsOwnLaneWithItsConnections() throws Exception {
        model.setMaxConcurrentRequests(3);
        model = aiService.save(model);
        assertThat(summaries.lane(model).key()).startsWith("ai:");
        assertThat(summaries.lane(model).limit()).isEqualTo(3);
        AI plain = createAI();
        assertThat(summaries.lane(plain).limit()).isEqualTo(1);
        assertThat(summaries.lane(plain).key()).isNotEqualTo(summaries.lane(model).key());
    }

    @Test
    void modelsOnOneGpuShareALaneAndTheLeastConnectionsHold() throws Exception {
        loginAdmin();
        Gpu gpu = new Gpu();
        gpu.setName(uniqueName("gpu"));
        gpu = gpuService.saveGpu(gpu);
        GpuLock voice = lock(gpu);
        GpuLock heavy = lock(gpu);
        login();
        AI big = createAI();
        big.setGpuLock(voice.getUuid());
        big.setMaxConcurrentRequests(4);
        big = aiService.save(big);
        AI small = createAI();
        small.setGpuLock(heavy.getUuid());
        small.setMaxConcurrentRequests(2);
        small = aiService.save(small);

        // the GPU, not the model: whichever writes the summaries waits in the same queue, as many at once as the one with the least can take
        assertThat(summaries.lane(big).key()).isEqualTo(summaries.lane(small).key()).startsWith("gpu:");
        assertThat(summaries.lane(big).limit()).isEqualTo(2);
        assertThat(summaries.lane(small).limit()).isEqualTo(2);
    }

    @Test
    void aLockThatAllowsFewerJobsLimitsTheLane() throws Exception {
        loginAdmin();
        Gpu gpu = new Gpu();
        gpu.setName(uniqueName("gpu"));
        gpu = gpuService.saveGpu(gpu);
        GpuLock one = lock(gpu);
        one.setMaxConcurrent(1);
        one = gpuService.saveLock(one);
        login();
        AI wide = createAI();
        wide.setGpuLock(one.getUuid());
        wide.setMaxConcurrentRequests(5);
        wide = aiService.save(wide);
        assertThat(summaries.lane(wide).limit()).isEqualTo(1);
    }

    private GpuLock lock(Gpu gpu) throws Exception {
        GpuLock lock = new GpuLock();
        lock.setName(uniqueName("lock"));
        lock.setGpu(gpu);
        lock.setMaxConcurrent(0);
        lock.setAcquireSteps(List.of());
        lock.setReleaseSteps(List.of());
        return gpuService.saveLock(lock);
    }

    @Test
    void theSummaryModelIsTheOneChosenForSummaries() throws Exception {
        AI summarizing = createAI();
        joi.setSummaryModel(summarizing.getId());
        joi = joiService.save(joi);
        message(ago(Duration.ofMinutes(180)), "Hello", "Hi.");
        answers("Greetings.");
        settle();
        assertThat(all()).isNotEmpty().allSatisfy(s -> assertThat(s.getModelUsed()).isEqualTo(summarizing.getName()));
    }

    @Test
    void aSummaryWithoutAnyModelIsNotAttempted() throws Exception {
        Joi bare = joiService.save(new Joi());
        summarizer.check(bare);
        assertThat(summarizer.jobs(bare)).isEmpty();
        assertThatThrownBy(() -> summarizer.summarize(bare, message(ago(Duration.ofMinutes(5)), "x", "y")))
                .isInstanceOfAny(IllegalStateException.class, IllegalArgumentException.class, SecurityException.class);
    }

    @Test
    void jobsAreShownWhileTheyWait() throws Exception {
        // a slow model keeps the job in the queue
        message(ago(Duration.ofMinutes(180)), "Hello", "Hi.");
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        llm.enqueue(MockLLMResponse.text("Slow.").waitFor(gate));
        summarizer.check(joi);
        List<SummarizerService.JobInfo> jobs = await(() -> summarizer.jobs(joi).isEmpty() ? null : summarizer.jobs(joi), "the job");
        assertThat(jobs).extracting(SummarizerService.JobInfo::kind).contains(Kind.QUIET);
        gate.countDown();
        awaitNoJobs();
        assertThat(all()).isNotEmpty();
    }
}
