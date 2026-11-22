import Foundation
import Testing
import JoiClient
import JoiProtocol
@testable import JoiApp

final class FakeLink: NodeLink, @unchecked Sendable {
    let events: AsyncStream<NodeConnection.Event>
    private let continuation: AsyncStream<NodeConnection.Event>.Continuation
    private let lock = NSLock()
    private var said: [String] = []
    private var frames: [Frame] = []
    var connectError: Error?
    var sayError: Error?
    private(set) var closed = false

    init() {
        (events, continuation) = AsyncStream.makeStream(of: NodeConnection.Event.self)
    }

    var sentWords: [String] { lock.withLock { said } }
    var sentFrames: [Frame] { lock.withLock { frames } }

    func connect() async throws -> Welcome {
        if let connectError { throw connectError }
        return Welcome(protocol: "1.8", server: "joi-server", version: "dev", session: "s1", joi: JoiSummary(id: "j1", name: "Joi"), heartbeatSeconds: 30)
    }

    func say(_ text: String, speak: Bool?, reasoning: Bool?, withEvents: Bool?) async throws -> String {
        if let sayError { throw sayError }
        return lock.withLock { said.append(text); return "t\(said.count)" }
    }

    func send(_ frame: Frame) async throws {
        if let sayError { throw sayError }
        lock.withLock { frames.append(frame) }
    }

    func close() async { closed = true }

    func tell(_ event: NodeConnection.Event) { continuation.yield(event) }
}

struct FakeHistory: HistorySource {
    var page = ChatHistory(messages: [], more: false)
    var error: Error?

    func history(_ joi: String, limit: Int, before: String?) async throws -> ChatHistory {
        if let error { throw error }
        return page
    }
}

@MainActor final class FakeSpeech: SpeechPlaying {
    var chunks: [AudioChunk] = []
    var stops = 0
    var onFinished: (@MainActor () -> Void)?
    func enqueue(_ chunk: AudioChunk) { chunks.append(chunk) }
    func stop() { stops += 1 }
}

@MainActor final class FakePlayer: AnimationPlayer {
    var played: [String] = []
    var offline = false
    var onEnded: (@MainActor () -> Void)?
    func play(_ animation: String?, loop: Bool) { played.append("\(animation ?? "rest")\(loop ? " loop" : "")") }
    func setOffline(_ offline: Bool) { self.offline = offline }
}

@MainActor @Suite struct ChatModelTests {
    let link = FakeLink()
    let speech = FakeSpeech()

    private func model(history: FakeHistory = FakeHistory(), withSpeech: Bool = true) -> ChatModel {
        ChatModel(joiID: "j1", joiName: "Joi", history: history, link: link, speech: withSpeech ? speech : nil)
    }

    private func frame(_ ref: String?, _ data: NodeFrameData) -> NodeConnection.Event {
        .frame(NodeConnection.Incoming(ref: ref, data: data))
    }

    @Test func theHistoryIsTheStartOfTheChat() async {
        let date = Date(timeIntervalSince1970: 1_800_000_000)
        let page = ChatHistory(messages: [
            ChatEntry(id: "m1", sentAt: date, type: "user", text: "Hi", reply: "Hello!"),
            ChatEntry(id: "m2", sentAt: date, type: "event", event: "node.first_boot", reply: "I'm awake."),
            ChatEntry(id: "m3", sentAt: date, type: "log", text: "Approved automatically: web_search"),
        ], more: true)
        let chat = model(history: FakeHistory(page: page))
        await chat.start()
        #expect(chat.messages.map(\.role) == [.user, .joi, .note, .joi, .note])
        #expect(chat.messages.map(\.text) == ["Hi", "Hello!", "She wakes up for the first time", "I'm awake.", "Approved automatically: web_search"])
        #expect(chat.hasMoreHistory)
        #expect(chat.status == .connected)
    }

