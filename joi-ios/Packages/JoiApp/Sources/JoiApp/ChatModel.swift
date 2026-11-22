import Foundation
import JoiClient
import JoiProtocol
import Observation

public struct ChatMessage: Identifiable, Equatable, Sendable {
    public enum Role: Equatable, Sendable {
        case user, joi
        /// Something that happened (the node connected, she woke up), or a note of the server.
        case note
        case error
    }

    public let id: UUID
    public var role: Role
    public var text: String
    /// The turn her words belong to, while they are written (`ref` of her frames).
    public var turn: String?
    public var streaming: Bool

    public init(role: Role, text: String, turn: String? = nil, streaming: Bool = false, id: UUID = UUID()) {
        self.id = id
        self.role = role
        self.text = text
        self.turn = turn
        self.streaming = streaming
    }
}

/// The conversation: her history, what the user says, her answer as it is written, her voice, her body (what it does as her turns come and
/// go: `AvatarDirector`) and the state of the connection.
@MainActor @Observable
public final class ChatModel: AvatarOutput {
    public enum Status: Equatable, Sendable {
        case connecting
        case connected
        /// The connection was lost; the next try is in this many seconds.
        case reconnecting(seconds: Int)
        /// It could not connect, or it ended for good (revoked, replaced...).
        case offline(String)
    }

    public private(set) var messages: [ChatMessage] = []
    public private(set) var status: Status = .connecting {
        didSet { player?.setOffline(status != .connected && status != .connecting) }
    }
    public private(set) var hasMoreHistory = false
    public let joiID: String
    public let joiName: String

    private let history: HistorySource
    private let link: NodeLink
    private let speech: SpeechPlaying?
    @ObservationIgnored private let player: AnimationPlayer?
    @ObservationIgnored private var director: AvatarDirector!
    /// What she is doing now (her body's state).
    public private(set) var avatarState: AvatarDirector.State = .idle
    /// The part she is being put in another state for (its id), nil when none: one change at a time, as the node's controls lock.
    public private(set) var switchingPart: String?
    /// The server kept a change of a part: her body is read again.
    @ObservationIgnored public var onPartKept: (@MainActor () -> Void)?
    private var partWaiters: [String: CheckedContinuation<PartAnswer, Never>] = [:]
    private var partCounter = 0
    /// The server tells that something changed (`body`, `joi`: the app loads it again).
    @ObservationIgnored public var onServerEvent: (@MainActor (String) -> Void)?
    private var eventTask: Task<Void, Never>?
    /// The turn the user's last words started: only one at a time.
    private var runningTurn: String?
    private var started = false

    public init(joiID: String, joiName: String, history: HistorySource, link: NodeLink, speech: SpeechPlaying? = nil,
                player: AnimationPlayer? = nil, roles: JoiAnimations? = nil) {
        self.joiID = joiID
        self.joiName = joiName
        self.history = history
        self.link = link
        self.speech = speech
        self.player = player
        director = AvatarDirector(output: self, roles: roles)
        speech?.onFinished = { [weak self] in self?.director.speechEnded() }
        player?.onEnded = { [weak self] in self?.director.animationEnded() }
    }

    /// The server's answer to a part the user put in another state.
    public enum PartAnswer: Equatable, Sendable {
        /// Kept on her body; `told` when her reaction is still to come (her turn follows).
        case kept(told: Bool)
        /// Her personality let her decide, and she did not allow it.
        case refused(String?)
        case failed(String)
    }

