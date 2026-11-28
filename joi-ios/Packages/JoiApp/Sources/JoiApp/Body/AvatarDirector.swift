import Foundation
import JoiProtocol

/// What the director drives: the renderer and the speech, and the chat that shows her words. It tells back when they end
/// (`animationEnded`, `speechEnded`).
@MainActor public protocol AvatarOutput: AnyObject {
    /// Plays one of the animations of her body's `.joia` (its id), nil for her rest pose.
    func play(_ animation: String?, loop: Bool)
    /// A piece of her reply as the model writes it.
    func text(turn: String, delta: String)
    /// Her reply is whole.
    func reply(turn: String, reply: ChatReply)
    /// A piece of her speech to play after the ones before; tell `speechEnded` when the last one played.
    func speak(turn: String, chunk: AudioChunk)
    /// Her turn is over: the words are all shown.
    func done(turn: String?)
    /// Her turn failed or was stopped.
    func failed(turn: String?, error: ApiError)
    /// She is in another state now.
    func state(_ state: AvatarDirector.State)
}

extension AvatarOutput {
    func state(_ state: AvatarDirector.State) {}
}

/// What her body does as her turns come and go, with the animations chosen in her dialog (`JoiAnimations`): idle while she waits (one of
/// the idle ones, another each time), talking while she speaks, and her very first waking up (cause `first_boot`): she sleeps while the
/// server writes and renders her words, which are held back; when the turn is done she wakes up, and her words come once the waking-up
/// animation is over. A missing animation is skipped (her rest pose). When she speaks, her words wait for her voice: the text of a turn is
/// shown with its first piece of speech (or when the turn ends without one), so the chat doesn't run ahead of her.
///
/// Her stance (`stance(_:)`, the server's `stance` frame) is an animation she holds: while she has one, it loops in place of the idle and
/// talking ones (her state still goes from idle to talking and back, so her words and voice are as always; her mouth and face are the
/// renderer's, over any animation). A gesture plays once and returns to it. Asleep, falling asleep and waking up win over it, and she is
/// back in it when she is awake.
///
/// The same logic as the reference node's `AvatarDirector` (Java).
@MainActor public final class AvatarDirector {
    public enum State: Equatable, Sendable {
        case idle, sleeping, wakingUp, talking, fallingAsleep
    }

    /// The cause of her very first waking up (`TurnStart`).
    public static let firstBoot = "first_boot"

    private unowned let output: AvatarOutput
    private var roles: JoiAnimations
    private var random: @MainActor (Int) -> Int
    public private(set) var state: State = .idle
    private var lastIdle: String?
    private var lastTalking: String?
    /// The animation she holds, her stance; nil for none.
    public private(set) var stance: String?
    private var current: String?
    /// A gesture of her own plays: the animation of her state waits for its end.
    private var gesturing = false

    /// The first-boot turn held back while she sleeps, nil for none.
    private var held: String?
    private var heldFrames: [() -> Void] = []
    private var heldDone = false
    /// Her turn now, and whether its speech still plays.
    private var speakingTurn: String?
    private var speechPlaying = false
    private var speechExpected = false
    /// The turn whose words wait for her voice, nil for none, and its words.
    private var unvoiced: String?
    private var unvoicedFrames: [() -> Void] = []
    /// The turn whose voice came.
    private var voiced: String?

    public init(output: AvatarOutput, roles: JoiAnimations? = nil, random: @escaping @MainActor (Int) -> Int = { Int.random(in: 0..<$0) }) {
        self.output = output
        self.roles = roles ?? JoiAnimations(idle: [], talking: [])
        self.random = random
    }

    /// The animations chosen in her dialog changed.
    public func roles(_ roles: JoiAnimations?) {
        self.roles = roles ?? JoiAnimations(idle: [], talking: [])
    }

    /// Her stance changed (`stance` frame): from now on it loops in place of the idle and talking animations, at once when she waits or
    /// talks; nil ends it, back to them. The app passes nil for an animation her body does not have. While she sleeps, wakes up or falls
    /// asleep it waits for her to be awake.
    public func stance(_ animation: String?) {
        guard animation != stance else { return }
        stance = animation
        if state == .idle {
            idle()
        } else if state == .talking {
            enter(.talking, stance ?? pick(roles.talking, lastTalking), loop: true)
            if stance == nil { lastTalking = current }
        }
    }

    /// She is shown: idle.
    public func start() {
        idle()
    }

    // MARK: her turns

    /// The user's words start a turn: she speaks when the node asked for speech.
    public func expectSpeech(_ speak: Bool) {
        speechExpected = speak
    }

    public func turnStarted(_ turn: String, cause: String?, speak: Bool) {
        if cause == Self.firstBoot, let sleeping = roles.sleeping {
            held = turn
            heldFrames = []
            heldDone = false
            enter(.sleeping, sleeping, loop: true)
        }
        speechExpected = speak
    }

    public func text(_ turn: String, _ delta: String) {
        let show = { [unowned self] in output.text(turn: turn, delta: delta) }
        if hold(turn, { [unowned self] in text(turn, delta) }) || waitForVoice(turn, show) { return }
        show()
    }

    public func reply(_ turn: String, _ reply: ChatReply) {
        let show = { [unowned self] in output.reply(turn: turn, reply: reply) }
        if hold(turn, { [unowned self] in self.reply(turn, reply) }) || waitForVoice(turn, show) {
            // she starts talking with her voice
            return
        }
        show()
        talk(turn)
    }

