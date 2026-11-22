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
    var connectError: Error?
    var sayError: Error?
    private(set) var closed = false

    init() {
        (events, continuation) = AsyncStream.makeStream(of: NodeConnection.Event.self)
    }

    var sentWords: [String] { lock.withLock { said } }

    func connect() async throws -> Welcome {
        if let connectError { throw connectError }
        return Welcome(protocol: "1.8", server: "joi-server", version: "dev", session: "s1", joi: JoiSummary(id: "j1", name: "Joi"), heartbeatSeconds: 30)
    }

    func say(_ text: String, speak: Bool?, reasoning: Bool?, withEvents: Bool?) async throws -> String {
        if let sayError { throw sayError }
        return lock.withLock { said.append(text); return "t\(said.count)" }
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
    func enqueue(_ chunk: AudioChunk) { chunks.append(chunk) }
    func stop() { stops += 1 }
}

@MainActor @Suite struct ChatModelTests {
    let link = FakeLink()
    let speech = FakeSpeech()

    private func model(history: FakeHistory = FakeHistory()) -> ChatModel {
        ChatModel(joiID: "j1", joiName: "Joi", history: history, link: link, speech: speech)
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
        #expect(chat.messages.map(\.text) == ["Hi", "Hello!", "node.first_boot", "I'm awake.", "Approved automatically: web_search"])
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

        chat.apply(frame("t1", .text(TextDelta(delta: "Not "))))
        chat.apply(frame("t1", .text(TextDelta(delta: "much."))))
        #expect(chat.messages[1].text == "Not much.")
        chat.apply(frame("t1", .reply(ChatReply(messageId: "m9", text: "Not much.", usage: Usage(promptTokens: 1, completionTokens: 1, firstTokenMs: 1, textMs: 1, speechMs: 1)))))
        chat.apply(frame("t1", .audio(AudioChunk(index: 0, text: "Not much.", mimeType: "audio/wav", data: Data([1, 2])))))
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
        let chat = model()
        await chat.start()
        link.tell(frame(nil, .turn(TurnStart(turn: "s1", cause: "x"))))
        link.tell(frame("s1", .text(TextDelta(delta: "Hello"))))
        for _ in 0..<200 where chat.messages.first?.text != "Hello" { try await Task.sleep(for: .milliseconds(5)) }
        #expect(chat.messages.first?.text == "Hello")
        await chat.stop()
        #expect(link.closed && speech.stops >= 1)
    }

    @Test func anAddressIsMadeIntoAnUrl() {
        #expect(AppModel.url("joi.example.com")?.absoluteString == "https://joi.example.com")
        #expect(AppModel.url(" http://192.168.1.5:8080 ")?.absoluteString == "http://192.168.1.5:8080")
        #expect(AppModel.url("ftp://x") == nil)
        #expect(AppModel.url("  ") == nil)
    }
}