    @Test func herAnswerIsWrittenAsItComesAndHerVoiceIsPlayed() async {
        let chat = model()
        await chat.start()
        await chat.send("  What's up?  ")
        #expect(link.sentWords == ["What's up?"])
        #expect(chat.messages.map(\.role) == [.user, .joi])
        #expect(chat.messages[1].streaming && chat.messages[1].turn == "t1")

        // her words wait for her voice: the chat does not run ahead of what she says
        chat.apply(frame("t1", .text(TextDelta(delta: "Not "))))
        chat.apply(frame("t1", .text(TextDelta(delta: "much."))))
        #expect(chat.messages[1].text == "")
        chat.apply(frame("t1", .reply(ChatReply(messageId: "m9", text: "Not much.", usage: Usage(promptTokens: 1, completionTokens: 1, firstTokenMs: 1, textMs: 1, speechMs: 1)))))
        chat.apply(frame("t1", .audio(AudioChunk(index: 0, text: "Not much.", mimeType: "audio/wav", data: Data([1, 2])))))
        #expect(chat.messages[1].text == "Not much.")
        chat.apply(frame("t1", .done(Done(messageId: "m9"))))
        #expect(!chat.messages[1].streaming)
        #expect(chat.messages[1].text == "Not much.")
        #expect(speech.chunks.map(\.index) == [0])
    }

    @Test func aTurnTheServerStartedIsShownToo() async {
        let chat = model()
        await chat.start()
        chat.apply(frame(nil, .turn(TurnStart(turn: "s7", cause: "first_boot"))))
        chat.apply(frame("s7", .text(TextDelta(delta: "I'm awake."))))
        // no voice came: her words are shown when the turn ends
        chat.apply(frame("s7", .done(Done(messageId: "m1"))))
        #expect(chat.messages.map(\.text) == ["I'm awake."])
        #expect(chat.messages[0].role == .joi && !chat.messages[0].streaming)
    }

    @Test func withoutSpeechHerWordsComeAsTheyAreWritten() async {
        let chat = model(withSpeech: false)
        await chat.start()
        chat.apply(frame(nil, .turn(TurnStart(turn: "s7", cause: "first_boot"))))
        chat.apply(frame("s7", .text(TextDelta(delta: "I'm awake."))))
        chat.apply(frame("s7", .done(Done(messageId: "m1"))))
        #expect(chat.messages.map(\.text) == ["I'm awake."])
        #expect(chat.messages[0].role == .joi && !chat.messages[0].streaming)
    }

    @Test func aTurnWithNoWordsLeavesNothingBehind() async {
        let chat = model()
        await chat.start()
        await chat.send("Hi")
        chat.apply(frame("t1", .done(Done(messageId: "m1"))))
        #expect(chat.messages.map(\.role) == [.user])
    }

    @Test func aFailedTurnIsAnError() async {
        let chat = model()
        await chat.start()
        await chat.send("Hi")
        chat.apply(frame("t1", .error(ApiError(code: "model_unavailable", message: "The model is down"))))
        #expect(chat.messages.map(\.role) == [.user, .error])
        #expect(chat.messages[1].text == "The model is down")
    }

    @Test func sayingWithoutAConnectionFailsAndIsNotKept() async {
        link.sayError = NodeConnectionError.notConnected
        let chat = model()
        await chat.start()
        await chat.send("Hi")
        #expect(chat.messages.map(\.role) == [.user, .error])
        #expect(chat.messages[1].text.hasPrefix("Not sent"))
    }

    @Test func emptyWordsAreNotSent() async {
        let chat = model()
        await chat.start()
        await chat.send("   \n")
        #expect(chat.messages.isEmpty && link.sentWords.isEmpty)
    }

