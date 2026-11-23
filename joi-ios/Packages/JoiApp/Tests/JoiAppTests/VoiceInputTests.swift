import Foundation
import Testing
import JoiClient
import JoiProtocol
@testable import JoiApp

/// A recognizer that the test tells what it hears.
@MainActor final class FakeDictation: Dictating {
    var beginError: Error?
    private(set) var begun = 0
    private(set) var ended = 0
    private(set) var cancelled = 0
    private var continuation: AsyncStream<DictationEvent>.Continuation?
    /// What `endAudio` makes the recognizer say last (nil: nothing, as a recognizer that hangs).
    var finalOnEnd: String?

    func begin() async throws -> AsyncStream<DictationEvent> {
        begun += 1
        if let beginError { throw beginError }
        let (stream, continuation) = AsyncStream.makeStream(of: DictationEvent.self)
        self.continuation = continuation
        return stream
    }

    func endAudio() {
        ended += 1
        if let finalOnEnd {
            continuation?.yield(.final(finalOnEnd))
            continuation?.finish()
        }
    }

    func cancel() {
        cancelled += 1
        continuation?.finish()
        continuation = nil
    }

    func hear(_ text: String) { continuation?.yield(.partial(text)) }
    func fail(_ why: String) { continuation?.yield(.failed(why)) }
    func finalText(_ text: String) { continuation?.yield(.final(text)); continuation?.finish() }
}

@MainActor final class VoiceInputTests {
    let dictation = FakeDictation()
    var sent: [String] = []
    var stopped = 0
    var accepts = true

    func voice(pause: Duration = .milliseconds(60), wait: Duration = .seconds(5), longest: Duration = .seconds(30),
               finishWait: Duration = .seconds(5)) -> VoiceInput {
        VoiceInput(dictation: dictation, send: { [unowned self] in sent.append($0); return accepts }, beforeListening: { [unowned self] in stopped += 1 },
                   pause: pause, waitForSpeech: wait, longest: longest, finishWait: finishWait)
    }

    func until(_ what: @MainActor () -> Bool) async throws {
        for _ in 0..<400 where !what() { try await Task.sleep(for: .milliseconds(5)) }
    }

    @Test func aPauseEndsTheTalkAndTheWordsAreSent() async throws {
        let voice = voice()
        dictation.finalOnEnd = "what time is it"
        voice.start()
        #expect(voice.state == .starting)
        #expect(stopped == 1) // talking over her stops her
        try await until { voice.state == .listening }
        dictation.hear("what time")
        try await until { voice.heard == "what time" }
        dictation.hear("what time is it")
        try await until { !sent.isEmpty }
        #expect(sent == ["what time is it"])
        #expect(voice.state == .idle)
        #expect(voice.heard == "")
        #expect(voice.note == nil)
        #expect(dictation.ended == 1)
    }

    @Test func tappingAgainSendsAtOnce() async throws {
        let voice = voice(pause: .seconds(30))
        dictation.finalOnEnd = "hello there"
        voice.toggle()
        try await until { voice.state == .listening }
        dictation.hear("hello")
        try await until { voice.heard == "hello" }
        voice.toggle()
        #expect(voice.state == .finishing)
        try await until { !sent.isEmpty }
        #expect(sent == ["hello there"])
        #expect(voice.state == .idle)
    }

    @Test func cancelSendsNothing() async throws {
        let voice = voice()
        voice.start()
        try await until { voice.state == .listening }
        dictation.hear("never mind")
        try await until { voice.heard == "never mind" }
        voice.cancel()
        #expect(voice.state == .idle)
        #expect(voice.heard == "")
        try await Task.sleep(for: .milliseconds(150)) // longer than the pause: no timer is left
        #expect(sent.isEmpty)
        #expect(dictation.ended == 0)
    }

    @Test func cancelWhileTheMicrophoneOpensClosesIt() async throws {
        let voice = voice()
        voice.start()
        voice.cancel() // begin() has not answered yet
        try await Task.sleep(for: .milliseconds(100))
        #expect(voice.state == .idle)
        #expect(dictation.cancelled >= 2) // by the cancel, and by the late begin
        #expect(sent.isEmpty)
    }

    @Test func silenceAtTheBeginningGivesUp() async throws {
        let voice = voice(wait: .milliseconds(60))
        voice.start()
        try await until { voice.state == .listening }
        try await until { voice.state == .idle }
        #expect(voice.note == "I did not hear anything.")
        #expect(sent.isEmpty)
    }

    @Test func nothingHeardAtTheEndIsNotSent() async throws {
        let voice = voice()
        voice.start()
        try await until { voice.state == .listening }
        dictation.finalText("  ")
        try await until { voice.state == .idle }
        #expect(voice.note == "I did not hear anything.")
        #expect(sent.isEmpty)
    }

    @Test func aRefusedPermissionIsTheNote() async throws {
        dictation.beginError = DictationError("Joi may not use the microphone.")
        let voice = voice()
        voice.start()
        try await until { voice.note != nil }
        #expect(voice.state == .idle)
        #expect(voice.note == "Joi may not use the microphone.")
        // the next try starts clean
        dictation.beginError = nil
        voice.start()
        #expect(voice.note == nil)
        try await until { voice.state == .listening }
    }

    @Test func aFailureEndsTheTalk() async throws {
        let voice = voice()
        voice.start()
        try await until { voice.state == .listening }
        dictation.fail("The recognizer stopped.")
        try await until { voice.state == .idle }
        #expect(voice.note == "The recognizer stopped.")
        #expect(sent.isEmpty)
    }

    @Test func wordsThatWereNotSentWaitForTheUser() async throws {
        accepts = false
        let voice = voice(pause: .seconds(30))
        dictation.finalOnEnd = "are you there"
        voice.start()
        try await until { voice.state == .listening }
        dictation.hear("are you there")
        try await until { voice.heard == "are you there" }
        voice.finish()
        try await until { voice.leftover != nil }
        #expect(voice.takeLeftover() == "are you there")
        #expect(voice.takeLeftover() == nil)
        #expect(voice.state == .idle)
    }

    @Test func aRecognizerThatDoesNotFinishDoesNotHoldTheWordsBack() async throws {
        let voice = voice(pause: .seconds(30), finishWait: .milliseconds(60))
        dictation.finalOnEnd = nil // endAudio brings no final
        voice.start()
        try await until { voice.state == .listening }
        dictation.hear("still here")
        try await until { voice.heard == "still here" }
        voice.finish()
        try await until { !sent.isEmpty }
        #expect(sent == ["still here"])
        #expect(voice.state == .idle)
    }

    @Test func aTalkThatGoesOnIsEnded() async throws {
        let voice = voice(pause: .seconds(30), longest: .milliseconds(60))
        dictation.finalOnEnd = "a long story"
        voice.start()
        try await until { voice.state == .listening }
        dictation.hear("a long story")
        try await until { !sent.isEmpty }
        #expect(sent == ["a long story"])
    }

    @Test func aSecondTalkIsNotStartedWhileOneRuns() async throws {
        let voice = voice(pause: .seconds(30))
        voice.start()
        voice.start()
        try await until { voice.state == .listening }
        voice.start()
        #expect(dictation.begun == 1)
    }
}