    /// The user puts a part of her in another state. The server keeps it on the body (so it is the same on every node) and starts her turn
    /// about it; the answer is the only thing that says it was kept. Only one at a time (a second one is `failed` at once), and without an
    /// answer in 20 s it is `failed`.
    public func switchPart(_ part: String, to state: String) async -> PartAnswer {
        guard switchingPart == nil else { return .failed("Another change is on its way.") }
        partCounter += 1
        let id = "p-\(partCounter)"
        switchingPart = part
        let answer = await withCheckedContinuation { (continuation: CheckedContinuation<PartAnswer, Never>) in
            partWaiters[id] = continuation
            Task { [weak self] in
                do {
                    try await self?.link.send(Frame.part(PartSwitch(part: part, state: state), id: id))
                } catch {
                    self?.answerPart(id, .failed(Self.describe(error)))
                }
            }
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(20))
                self?.answerPart(id, .failed("The server did not answer."))
            }
        }
        switchingPart = nil
        if case .kept = answer { onPartKept?() }
        return answer
    }

    private func answerPart(_ id: String?, _ answer: PartAnswer) {
        guard let id, let waiter = partWaiters.removeValue(forKey: id) else { return }
        waiter.resume(returning: answer)
    }

    /// The animations chosen in her dialog changed.
    public func setRoles(_ roles: JoiAnimations?) {
        director.roles(roles)
    }

    /// Loads her history, starts listening to the connection and connects it. Call again to try again after `offline`.
    public func start() async {
        if eventTask == nil {
            eventTask = Task { [link] in
                for await event in link.events {
                    self.apply(event)
                }
            }
        }
        if messages.isEmpty {
            await loadHistory()
        }
        if started == false {
            started = true
            director.start()
        }
        status = .connecting
        do {
            _ = try await link.connect()
            status = .connected
        } catch {
            status = .offline(Self.describe(error))
        }
    }

    public func stop() async {
        eventTask?.cancel()
        eventTask = nil
        speech?.stop()
        player?.onEnded = nil
        await link.close()
    }

    /// The user says something. Nothing is kept for later when it is not connected: it fails and they say it again.
    public func send(_ text: String) async {
        let words = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !words.isEmpty else { return }
        speech?.stop()
        director.speechEnded() // what she was saying is over
        director.expectSpeech(speech != nil)
        messages.append(ChatMessage(role: .user, text: words))
        do {
            let turn = try await link.say(words, speak: true, reasoning: nil, withEvents: nil)
            runningTurn = turn
            messages.append(ChatMessage(role: .joi, text: "", turn: turn, streaming: true))
        } catch {
            messages.append(ChatMessage(role: .error, text: "Not sent: " + Self.describe(error)))
        }
    }

    // MARK: history

    private func loadHistory() async {
        do {
            let page = try await history.history(joiID, limit: 50, before: nil)
            messages = Self.messages(of: page.messages)
            hasMoreHistory = page.more
        } catch {
            messages.append(ChatMessage(role: .error, text: "The history could not be loaded: " + Self.describe(error)))
        }
    }

    static func messages(of entries: [ChatEntry]) -> [ChatMessage] {
        var out: [ChatMessage] = []
        for entry in entries {
            switch entry.type {
            case "user":
                if let text = entry.text { out.append(ChatMessage(role: .user, text: text)) }
            case "log":
                if let text = entry.text { out.append(ChatMessage(role: .note, text: text)) }
            default:
                // an event she was told: what happened, and her answer to it
                out.append(ChatMessage(role: .note, text: eventNote(entry.event ?? entry.type)))
            }
            if let reply = entry.reply, !reply.isEmpty { out.append(ChatMessage(role: .joi, text: reply)) }
        }
        return out
    }

    // MARK: what the connection tells

    func apply(_ event: NodeConnection.Event) {
        switch event {
        case .connecting:
            status = .connecting
        case .disconnected, .retrying(_):
            if case .retrying(let seconds) = event {
                status = .reconnecting(seconds: seconds)
            }
            endRunningTurn(note: "The connection was lost.")
        case .reconnected:
            status = .connected
        case .closed(let code, let reason):
            status = .offline(reason.isEmpty ? "Closed (\(code))" : reason)
        case .frame(let incoming):
            apply(incoming)
        }
    }

    private func apply(_ incoming: NodeConnection.Incoming) {
        switch incoming.data {
        case .turn(let start):
            // a turn the server started (she woke up, the user is back): her words come with this id
            runningTurn = start.turn
            messages.append(ChatMessage(role: .joi, text: "", turn: start.turn, streaming: true))
            director.turnStarted(start.turn, cause: start.cause, speak: speech != nil)
        case .text(let delta):
            if let turn = incoming.ref { director.text(turn, delta.delta) }
        case .reply(let reply):
            if let turn = incoming.ref { director.reply(turn, reply) }
        case .audio(let chunk):
            if let turn = incoming.ref { director.audio(turn, chunk) } else { speech?.enqueue(chunk) }
        case .done:
            director.done(incoming.ref)
        case .partKept(let kept):
            answerPart(incoming.ref, .kept(told: kept.told))
        case .partRefused(let refused):
            answerPart(incoming.ref, .refused(refused.reason))
        case .error(let error):
            if let ref = incoming.ref, partWaiters[ref] != nil {
                answerPart(ref, .failed(error.message))
            } else {
                director.failed(incoming.ref, error)
            }
        case .animation(let play):
            director.gesture(play.animation, loop: play.loop ?? false)
        case .event(let event):
            onServerEvent?(event.name)
        case .log(let log):
            messages.append(ChatMessage(role: .note, text: log.text))
        default:
            break // her tasks and the rest are the app's later stages
        }
    }

    // MARK: what the director shows (her words come when her body lets them)

    public func play(_ animation: String?, loop: Bool) {
        player?.play(animation, loop: loop)
    }

    public func text(turn: String, delta: String) {
        withTurn(turn) { $0.text += delta }
    }

    public func reply(turn: String, reply: ChatReply) {
        withTurn(turn) { $0.text = reply.text }
    }

    public func speak(turn: String, chunk: AudioChunk) {
        speech?.enqueue(chunk)
    }

    public func done(turn: String?) {
        finish(turn)
    }

    public func failed(turn: String?, error: ApiError) {
        finish(turn)
        messages.append(ChatMessage(role: .error, text: error.message))
    }

    public func state(_ state: AvatarDirector.State) {
        avatarState = state
    }

    /// Her message of that turn (made when a frame comes first, as after a turn the server started).
    private func withTurn(_ turn: String?, _ change: (inout ChatMessage) -> Void) {
        guard let turn else { return }
        if let index = messages.lastIndex(where: { $0.role == .joi && $0.turn == turn }) {
            change(&messages[index])
        } else {
            var message = ChatMessage(role: .joi, text: "", turn: turn, streaming: true)
            change(&message)
            messages.append(message)
        }
    }

    private func finish(_ turn: String?) {
        guard let turn, let index = messages.lastIndex(where: { $0.turn == turn }) else { return }
        messages[index].streaming = false
        if messages[index].role == .joi, messages[index].text.isEmpty {
            messages.remove(at: index) // she said nothing (a turn that was only a tool, or an event she did not answer)
        }
        if runningTurn == turn { runningTurn = nil }
    }

    private func endRunningTurn(note: String) {
        director.disconnected()
        for id in Array(partWaiters.keys) { answerPart(id, .failed("The connection was lost.")) }
        guard let turn = runningTurn else { return }
        finish(turn)
        messages.append(ChatMessage(role: .note, text: note))
    }

    /// An event the server told her (`node.first_boot`, `node.part_switched`...) as a line for the user.
    static func eventNote(_ name: String) -> String {
        let kind = name.hasPrefix("node.") ? String(name.dropFirst("node.".count)) : name
        switch kind {
        case "connected": return "Connected"
        case "first_boot": return "She wakes up for the first time"
        case "summary_created": return "She went over the past"
        case "part_switched": return "Her look was changed"
        case "task_ended": return "A task of hers ended"
        case "looking_glass_entered": return "Moved to the Looking Glass"
        case "looking_glass_exited": return "Back from the Looking Glass"
        default:
            let words = kind.replacingOccurrences(of: "_", with: " ").replacingOccurrences(of: ".", with: " ")
            return words.prefix(1).uppercased() + words.dropFirst()
        }
    }

    static func describe(_ error: Error) -> String {
        if let error = error as? ServerError { return error.message }
        if let error = error as? ChannelClosed { return error.reason.isEmpty ? "closed (\(error.code))" : error.reason }
        if error is NodeConnectionError { return "no connection" }
        return error.localizedDescription
    }
}
