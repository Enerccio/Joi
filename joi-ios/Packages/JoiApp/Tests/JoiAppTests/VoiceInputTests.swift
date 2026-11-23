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

    private(set) var hintsGiven: [[String]] = []

    func begin(hints: [String]) async throws -> AsyncStream<DictationEvent> {
        begun += 1
        hintsGiven.append(hints)
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

    // MARK: the wake word

    func armed(wakeWait: Duration = .seconds(5), recycle: Duration = .seconds(30)) -> VoiceInput {
        let voice = VoiceInput(dictation: dictation, send: { [unowned self] in sent.append($0); return accepts }, beforeListening: { [unowned self] in stopped += 1 },
                               pause: .milliseconds(60), waitForSpeech: .seconds(5), longest: .seconds(30), finishWait: .seconds(5), wakeWait: wakeWait,
                               recycleAfter: recycle, restartDelay: .milliseconds(10), backoffStep: .milliseconds(10))
        voice.arm(phrases: WakeWord.phrases(name: "Joi"), hints: WakeWord.hints(name: "Joi"))
        return voice
    }

    @Test func theWakeWordStartsTheTalkAndWhatFollowsIsTheMessage() async throws {
        let voice = armed()
        #expect(voice.state == .waiting)
        try await until { dictation.begun == 1 }
        #expect(dictation.hintsGiven == [["Hey Joi", "Joi"]])
        dictation.hear("what a nice day") // not for her
        dictation.hear("what a nice day hey Joi")
        try await until { voice.state == .listening }
        #expect(voice.heard == "")
        dictation.hear("what a nice day hey Joi what time is it")
        try await until { voice.heard == "what time is it" }
        dictation.finalOnEnd = "what a nice day hey Joi what time is it"
        try await until { !sent.isEmpty } // the pause ends it
        #expect(sent == ["what time is it"]) // without the wake word
        // and she listens for the wake word again
        try await until { dictation.begun == 2 }
        #expect(voice.state == .waiting)
        #expect(voice.note == nil)
    }

    @Test func theNameAloneWaitsAFewSecondsForTheWords() async throws {
        let voice = armed(wakeWait: .milliseconds(60))
        try await until { dictation.begun == 1 }
        dictation.hear("Hey Joi")
        try await until { voice.state == .listening }
        try await until { voice.state == .waiting && dictation.begun == 2 } // nothing came: back to waiting, no note
        #expect(voice.note == nil)
        #expect(sent.isEmpty)
    }

    @Test func theMessageInTheSameBreathAndAFinalWithTheWakeWord() async throws {
        let voice = armed()
        try await until { dictation.begun == 1 }
        dictation.hear("Hey Joi turn it off")
        try await until { voice.heard == "turn it off" }
        dictation.finalOnEnd = "Hey Joi, turn it off."
        try await until { !sent.isEmpty }
        #expect(sent == ["turn it off."])
    }

    @Test func theRecognitionIsMadeAgainWhenItEnds() async throws {
        let voice = armed()
        try await until { dictation.begun == 1 }
        dictation.finalText("just talking")
        try await until { dictation.begun == 2 }
        #expect(voice.state == .waiting)
        #expect(sent.isEmpty)
    }

    @Test func aFailureIsTriedAgainLater() async throws {
        let voice = armed()
        try await until { dictation.begun == 1 }
        dictation.fail("interrupted")
        try await until { dictation.begun == 2 }
        #expect(voice.state == .waiting)
        #expect(voice.note == nil)
    }

    @Test func aMicrophoneThatCannotBeOpenedIsTriedAgain() async throws {
        dictation.beginError = NSError(domain: "audio", code: 1)
        let voice = armed()
        try await until { dictation.begun >= 3 }
        dictation.beginError = nil
        try await until { dictation.begun >= 4 && voice.state == .waiting }
        #expect(voice.armed)
    }

    @Test func aRefusedPermissionDisarmsIt() async throws {
        dictation.beginError = DictationError("Joi may not use the microphone.")
        let voice = armed()
        try await until { voice.note != nil }
        #expect(!voice.armed)
        #expect(voice.state == .idle)
        #expect(voice.note == "Joi may not use the microphone.")
        let begun = dictation.begun
        try await Task.sleep(for: .milliseconds(80))
        #expect(dictation.begun == begun)
    }

    @Test func herVoiceIsNotListenedTo() async throws {
        let voice = armed()
        try await until { voice.state == .waiting && dictation.begun == 1 }
        voice.suspend(true)
        #expect(voice.state == .idle)
        #expect(dictation.cancelled >= 1) // the microphone is closed
        try await Task.sleep(for: .milliseconds(80))
        #expect(dictation.begun == 1)
        voice.suspend(false)
        try await until { dictation.begun == 2 }
        #expect(voice.state == .waiting)
    }

    @Test func theButtonTakesTheTalkOverFromTheWakeWord() async throws {
        let voice = armed()
        try await until { voice.state == .waiting && dictation.begun == 1 }
        voice.toggle()
        #expect(voice.state == .starting)
        #expect(stopped == 1)
        try await until { voice.state == .listening }
        dictation.finalOnEnd = "by hand"
        dictation.hear("by hand")
        try await until { !sent.isEmpty }
        #expect(sent == ["by hand"])
        try await until { voice.state == .waiting } // and the wake word goes on
    }

    @Test func cancellingATalkGoesBackToWaiting() async throws {
        let voice = armed()
        try await until { dictation.begun == 1 }
        dictation.hear("hey Joi be quiet")
        try await until { voice.state == .listening }
        voice.cancel()
        #expect(sent.isEmpty)
        try await until { voice.state == .waiting && dictation.begun == 2 }
    }

    @Test func disarmingStopsIt() async throws {
        let voice = armed()
        try await until { dictation.begun == 1 }
        voice.disarm()
        #expect(voice.state == .idle && !voice.armed)
        try await Task.sleep(for: .milliseconds(80))
        #expect(dictation.begun == 1)
    }

    @Test func aRecognitionThatGoesOnForTooLongIsMadeAgain() async throws {
        _ = armed(recycle: .milliseconds(60))
        try await until { dictation.begun >= 2 }
    }
}
