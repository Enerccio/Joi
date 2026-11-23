import AVFoundation
import Foundation
import Speech

/// Apple's recognizer on the device (`SFSpeechRecognizer`, `requiresOnDeviceRecognition`): the microphone feeds it while the user talks, its text
/// comes back as `DictationEvent`s. Nothing is recognized by Apple's servers: a language the phone cannot recognize by itself is an error, not
/// a quiet fall back. The language is the phone's, else English.
@MainActor public final class AppleDictation: Dictating {
    private var engine: AVAudioEngine?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?

    public init() {}

    public func begin() async throws -> AsyncStream<DictationEvent> {
        cancel()
        try await Self.authorize()
        guard let recognizer = Self.recognizer() else {
            throw DictationError("This phone cannot recognize speech without the network for your language. Turn on Dictation in Settings → General → Keyboard.")
        }
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
        try session.setActive(true)
        #endif
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.requiresOnDeviceRecognition = true
        request.taskHint = .dictation
        request.addsPunctuation = true
        let engine = AVAudioEngine()
        let format = engine.inputNode.outputFormat(forBus: 0)
        guard format.sampleRate > 0 else { throw DictationError("The microphone is not available.") }
        Self.tap(engine.inputNode, format: format, into: request)
        engine.prepare()
        try engine.start()
        let (events, continuation) = AsyncStream.makeStream(of: DictationEvent.self)
        self.engine = engine
        self.request = request
        task = Self.recognize(recognizer, request, continuation)
        return events
    }

    public func endAudio() {
        closeMicrophone()
        request?.endAudio()
    }

    public func cancel() {
        closeMicrophone()
        task?.cancel()
        task = nil
        request = nil
    }

    private func closeMicrophone() {
        guard let engine else { return }
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
        self.engine = nil
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif
    }

    // MARK: permissions and the recognizer

    private static func authorize() async throws {
        let speech = await withCheckedContinuation { (continuation: CheckedContinuation<SFSpeechRecognizerAuthorizationStatus, Never>) in
            SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0) }
        }
        guard speech == .authorized else {
            throw DictationError("Joi may not recognize your speech: allow it in Settings → Joi → Speech Recognition.")
        }
        guard await AVAudioApplication.requestRecordPermission() else {
            throw DictationError("Joi may not use the microphone: allow it in Settings → Joi → Microphone.")
        }
    }

    /// The phone's language, else English: the first the phone can recognize on its own.
    static func recognizer() -> SFSpeechRecognizer? {
        for locale in [Locale.current, Locale(identifier: "en_US")] {
            if let recognizer = SFSpeechRecognizer(locale: locale), recognizer.isAvailable, recognizer.supportsOnDeviceRecognition {
                return recognizer
            }
        }
        return nil
    }

    // These two are not on the main actor: the microphone's thread and the recognizer's queue call their closures, and a closure made in a
    // main actor method would be checked to run there.

    private nonisolated static func tap(_ input: AVAudioInputNode, format: AVAudioFormat, into request: SFSpeechAudioBufferRecognitionRequest) {
        let sink = BufferSink(request)
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in sink.append(buffer) }
    }

    private nonisolated static func recognize(_ recognizer: SFSpeechRecognizer, _ request: SFSpeechAudioBufferRecognitionRequest,
                                              _ events: AsyncStream<DictationEvent>.Continuation) -> SFSpeechRecognitionTask {
        let relay = Relay(events)
        return recognizer.recognitionTask(with: request) { result, error in relay.take(result, error) }
    }
}

/// The microphone's buffers into the request (`append` may be called from any thread).
private final class BufferSink: @unchecked Sendable {
    private let request: SFSpeechAudioBufferRecognitionRequest
    init(_ request: SFSpeechAudioBufferRecognitionRequest) { self.request = request }
    func append(_ buffer: AVAudioPCMBuffer) { request.append(buffer) }
}

/// The recognizer's results as events: the last text is kept, so an end with an error still gives what was heard.
private final class Relay: @unchecked Sendable {
    private let events: AsyncStream<DictationEvent>.Continuation
    private let lock = NSLock()
    private var last = ""
    private var done = false

    init(_ events: AsyncStream<DictationEvent>.Continuation) { self.events = events }

    func take(_ result: SFSpeechRecognitionResult?, _ error: Error?) {
        let step: (DictationEvent?, Bool) = lock.withLock {
            guard !done else { return (nil, false) }
            if let result {
                last = result.bestTranscription.formattedString
                if result.isFinal {
                    done = true
                    return (.final(last), true)
                }
                return (.partial(last), false)
            }
            guard let error else { return (nil, false) }
            done = true
            if !last.isEmpty { return (.final(last), true) }
            return (Self.isNoSpeech(error) ? .final("") : .failed(error.localizedDescription), true)
        }
        if let event = step.0 { events.yield(event) }
        if step.1 { events.finish() }
    }

    /// The recognizer's "no speech detected" (1110) and its cancel (301 of the language recognition, 216 when the request ended with nothing).
    private static func isNoSpeech(_ error: Error) -> Bool {
        let error = error as NSError
        return error.code == 1110 || error.code == 301 || error.code == 216
    }
}
