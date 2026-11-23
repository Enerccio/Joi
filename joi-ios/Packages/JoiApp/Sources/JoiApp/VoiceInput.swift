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
    /// Asks for the permissions (the first time), opens the microphone and starts recognizing; the events come until the end. `hints` are
    /// words the recognizer should expect (her name).
    func begin(hints: [String]) async throws -> AsyncStream<DictationEvent>
    /// The user (or a pause) ended the speech: the microphone closes and the recognizer finishes with `final`.
    func endAudio()
    /// Stops at once, nothing more comes.
    func cancel()
}

extension Dictating {
    func begin() async throws -> AsyncStream<DictationEvent> { try await begin(hints: []) }
}

/// Push-to-talk and the wake word: the user taps the microphone (or says "Hey Joi"), talks, and what they said goes to her as typed words (`say`
/// carries text, never audio). The recording ends at a pause (1.2 s by default), when the user taps again (it is sent at once) or cancels (nothing is
/// sent). Talking over her stops her, as the Stop of the other nodes does. Nothing begins after 8 s of silence, and no talk takes more than 60 s.
///
/// **The wake word** (`arm`): the microphone stays open and the recognizer's text is searched for "Hey <her name>" (`WakeWord`). What follows it
/// in the same breath is the message ("Hey Joi, what time is it"); the name alone waits 5 s for the words. The recognition is made again every
/// 40 s (a session of the recognizer does not go on for ever), and after an end or a failure after 1 s, 2 s, 4 s... up to 30 s. It stands still
/// while her voice plays (`suspend`), or she would wake herself.
@MainActor @Observable
public final class VoiceInput {
    public enum State: Equatable, Sendable {
        case idle
        /// The microphone is open and the wake word is waited for.
        case waiting
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
    /// Whether the wake word is wanted (`arm`).
    public var armed: Bool { !phrases.isEmpty }

    private let dictation: Dictating
    private let send: @MainActor (String) async -> Bool
    private let beforeListening: @MainActor () -> Void
    private let pause: Duration
    private let waitForSpeech: Duration
    private let longest: Duration
    private let finishWait: Duration
    private let wakeWait: Duration
    private let recycleAfter: Duration
    private let restartDelay: Duration
    private let backoffStep: Duration
    /// Counts the talks: what an earlier one does late (a timer, a stream) is dropped.
    private var session = 0
    private var reading: Task<Void, Never>?
    private var quiet: Task<Void, Never>?
    private var limit: Task<Void, Never>?
    private var retry: Task<Void, Never>?
    private var phrases: [String] = []
    private var hints: [String] = []
    private var suspended = false
    /// The talk began with the wake word: what the recognizer hears holds it, and the words are what follows.
    private var woken = false
    /// How many times in a row the wake word's listening ended in a failure.
    private var failures = 0

    /// `send` says the words to her and tells whether they went; `beforeListening` stops her voice.
    public init(dictation: Dictating, send: @escaping @MainActor (String) async -> Bool, beforeListening: @escaping @MainActor () -> Void = {},
                pause: Duration = .milliseconds(1200), waitForSpeech: Duration = .seconds(8), longest: Duration = .seconds(60),
                finishWait: Duration = .seconds(5), wakeWait: Duration = .seconds(5), recycleAfter: Duration = .seconds(40),
                restartDelay: Duration = .milliseconds(200), backoffStep: Duration = .seconds(1)) {
        self.dictation = dictation
        self.send = send
        self.beforeListening = beforeListening
        self.pause = pause
        self.waitForSpeech = waitForSpeech
        self.longest = longest
        self.finishWait = finishWait
        self.wakeWait = wakeWait
        self.recycleAfter = recycleAfter
        self.restartDelay = restartDelay
        self.backoffStep = backoffStep
    }

    // MARK: the wake word

