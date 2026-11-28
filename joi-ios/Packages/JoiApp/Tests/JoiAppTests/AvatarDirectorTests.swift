import Foundation
import Testing
import JoiProtocol
@testable import JoiApp

/// Records what the director does.
@MainActor final class RecordingOutput: AvatarOutput {
    var log: [String] = []
    func play(_ animation: String?, loop: Bool) { log.append("play \(animation ?? "rest")\(loop ? " loop" : "")") }
    func text(turn: String, delta: String) { log.append("text \(delta)") }
    func reply(turn: String, reply: ChatReply) { log.append("reply \(reply.text)") }
    func speak(turn: String, chunk: AudioChunk) { log.append("speak \(chunk.index)") }
    func done(turn: String?) { log.append("done") }
    func failed(turn: String?, error: ApiError) { log.append("failed \(error.message)") }
    func state(_ state: AvatarDirector.State) { log.append("state \(state)") }
    /// What happened, without the states.
    var actions: [String] { log.filter { !$0.hasPrefix("state") } }
}

@MainActor @Suite struct AvatarDirectorTests {
    let out = RecordingOutput()
    let roles = JoiAnimations(sleeping: "sleep", fallingAsleep: "fall", wakingUp: "wake", idle: ["idle1"], talking: ["talk1"])

    private func director(_ roles: JoiAnimations? = nil) -> AvatarDirector {
        AvatarDirector(output: out, roles: roles ?? self.roles, random: { _ in 0 })
    }

    private func reply(_ text: String) -> ChatReply {
        ChatReply(messageId: "m", text: text, usage: Usage(promptTokens: 1, completionTokens: 1, firstTokenMs: 1, textMs: 1, speechMs: 1))
    }

    private func chunk(_ index: Int) -> AudioChunk {
        AudioChunk(index: index, text: "x", mimeType: "audio/wav", data: Data())
    }

    @Test func sheIsIdleWhenShown() {
        let d = director()
        d.start()
        #expect(out.actions == ["play idle1 loop"])
        #expect(d.state == .idle)
    }

    @Test func sheTalksWithHerVoiceAndIsIdleAgainWhenItEnds() {
        let d = director()
        d.start()
        d.expectSpeech(true)
        d.text("t1", "Hel")
        d.text("t1", "lo")
        #expect(out.actions == ["play idle1 loop"]) // the words wait for the voice
        d.audio("t1", chunk(0))
        #expect(out.actions == ["play idle1 loop", "text Hel", "text lo", "play talk1 loop", "speak 0"])
        d.audio("t1", chunk(1))
        d.done("t1")
        #expect(d.state == .talking) // her voice still plays
        d.speechEnded()
        #expect(d.state == .idle)
        #expect(out.actions.last == "play idle1 loop")
    }

    @Test func withoutSpeechTheWordsComeAtOnceAndSheTalksUntilTheTurnEnds() {
        let d = director()
        d.start()
        d.expectSpeech(false)
        d.text("t1", "Hi")
        d.reply("t1", reply("Hi"))
        #expect(out.actions == ["play idle1 loop", "text Hi", "reply Hi", "play talk1 loop"])
        d.done("t1")
        #expect(out.actions.suffix(2) == ["done", "play idle1 loop"])
    }

    @Test func aTurnThatEndsWithoutHerVoiceShowsTheWordsThen() {
        let d = director()
        d.start()
        d.expectSpeech(true)
        d.text("t1", "Hi")
        d.done("t1")
        #expect(out.actions == ["play idle1 loop", "text Hi", "done"])
    }