    @Test func aLostConnectionEndsTheTurnAndComesBack() async {
        let chat = model()
        await chat.start()
        await chat.send("Hi")
        chat.apply(.disconnected(code: 1006, reason: "lost"))
        chat.apply(.retrying(seconds: 2))
        #expect(chat.status == .reconnecting(seconds: 2))
        #expect(chat.messages.map(\.role) == [.user, .note]) // her empty message is gone, and the user is told
        chat.apply(.connecting)
        #expect(chat.status == .connecting)
        chat.apply(.reconnected(Welcome(protocol: "1.8", server: "s", version: "v", session: "s2", joi: JoiSummary(id: "j1", name: "Joi"), heartbeatSeconds: 30)))
        #expect(chat.status == .connected)
    }

    @Test func aConnectionThatEndedForGoodIsOffline() async {
        let chat = model()
        await chat.start()
        chat.apply(.closed(code: 4009, reason: "revoked"))
        #expect(chat.status == .offline("revoked"))
    }

    @Test func aFailedConnectIsOfflineAndCanBeTriedAgain() async {
        link.connectError = ChannelClosed(code: 1006, reason: "no network")
        let chat = model()
        await chat.start()
        #expect(chat.status == .offline("no network"))
        link.connectError = nil
        await chat.start()
        #expect(chat.status == .connected)
    }

    @Test func aHistoryThatFailsIsToldAndTheChatGoesOn() async {
        let chat = model(history: FakeHistory(error: ServerError(status: 500, code: "x", message: "broken")))
        await chat.start()
        #expect(chat.messages.map(\.role) == [.error])
        #expect(chat.status == .connected)
    }

    @Test func eventsComeThroughTheStreamToo() async throws {
        let chat = model(withSpeech: false)
        await chat.start()
        link.tell(frame(nil, .turn(TurnStart(turn: "s1", cause: "x"))))
        link.tell(frame("s1", .text(TextDelta(delta: "Hello"))))
        for _ in 0..<200 where chat.messages.first?.text != "Hello" { try await Task.sleep(for: .milliseconds(5)) }
        #expect(chat.messages.first?.text == "Hello")
        await chat.stop()
        #expect(link.closed)
    }

    @Test func herBodyFollowsHerTurnsAndTheConnection() async {
        let player = FakePlayer()
        let roles = JoiAnimations(sleeping: "sleep", wakingUp: "wake", idle: ["idle"], talking: ["talk"])
        let chat = ChatModel(joiID: "j1", joiName: "Joi", history: FakeHistory(), link: link, speech: nil, player: player, roles: roles)
        await chat.start()
        #expect(player.played == ["idle loop"])
        // her first waking up: she sleeps while her words are written, they are shown when she is awake
        chat.apply(frame(nil, .turn(TurnStart(turn: "s1", cause: "first_boot"))))
        chat.apply(frame("s1", .text(TextDelta(delta: "I'm awake."))))
        chat.apply(frame("s1", .done(Done(messageId: "m1"))))
        #expect(player.played == ["idle loop", "sleep loop", "wake"])
        #expect(chat.messages.map(\.text) == [""])
        #expect(chat.avatarState == .wakingUp)
        player.onEnded?()
        #expect(chat.messages.map(\.text) == ["I'm awake."])
        #expect(chat.avatarState == .idle)
        // a gesture she chose
        chat.apply(frame(nil, .animation(PlayAnimation(animation: "wave"))))
        #expect(player.played.last == "wave")
        // the server is gone: she is frozen
        chat.apply(.disconnected(code: 1006, reason: "lost"))
        chat.apply(.retrying(seconds: 1))
        #expect(player.offline)
        chat.apply(.reconnected(Welcome(protocol: "1.8", server: "s", version: "v", session: "s2", joi: JoiSummary(id: "j1", name: "Joi"), heartbeatSeconds: 30)))
        #expect(!player.offline)
    }

