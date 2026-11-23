import Foundation
import Testing
@testable import JoiApp

@Suite struct WakeWordTests {
    let phrases = WakeWord.phrases(name: "Joi")

    @Test func theMessageAfterTheNameIsWhatIsLeft() {
        #expect(WakeWord.remainder(in: "Hey Joi, what time is it", phrases: phrases) == "what time is it")
        #expect(WakeWord.remainder(in: "hey joi what time is it", phrases: phrases) == "what time is it")
        #expect(WakeWord.remainder(in: "Hey, Joi! Turn the light on.", phrases: phrases) == "Turn the light on.")
    }

    @Test func theNameAloneLeavesNothing() {
        #expect(WakeWord.remainder(in: "Hey Joi", phrases: phrases) == "")
        #expect(WakeWord.remainder(in: "hey joi.", phrases: phrases) == "")
    }

    @Test func otherGreetingsAndAnythingBefore() {
        #expect(WakeWord.remainder(in: "OK Joi are you there", phrases: phrases) == "are you there")
        #expect(WakeWord.remainder(in: "okay joi", phrases: phrases) == "")
        #expect(WakeWord.remainder(in: "so I said hi Joi and then", phrases: phrases) == "and then")
    }

    @Test func noWakeWordNoMatch() {
        #expect(WakeWord.remainder(in: "what time is it", phrases: phrases) == nil)
        #expect(WakeWord.remainder(in: "Joi what time is it", phrases: phrases) == nil) // her name without the greeting is not enough
        #expect(WakeWord.remainder(in: "hey joy", phrases: phrases) == nil)
        #expect(WakeWord.remainder(in: "they Joian", phrases: phrases) == nil) // whole words only
        #expect(WakeWord.remainder(in: "", phrases: phrases) == nil)
        #expect(WakeWord.remainder(in: "hey joi", phrases: []) == nil)
    }

    @Test func theFirstOneWinsAndAccentsDoNotMatter() {
        #expect(WakeWord.remainder(in: "Hey Joi hey Joi again", phrases: phrases) == "hey Joi again")
        #expect(WakeWord.remainder(in: "Hey Zoë, hello", phrases: WakeWord.phrases(name: "Zoe")) == "hello")
    }

    @Test func aNameOfTwoWords() {
        let phrases = WakeWord.phrases(name: "Mary Ann")
        #expect(WakeWord.remainder(in: "hey mary ann, hi", phrases: phrases) == "hi")
        #expect(WakeWord.remainder(in: "hey mary", phrases: phrases) == nil)
    }

    @Test func phrasesAndHints() {
        #expect(WakeWord.phrases(name: "  ") == [])
        #expect(WakeWord.phrases(name: "Joi").contains("hey Joi"))
        #expect(WakeWord.hints(name: "Joi") == ["Hey Joi", "Joi"])
    }
}

@MainActor @Suite struct VoiceTriggerTests {
    @Test func aRequestWaitsForTheChat() {
        let trigger = VoiceTrigger()
        var runs = 0
        let now = Date()
        trigger.listen(now: now)
        trigger.attach({ runs += 1 }, now: now.addingTimeInterval(3))
        #expect(runs == 1)
        // and the next ones are taken at once
        trigger.listen()
        #expect(runs == 2)
    }

    @Test func aRequestThatWaitedTooLongIsDropped() {
        let trigger = VoiceTrigger()
        var runs = 0
        let now = Date()
        trigger.listen(now: now)
        trigger.attach({ runs += 1 }, now: now.addingTimeInterval(VoiceTrigger.patience + 1))
        #expect(runs == 0)
        trigger.attach(nil)
        trigger.listen() // nobody is there: it waits, nothing runs
        #expect(runs == 0)
    }
}

@MainActor @Suite struct WakeWordControllerTests {
    let dictation = FakeDictation()
    let defaults = UserDefaults(suiteName: "joi.test.\(UUID().uuidString)")!

    private func voice() -> VoiceInput {
        VoiceInput(dictation: dictation, send: { _ in true }, restartDelay: .milliseconds(5), backoffStep: .milliseconds(5))
    }

    @Test func offUntilTheUserTurnsItOn() async throws {
        let voice = voice()
        let wake = WakeWordController(name: "Joi", voice: voice, defaults: defaults)
        #expect(!wake.enabled && !wake.listening)
        #expect(voice.state == .idle)
        wake.setEnabled(true)
        #expect(voice.state == .waiting)
        #expect(defaults.bool(forKey: WakeWordController.Keys.enabled))
        // the choice is kept
        let again = WakeWordController(name: "Joi", voice: self.voice(), defaults: defaults)
        #expect(again.enabled)
        wake.setEnabled(false)
        #expect(voice.state == .idle)
    }

    @Test func onlyWhileChargingFollowsThePower() async throws {
        let voice = voice()
        let wake = WakeWordController(name: "Joi", voice: voice, defaults: defaults)
        wake.setEnabled(true)
        wake.setOnlyWhileCharging(true)
        wake.setCharging(false)
        #expect(!wake.listening)
        #expect(voice.state == .idle)
        wake.setCharging(true)
        #expect(voice.state == .waiting)
    }
}