    /// Listens for one of the `phrases` ("hey Joi"); `hints` are what the recognizer should expect. An empty list is `disarm`.
    public func arm(phrases: [String], hints: [String] = []) {
        self.phrases = phrases
        self.hints = hints
        if phrases.isEmpty { disarm(); return }
        failures = 0
        if state == .idle { resumeWaiting() }
    }

    public func disarm() {
        phrases = []
        retry?.cancel()
        retry = nil
        if state == .waiting { cancel(rearm: false) }
    }

    /// Her voice plays: the wake word is not listened for (the microphone is closed) until it does not.
    public func suspend(_ on: Bool) {
        guard on != suspended else { return }
        suspended = on
        if on {
            retry?.cancel()
            retry = nil
            if state == .waiting { cancel(rearm: false) }
        } else if state == .idle {
            resumeWaiting()
        }
    }

    /// The app is in front again: a microphone that could not be opened in the background is tried at once.
    public func retryNow() {
        guard state == .idle, wantsWaiting else { return }
        failures = 0
        resumeWaiting()
    }

    private var wantsWaiting: Bool { !phrases.isEmpty && !suspended }

    private func resumeWaiting(after delay: Duration = .zero) {
        guard state == .idle, wantsWaiting else { return }
        retry?.cancel()
        if delay == .zero {
            beginWaiting()
            return
        }
        retry = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled, let self else { return }
            self.beginWaiting()
        }
    }

    private func beginWaiting() {
        guard state == .idle, wantsWaiting else { return }
        retry = nil
        woken = false
        state = .waiting
        session += 1
        let mine = session
        reading = Task { [weak self] in await self?.run(mine, waiting: true) }
    }

    /// The wake word's listening is over (an end, a failure, 40 s): a new one after a pause, longer after each failure in a row.
    private func recycle(failed: Bool) {
        session += 1
        stopTimers()
        dictation.cancel()
        state = .idle
        heard = ""
        woken = false
        guard wantsWaiting else { return }
        if failed {
            failures += 1
            let seconds = min(30.0, Double(1 << min(failures - 1, 6)) * backoffStep.seconds)
            resumeWaiting(after: .seconds(seconds))
        } else {
            resumeWaiting(after: restartDelay)
        }
    }

    // MARK: the user

    /// The button: starts listening, or ends what is said now (it is sent at once). While the wake word is waited for, it starts the talk.
    public func toggle() {
        switch state {
        case .idle, .waiting: start()
        case .listening: finish()
        case .starting, .finishing: break
        }
    }

    /// Starts a talk: the button, or *Talk to Joi*. While the wake word is waited for, that listening gives way to it.
    public func start() {
        if state == .waiting { cancel(rearm: false) }
        guard state == .idle else { return }
        retry?.cancel()
        retry = nil
        note = nil
        heard = ""
        leftover = nil
        woken = false
        state = .starting
        beforeListening()
        session += 1
        let mine = session
        reading = Task { [weak self] in await self?.run(mine, waiting: false) }
    }

    /// Nothing is sent. The wake word, when it is on, is waited for again.
    public func cancel() { cancel(rearm: true) }