    public func audio(_ turn: String, _ chunk: AudioChunk) {
        if hold(turn, { [unowned self] in audio(turn, chunk) }) { return }
        voiced = turn
        showUnvoiced()
        talk(turn)
        speechPlaying = true
        output.speak(turn: turn, chunk: chunk)
    }

    public func done(_ turn: String?) {
        if let turn, turn == held {
            heldDone = true
            wakeUp()
            return
        }
        showUnvoiced()
        output.done(turn: turn)
        if let turn, turn == speakingTurn, !speechExpected || !speechPlaying {
            // no speech (or none came): back to idle once the reply is shown
            speakingTurn = nil
            idle()
        }
    }

    public func failed(_ turn: String?, _ error: ApiError) {
        if let turn, turn == held {
            // she does not wake up into a failure: the words that came are shown, then she waits
            held = nil
            heldDone = false
            replayHeld(turn)
        }
        showUnvoiced()
        output.failed(turn: turn, error: error)
        if (turn != nil && turn == speakingTurn) || state == .sleeping {
            speakingTurn = nil
            speechPlaying = false
            idle()
        }
    }

    /// The connection to the server was lost: the turn that ran is lost with it. Her held words are shown (she does not stay asleep), and
    /// she waits unless her speech still plays.
    public func disconnected() {
        if let turn = held {
            held = nil
            heldDone = false
            idle()
            replayHeld(turn)
        }
        showUnvoiced()
        if speakingTurn != nil, !speechPlaying {
            speakingTurn = nil
            idle()
        }
    }

    /// She plays an animation of her own choice (her `play_animation`) on top of what she is doing: once, and then what she was doing
    /// goes on (if she started talking meanwhile, that; her stance, when she holds one); or until something else, when it loops. Not while she sleeps, wakes up or falls
    /// asleep: those animations are not interrupted.
    public func gesture(_ animation: String?, loop: Bool) {
        guard let animation, state != .sleeping, state != .wakingUp, state != .fallingAsleep else { return }
        gesturing = !loop
        output.play(animation, loop: loop)
    }

    /// The renderer finished an animation that does not loop.
    public func animationEnded() {
        if gesturing {
            // back to what she is doing now (it may have changed while the gesture played)
            gesturing = false
            output.play(current, loop: true)
            return
        }
        if state == .wakingUp {
            let turn = held
            held = nil
            idle()
            replayHeld(turn)
        } else if state == .fallingAsleep {
            enter(.sleeping, roles.sleeping, loop: true)
        }
    }

    /// The last piece of her speech was played.
    public func speechEnded() {
        speechPlaying = false
        if state == .talking {
            speakingTurn = nil
            idle()
        }
    }

    /// She goes to sleep (the user said good night, the app is idle for long).
    public func fallAsleep() {
        if let falling = roles.fallingAsleep {
            enter(.fallingAsleep, falling, loop: false)
        } else {
            enter(.sleeping, roles.sleeping, loop: true)
        }
    }

    // MARK: inside

    /// A frame of the held turn is kept for after she wakes up; true when it was.
    private func hold(_ turn: String, _ frame: @escaping () -> Void) -> Bool {
        guard turn == held else { return false }
        heldFrames.append(frame)
        return true
    }

    /// A frame of her words is kept until her voice comes; true when it was.
    private func waitForVoice(_ turn: String, _ frame: @escaping () -> Void) -> Bool {
        if !speechExpected || turn == voiced { return false }
        if turn != unvoiced {
            showUnvoiced()
            unvoiced = turn
        }
        unvoicedFrames.append(frame)
        return true
    }

    /// The words that waited for her voice.
    private func showUnvoiced() {
        unvoiced = nil
        let frames = unvoicedFrames
        unvoicedFrames = []
        frames.forEach { $0() }
    }

    private func wakeUp() {
        if let waking = roles.wakingUp {
            enter(.wakingUp, waking, loop: false)
        } else {
            let turn = held
            held = nil
            idle()
            replayHeld(turn)
        }
    }

    /// Her held words now that she is awake, then the end of the turn as it came.
    private func replayHeld(_ turn: String?) {
        let frames = heldFrames
        heldFrames = []
        frames.forEach { $0() }
        if heldDone {
            heldDone = false
            done(turn)
        }
    }

    private func talk(_ turn: String) {
        speakingTurn = turn
        if state != .talking {
            if stance != nil {
                // she holds her stance while she talks: nothing new plays
                enterHeld(.talking)
                return
            }
            enter(.talking, pick(roles.talking, lastTalking), loop: true)
            lastTalking = current
        }
    }

    private func idle() {
        if stance != nil {
            enterHeld(.idle)
            return
        }
        enter(.idle, pick(roles.idle, lastIdle), loop: true)
        lastIdle = current
    }

    /// Idle or talking in her stance: it plays on (it is not started again when it already plays).
    private func enterHeld(_ next: State) {
        if let stance, stance == current, state == .idle || state == .talking {
            state = next
            output.state(next)
        } else {
            enter(next, stance, loop: true)
        }
    }

    private func enter(_ next: State, _ animation: String?, loop: Bool) {
        state = next
        current = animation
        if gesturing, loop, next == .idle || next == .talking {
            // her gesture is played to its end, then this
            output.state(next)
            return
        }
        gesturing = false
        output.play(animation, loop: loop)
        output.state(next)
    }

    /// One of the list, not the one played last when there is another; nil for an empty list.
    private func pick(_ ids: [String], _ last: String?) -> String? {
        if ids.isEmpty { return nil }
        if ids.count == 1 { return ids[0] }
        let others = ids.filter { $0 != last }
        return others[random(others.count)]
    }
}