    @Test func herFirstWakingUpHoldsHerWordsUntilTheAnimationsAreOver() {
        let d = director()
        d.start()
        d.turnStarted("s1", cause: "first_boot", speak: false)
        #expect(d.state == .sleeping)
        d.text("s1", "I'm ")
        d.text("s1", "awake.")
        d.reply("s1", reply("I'm awake."))
        #expect(out.actions == ["play idle1 loop", "play sleep loop"])
        d.done("s1")
        #expect(d.state == .wakingUp)
        #expect(out.actions.last == "play wake")
        d.animationEnded()
        // awake: her words come, and the end of the turn as it came
        #expect(out.actions == ["play idle1 loop", "play sleep loop", "play wake", "play idle1 loop", "text I'm ", "text awake.", "reply I'm awake.",
                                "play talk1 loop", "done", "play idle1 loop"])
    }

    @Test func withoutASleepingAnimationThereIsNothingToHold() {
        let d = director(JoiAnimations(idle: ["idle1"], talking: []))
        d.start()
        d.turnStarted("s1", cause: "first_boot", speak: false)
        d.text("s1", "Hi")
        #expect(out.actions == ["play idle1 loop", "text Hi"])
    }

    @Test func aFailedHeldTurnShowsWhatCameAndSheWaits() {
        let d = director()
        d.start()
        d.turnStarted("s1", cause: "first_boot", speak: false)
        d.text("s1", "Half")
        d.failed("s1", ApiError(code: "x", message: "broke"))
        #expect(out.actions.suffix(3) == ["text Half", "failed broke", "play idle1 loop"])
        #expect(d.state == .idle)
    }

    @Test func aLostConnectionWakesHerAndShowsWhatShouldHaveBeenHeld() {
        let d = director()
        d.start()
        d.turnStarted("s1", cause: "first_boot", speak: false)
        d.text("s1", "Half")
        d.disconnected()
        #expect(d.state == .idle)
        #expect(out.actions.suffix(2) == ["play idle1 loop", "text Half"])
    }

    @Test func aGestureIsPlayedOnceAndWhatSheWasDoingGoesOn() {
        let d = director()
        d.start()
        d.gesture("wave", loop: false)
        #expect(out.actions.last == "play wave")
        d.animationEnded()
        #expect(out.actions.last == "play idle1 loop")
        d.gesture("dance", loop: true)
        #expect(out.actions.last == "play dance loop")
    }

    @Test func aGestureDoesNotInterruptSleepingOrWakingUp() {
        let d = director()
        d.start()
        d.fallAsleep()
        #expect(d.state == .fallingAsleep)
        d.gesture("wave", loop: false)
        #expect(!out.actions.contains("play wave"))
        d.animationEnded()
        #expect(d.state == .sleeping)
        d.gesture("wave", loop: false)
        #expect(!out.actions.contains("play wave"))
    }

    @Test func whileAGestureOfHerOwnPlaysTheStateWaitsForItsEnd() {
        let d = director()
        d.start()
        d.gesture("wave", loop: false)
        d.expectSpeech(true)
        d.audio("t1", chunk(0))
        #expect(!out.actions.contains("play talk1 loop")) // the gesture is played to its end
        d.animationEnded()
        #expect(out.actions.last == "play talk1 loop")
    }

    @Test func idleAnimationsTakeTurnsAndNeverRepeatTheLastOne() {
        let d = AvatarDirector(output: out, roles: JoiAnimations(idle: ["a", "b", "c"], talking: []), random: { _ in 0 })
        d.start()
        d.expectSpeech(false)
        for turn in ["t1", "t2", "t3"] {
            d.reply(turn, reply("x"))
            d.done(turn)
        }
        let idles = out.actions.filter { $0.hasPrefix("play") && $0.hasSuffix("loop") }
        // after `a`, the first of the others is `b`; with no talking animation she is at rest while she talks
        #expect(idles.first == "play a loop")
        for (before, after) in zip(idles.filter { $0 != "play rest loop" }, idles.filter { $0 != "play rest loop" }.dropFirst()) {
            #expect(before != after)
        }
    }

    @Test func fallingAsleepWithoutTheAnimationSleepsAtOnce() {
        let d = director(JoiAnimations(sleeping: "sleep", idle: [], talking: []))
        d.fallAsleep()
        #expect(d.state == .sleeping)
        #expect(out.actions == ["play sleep loop"])
    }

    @Test func herStanceLoopsThroughHerTurnsInPlaceOfIdleAndTalkingUntilItEnds() {
        let d = director()
        d.start()
        d.stance("dance")
        #expect(out.actions == ["play idle1 loop", "play dance loop"])
        #expect(d.stance == "dance")
        // she talks: her words and voice as always, the dance plays on
        d.expectSpeech(true)
        d.reply("t1", reply("Wheee"))
        d.audio("t1", chunk(0))
        #expect(d.state == .talking)
        d.speechEnded()
        d.done("t1")
        #expect(d.state == .idle)
        #expect(Array(out.actions.dropFirst(2)) == ["reply Wheee", "speak 0", "done"])
        // a gesture plays once and returns to the dance
        d.gesture("wave", loop: false)
        d.animationEnded()
        #expect(Array(out.actions.suffix(2)) == ["play wave", "play dance loop"])
        // asleep wins over it; awake she is back in it
        d.fallAsleep()
        d.animationEnded()
        #expect(out.actions.last == "play sleep loop")
        d.turnStarted("s1", cause: AvatarDirector.firstBoot, speak: false)
        d.done("s1")
        d.animationEnded()
        #expect(Array(out.actions.filter { $0 != "done" }.suffix(2)) == ["play wake", "play dance loop"])
        // ending it goes back to idle; the same again changes nothing
        d.stance(nil)
        #expect(out.actions.last == "play idle1 loop")
        let count = out.actions.count
        d.stance(nil)
        #expect(out.actions.count == count)
    }
}