    /// Starts a change and waits until its frame is out; returns the answer's task and the frame's id.
    private func startSwitch(_ chat: ChatModel) async throws -> (Task<ChatModel.PartAnswer, Never>, String) {
        let answer = Task { await chat.switchPart("mesh:Hat", to: "off") }
        for _ in 0..<200 where link.sentFrames.isEmpty { try await Task.sleep(for: .milliseconds(5)) }
        let frame = try #require(link.sentFrames.last)
        #expect(frame.type == "part")
        return (answer, try #require(frame.id))
    }

    @Test func aPartIsPutInAnotherStateAndTheServersAnswerSaysItWasKept() async throws {
        var kept = 0
        let chat = model(withSpeech: false)
        chat.onPartKept = { kept += 1 }
        await chat.start()
        let (answer, id) = try await startSwitch(chat)
        #expect(chat.switchingPart == "mesh:Hat")
        // only one at a time
        #expect(await chat.switchPart("mesh:Shoes", to: "off") == .failed("Another change is on its way."))
        chat.apply(frame(id, .partKept(PartKept(part: "mesh:Hat", state: "off", told: true))))
        #expect(await answer.value == .kept(told: true))
        #expect(chat.switchingPart == nil && kept == 1)
        #expect(chat.messages.isEmpty)
    }

    @Test func herRefusalIsTheAnswer() async throws {
        let chat = model(withSpeech: false)
        await chat.start()
        let (answer, id) = try await startSwitch(chat)
        chat.apply(frame(id, .partRefused(PartRefused(part: "mesh:Hat", state: "off", reason: "Not in front of you."))))
        #expect(await answer.value == .refused("Not in front of you."))
    }

    @Test func anErrorOfThePartIsNotATurnsError() async throws {
        let chat = model(withSpeech: false)
        await chat.start()
        let (answer, id) = try await startSwitch(chat)
        chat.apply(frame(id, .error(ApiError(code: "bad_message", message: "No such state"))))
        #expect(await answer.value == .failed("No such state"))
        #expect(chat.messages.isEmpty)
    }

    @Test func aLostConnectionEndsAChangeOfAPart() async throws {
        let chat = model(withSpeech: false)
        await chat.start()
        let (answer, _) = try await startSwitch(chat)
        chat.apply(.disconnected(code: 1006, reason: "lost"))
        #expect(await answer.value == .failed("The connection was lost."))
    }

    @Test func eventsHaveLinesForTheUser() {
        #expect(ChatModel.eventNote("node.connected") == "Connected")
        #expect(ChatModel.eventNote("node.some_new_cause") == "Some new cause")
    }

    @Test func aServerEventIsToldToTheApp() async {
        let chat = model(withSpeech: false)
        var names: [String] = []
        chat.onServerEvent = { names.append($0) }
        chat.apply(frame(nil, .event(ServerEvent(name: "body", at: Date()))))
        #expect(names == ["body"])
    }

    @Test func aPairingLinkHasTheServerAndTheOffer() {
        let link = URL(string: "joi://pair?server=http%3A%2F%2F192.168.1.5%3A8080&offer=aB-_9")!
        let pairing = AppModel.pairing(from: link)
        #expect(pairing?.server.absoluteString == "http://192.168.1.5:8080" && pairing?.offer == "aB-_9")
        #expect(AppModel.pairing(from: URL(string: "https://pair?server=x&offer=y")!) == nil) // not of the app
        #expect(AppModel.pairing(from: URL(string: "joi://other?server=x&offer=y")!) == nil)
        #expect(AppModel.pairing(from: URL(string: "joi://pair?server=joi.example.com")!) == nil) // no offer
        #expect(AppModel.pairing(from: URL(string: "joi://pair?server=joi.example.com&offer=a%20b")!) == nil) // not a secret of the server
        #expect(AppModel.pairing(from: URL(string: "joi://pair?server=ftp%3A%2F%2Fx&offer=y")!) == nil)
    }

    @Test func anAddressIsMadeIntoAnUrl() {
        #expect(AppModel.url("joi.example.com")?.absoluteString == "https://joi.example.com")
        #expect(AppModel.url(" http://192.168.1.5:8080 ")?.absoluteString == "http://192.168.1.5:8080")
        #expect(AppModel.url("ftp://x") == nil)
        #expect(AppModel.url("  ") == nil)
    }
}

/// A source of tasks that records the answers.
final class FakeTasks: HistorySource, @unchecked Sendable {
    private let lock = NSLock()
    private var answers: [String] = []
    var error: Error?
    var recorded: [String] { lock.withLock { answers } }