    private func cancel(rearm: Bool) {
        guard state != .idle else { return }
        session += 1
        reading?.cancel()
        stopTimers()
        dictation.cancel()
        state = .idle
        heard = ""
        woken = false
        if rearm { resumeWaiting(after: restartDelay) }
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

    private func run(_ mine: Int, waiting: Bool) async {
        let events: AsyncStream<DictationEvent>
        do {
            events = try await dictation.begin(hints: hints)
        } catch {
            guard mine == session else { return }
            if waiting {
                // a refused permission does not mend itself; a microphone that is not free (a call, the background) does
                if error is DictationError {
                    phrases = []
                    end(note: error.localizedDescription)
                } else {
                    recycle(failed: true)
                }
            } else {
                end(note: error.localizedDescription)
            }
            return
        }
        guard mine == session else {
            dictation.cancel() // cancelled while the microphone opened
            return
        }
        if waiting {
            failures = 0
            limit = after(recycleAfter, mine) { [weak self] in self?.recycle(failed: false) }
        } else {
            state = .listening
            quiet = after(waitForSpeech, mine) { [weak self] in self?.end(note: "I did not hear anything.") }
            limit = after(longest, mine) { [weak self] in self?.finish() }
        }
        for await event in events {
            guard mine == session else { return }
            switch event {
            case .partial(let text):
                if state == .waiting {
                    if let rest = WakeWord.remainder(in: text, phrases: phrases) { wake(rest, mine) }
                } else if state == .listening {
                    guard let words = spoken(text) else { continue }
                    heard = words
                    // a pause after words ends them; a text still empty keeps the wait for the beginning
                    if !words.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        quiet?.cancel()
                        quiet = after(pause, mine) { [weak self] in self?.finish() }
                    }
                }
            case .final(let text):
                if state == .waiting {
                    // the recognizer ended while the wake word waited: it was in the last words, or the recognition is made again
                    if let rest = WakeWord.remainder(in: text, phrases: phrases), !rest.isEmpty {
                        woken = true
                        await deliver(rest, mine)
                    } else {
                        recycle(failed: false)
                    }
                } else {
                    await deliver(spoken(text) ?? heard, mine)
                }
                return
            case .failed(let why):
                if state == .waiting { recycle(failed: true) } else { end(note: why) }
                return
            }
        }
        // the stream ended with nothing final
        guard mine == session, state != .idle else { return }
        switch state {
        case .waiting: recycle(failed: false)
        case .finishing: await deliver(heard, mine)
        default: end(note: nil)
        }
    }

    /// Her name was said: what follows it is the message, which begins now.
    private func wake(_ rest: String, _ mine: Int) {
        woken = true
        state = .listening
        heard = rest
        failures = 0
        beforeListening()
        limit?.cancel()
        limit = after(longest, mine) { [weak self] in self?.finish() }
        quiet?.cancel()
        // the name alone: she waits a few seconds for the words, and then goes back to waiting
        if rest.isEmpty {
            quiet = after(wakeWait, mine) { [weak self] in self?.recycle(failed: false) }
        } else {
            quiet = after(pause, mine) { [weak self] in self?.finish() }
        }
    }

    /// What the user said in a text the recognizer gave: after the wake word when the talk began with it (nil: the recognizer changed its
    /// mind about the name, the words so far stay).
    private func spoken(_ text: String) -> String? {
        guard woken else { return text }
        return WakeWord.remainder(in: text, phrases: phrases)
    }

    private func deliver(_ text: String, _ mine: Int) async {
        guard mine == session, state != .idle else { return }
        let words = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let viaWakeWord = woken
        stopTimers()
        dictation.cancel() // closes what is still open, nothing more comes from it
        session += 1 // the stream and the timers of this talk are over
        guard !words.isEmpty else {
            state = .idle
            heard = ""
            woken = false
            // a note is for a button's talk: the wake word that heard nothing is its own business
            if !viaWakeWord { note = "I did not hear anything." }
            resumeWaiting(after: restartDelay)
            return
        }
        state = .finishing
        heard = words
        if await send(words) == false { leftover = words }
        state = .idle
        heard = ""
        woken = false
        resumeWaiting(after: restartDelay)
    }

    private func end(note: String?) {
        session += 1
        stopTimers()
        dictation.cancel()
        state = .idle
        heard = ""
        woken = false
        self.note = note
        // a button's talk that gave nothing: the wake word goes on, unless the failure was of the wake word's own (it is a note then)
        if let note, note != "I did not hear anything." { failures += 1 }
        resumeWaiting(after: failures > 0 ? .seconds(min(30.0, Double(1 << min(failures - 1, 6)) * backoffStep.seconds)) : restartDelay)
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

private extension Duration {
    var seconds: Double {
        let parts = components
        return Double(parts.seconds) + Double(parts.attoseconds) / 1e18
    }
}
