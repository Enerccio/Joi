import Foundation
import Observation

/// What a recognizer tells while the user talks.
public enum DictationEvent: Equatable, Sendable {
    /// Everything heard so far (the whole text, not a piece of it).
    case partial(String)
    /// The recognizer is done: the whole text (empty when nothing was said).
    case final(String)
    /// It could not go on.
    case failed(String)
}

public struct DictationError: Error, Equatable, Sendable, LocalizedError {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var errorDescription: String? { message }
}

/// Turns the user's speech into text: the microphone and a recognizer (Apple's, on the device: `AppleDictation`). No audio leaves the phone.
@MainActor public protocol Dictating: AnyObject {
    /// Asks for the permissions (the first time), opens the microphone and starts recognizing; the events come until the end.
    func begin() async throws -> AsyncStream<DictationEvent>
    /// The user (or a pause) ended the speech: the microphone closes and the recognizer finishes with `final`.
    func endAudio()
    /// Stops at once, nothing more comes.
    func cancel()
}

/// Push-to-talk: the user taps the microphone, talks, and what they said goes to her as typed words (`say` carries text, never audio).
/// The recording ends at a pause (1.2 s by default), when the user taps again (it is sent at once) or cancels (nothing is sent). Talking over her
/// stops her, as the Stop of the other nodes does. Nothing begins after 8 s of silence, and no talk takes more than 60 s.
@MainActor @Observable
public final class VoiceInput {
    public enum State: Equatable, Sendable {
        case idle
        /// The permissions are asked and the microphone opens.
        case starting
        case listening
        /// The recognizer finishes, then the words are sent.
        case finishing
    }

    public private(set) var state: State = .idle
    /// What is heard so far, while the user talks.
    public private(set) var heard = ""
    /// Why nothing was said to her, nil when something was.
    public private(set) var note: String?
    /// Words that were heard but not sent (the connection was down): for the user to keep in the text field.
    public private(set) var leftover: String?

    private let dictation: Dictating
    private let send: @MainActor (String) async -> Bool
    private let beforeListening: @MainActor () -> Void
    private let pause: Duration
    private let waitForSpeech: Duration
    private let longest: Duration
    private let finishWait: Duration
    /// Counts the talks: what an earlier one does late (a timer, a stream) is dropped.
    private var session = 0
    private var reading: Task<Void, Never>?
    private var quiet: Task<Void, Never>?
    private var limit: Task<Void, Never>?

    /// `send` says the words to her and tells whether they went; `beforeListening` stops her voice.
    public init(dictation: Dictating, send: @escaping @MainActor (String) async -> Bool, beforeListening: @escaping @MainActor () -> Void = {},
                pause: Duration = .milliseconds(1200), waitForSpeech: Duration = .seconds(8), longest: Duration = .seconds(60),
                finishWait: Duration = .seconds(5)) {
        self.dictation = dictation
        self.send = send
        self.beforeListening = beforeListening
        self.pause = pause
        self.waitForSpeech = waitForSpeech
        self.longest = longest
        self.finishWait = finishWait
    }

    /// The button: starts listening, or ends what is said now (it is sent at once).
    public func toggle() {
        switch state {
        case .idle: start()
        case .listening: finish()
        case .starting, .finishing: break
        }
    }

    public func start() {
        guard state == .idle else { return }
        note = nil
        heard = ""
        leftover = nil
        state = .starting
        beforeListening()
        session += 1
        let mine = session
        reading = Task { [weak self] in await self?.run(mine) }
    }

    /// Nothing is sent.
    public func cancel() {
        guard state != .idle else { return }
        session += 1
        reading?.cancel()
        stopTimers()
        dictation.cancel()
        state = .idle
        heard = ""
    }

    /// The text field takes the words that were not sent.
    public func takeLeftover() -> String? {
        defer { leftover = nil }
        return leftover
    }

    /// What was said is over: the recognizer finishes with what it has.
    public func finish() {
        guard state == .listening else { return }
        state = .finishing
        stopTimers()
        dictation.endAudio()
        let mine = session
        // a recognizer that does not finish does not hold the words back
        quiet = after(finishWait, mine) { [weak self] in
            guard let self else { return }
            Task { await self.deliver(self.heard, mine) }
        }
    }

    // MARK: the talk

    private func run(_ mine: Int) async {
        let events: AsyncStream<DictationEvent>
        do {
            events = try await dictation.begin()
        } catch {
            guard mine == session else { return }
            end(note: error.localizedDescription)
            return
        }
        guard mine == session else {
            dictation.cancel() // cancelled while the microphone opened
            return
        }
        state = .listening
        quiet = after(waitForSpeech, mine) { [weak self] in self?.end(note: "I did not hear anything.") }
        limit = after(longest, mine) { [weak self] in self?.finish() }
        for await event in events {
            guard mine == session else { return }
            switch event {
            case .partial(let text):
                guard state == .listening else { continue }
                heard = text
                // a pause after words ends them; a text still empty keeps the wait for the beginning
                if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    quiet?.cancel()
                    quiet = after(pause, mine) { [weak self] in self?.finish() }
                }
            case .final(let text):
                await deliver(text, mine)
                return
            case .failed(let why):
                end(note: why)
                return
            }
        }
        // the stream ended with nothing final
        guard mine == session, state != .idle else { return }
        if state == .finishing { await deliver(heard, mine) } else { end(note: nil) }
    }

    private func deliver(_ text: String, _ mine: Int) async {
        guard mine == session, state != .idle else { return }
        let words = text.trimmingCharacters(in: .whitespacesAndNewlines)
        stopTimers()
        dictation.cancel() // closes what is still open, nothing more comes from it
        session += 1 // the stream and the timers of this talk are over
        guard !words.isEmpty else {
            state = .idle
            heard = ""
            note = "I did not hear anything."
            return
        }
        state = .finishing
        heard = words
        if await send(words) == false { leftover = words }
        state = .idle
        heard = ""
    }

    private func end(note: String?) {
        session += 1
        stopTimers()
        dictation.cancel()
        state = .idle
        heard = ""
        self.note = note
    }

    private func stopTimers() {
        quiet?.cancel()
        limit?.cancel()
        quiet = nil
        limit = nil
    }

    /// Runs `action` after `time`, unless this talk is over or the task is cancelled before.
    private func after(_ time: Duration, _ mine: Int, _ action: @escaping @MainActor () -> Void) -> Task<Void, Never> {
        Task { [weak self] in
            try? await Task.sleep(for: time)
            guard !Task.isCancelled, let self, mine == self.session else { return }
            action()
        }
    }
}