    func history(_ joi: String, limit: Int, before: String?) async throws -> ChatHistory { ChatHistory(messages: [], more: false) }

    func approveTask(_ joi: String, _ task: String, always: Bool) async throws -> TaskInfo {
        if let error { throw error }
        lock.withLock { answers.append("approve \(task)\(always ? " always" : "")") }
        return Self.task(task, "running")
    }

    func denyTask(_ joi: String, _ task: String, reason: String?) async throws -> TaskInfo {
        if let error { throw error }
        lock.withLock { answers.append("deny \(task)") }
        return Self.task(task, "cancelled")
    }

    static func task(_ id: String, _ status: String) -> TaskInfo {
        TaskInfo(id: id, title: "Search the web", tool: "web_search", toolDescription: "Searches the web", status: status,
                 question: "weather in Brno", url: nil, result: nil, error: nil, created: Date(), started: nil, finished: nil, steps: nil)
    }
}

@MainActor @Suite struct TaskQuestionTests {
    let link = FakeLink()

    private func model(_ tasks: FakeTasks = FakeTasks()) -> ChatModel {
        ChatModel(joiID: "j1", joiName: "Joi", history: tasks, link: link)
    }

    private func task(_ id: String, _ status: String = "waiting") -> NodeConnection.Event {
        .frame(NodeConnection.Incoming(ref: nil, data: .task(FakeTasks.task(id, status))))
    }

    @Test func aTaskThatWaitsIsAskedOnceAndTakenBackWhenItIsAnswered() {
        let chat = model()
        chat.apply(task("a"))
        chat.apply(task("b"))
        chat.apply(task("a"))
        #expect(chat.questions.map(\.id) == ["a", "b"])
        // answered somewhere else
        chat.apply(task("a", "running"))
        #expect(chat.questions.map(\.id) == ["b"])
        // a task that does not wait is no question
        chat.apply(task("c", "running"))
        #expect(chat.questions.map(\.id) == ["b"])
    }

    @Test func approvingAndDenyingAreSentAndTakeTheQuestionBack() async {
        let tasks = FakeTasks()
        let chat = model(tasks)
        chat.apply(task("a"))
        chat.apply(task("b"))
        chat.apply(task("c"))
        await chat.answer(chat.questions[0], .approve)
        await chat.answer(chat.questions[0], .deny)
        await chat.answer(chat.questions[0], .approveAlways)
        #expect(tasks.recorded == ["approve a", "deny b", "approve c always"])
        #expect(chat.questions.isEmpty)
        #expect(chat.questionNote == nil)
    }

    @Test func aTaskAnsweredElsewhereIsAConflictAndSimplyGoes() async {
        let tasks = FakeTasks()
        tasks.error = ServerError(status: 409, code: "conflict", message: "Answered already")
        let chat = model(tasks)
        chat.apply(task("a"))
        await chat.answer(chat.questions[0], .approve)
        #expect(chat.questions.isEmpty)
        #expect(chat.questionNote == nil)
    }

    @Test func aFailedAnswerKeepsTheQuestionAndSaysWhy() async {
        let tasks = FakeTasks()
        tasks.error = ServerError(status: 500, code: "internal", message: "Broken")
        let chat = model(tasks)
        chat.apply(task("a"))
        await chat.answer(chat.questions[0], .approve)
        #expect(chat.questions.map(\.id) == ["a"])
        #expect(chat.questionNote != nil)
        #expect(chat.answering == nil)
    }

    @Test func aLostConnectionForgetsTheQuestions() {
        let chat = model()
        chat.apply(task("a"))
        chat.apply(.retrying(seconds: 1))
        #expect(chat.questions.isEmpty)
    }
}
