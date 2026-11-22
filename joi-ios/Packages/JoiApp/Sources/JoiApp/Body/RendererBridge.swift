import Foundation
import JoiProtocol
import JoiClient

/// What plays her animations: the renderer. It tells back when an animation that does not loop is over.
@MainActor public protocol AnimationPlayer: AnyObject {
    /// Plays one of the animations of her `.joia` (its id), nil for her rest pose: looping as made, or once.
    func play(_ animation: String?, loop: Bool)
    /// The server is gone (`true`) or back: she is frozen and gray, or goes on.
    func setOffline(_ offline: Bool)
    /// Called once an animation played with `play` that does not loop is over.
    var onEnded: (@MainActor () -> Void)? { get set }
}

/// The messages of the renderer page (`joi-node/src/main/renderer/src/main.js`, its native mode) and the files it reads: the part of the
/// renderer that has no WebKit, so that it is tried by tests. A view shows the page and gives `send` (what the page gets) and tells
/// `pageReady`, `ended` and `failed` (what the page says); until then what is to be shown is kept and sent when the page asks.
///
/// Like the node's `RendererServer`: the last model, play, toggles and offline messages are sent again to a page that opens later, and an
/// animation that does not loop also ends when the page does not tell within its length and a few seconds (it hangs, or none is open), so
/// her states go on without a renderer.
@MainActor public final class RendererBridge: AnimationPlayer {
    /// How much longer than its length an animation may take before it is taken as ended.
    var graceSeconds = 3.0
    /// The length of an animation whose clips do not say theirs.
    static let unknownSeconds = 10.0

    public var onEnded: (@MainActor () -> Void)?
    /// Called when the page has her model (each time one is shown); `false` when a new one is on its way.
    public var onDrawn: (@MainActor (Bool) -> Void)?
    /// Sends a message to the page (nil while none is open).
    public var send: (@MainActor (JSONValue) -> Void)?
    /// Her files, as the scheme handler of the view reads them (from any thread).
    public let files = BodyFilesBox()

    private var body = BodyFiles.none
    private var model: JSONValue?
    private var playMessage: JSONValue?
    private var togglesMessage: JSONValue?
    private var offlineMessage: JSONValue?
    private var playing = 0
    private var playingLoops = true
    private var watchdog: Task<Void, Never>?
    private var modelCount = 0
    /// The animation she should be playing while it loops, played again once a body is shown (she was asked for it before her body came).
    private var wanted: String?

    public init() {}

    /// The page is open and asks for what is to be shown.
    public func pageReady() {
        [model, togglesMessage, playMessage, offlineMessage].compactMap { $0 }.forEach { send?($0) }
    }

    /// Shows her body.
    public func show(_ body: BodyFiles) {
        self.body = body
        files.set(body)
        playMessage = nil
        guard body.model != nil else {
            model = nil
            broadcast(Self.message("stop"))
            return
        }
        modelCount += 1
        onDrawn?(false)
        let message = Self.message("model", ["src": .string("body/model.vrm?\(modelCount)")])
        model = message
        broadcast(message)
        if let wanted { play(wanted, loop: true) }
    }

    /// Which parts of her model are shown, every toggle of it: a page applies it once her model is there.
    public func setToggles(_ toggles: [BodyToggle]) {
        let json = (try? JSONEncoder().encode(toggles)).flatMap { try? JSONDecoder().decode(JSONValue.self, from: $0) } ?? .array([])
        let message = Self.message("toggles", ["toggles": json])
        togglesMessage = message
        broadcast(message)
    }

    public func setOffline(_ offline: Bool) {
        let message = Self.message("offline", ["offline": .bool(offline)])
        offlineMessage = message
        broadcast(message)
    }

    public func play(_ animation: String?, loop: Bool) {
        if loop { wanted = animation }
        playing += 1
        let id = playing
        playingLoops = loop
        watchdog?.cancel()
        guard let animation, let found = body.animations[animation], let steps = found.steps, !steps.isEmpty else {
            // her body has no such animation (or she has none): at rest, and an animation that does not loop is over at once
            playMessage = nil
            broadcast(Self.message("stop"))
            if !loop {
                Task { @MainActor in self.finish(id) }
            }
            return
        }
        var seconds = 0.0
        var order: [String] = []
        var json: [JSONValue] = []
        for step in steps {
            let index = order.firstIndex(of: step.clip) ?? { order.append(step.clip); return order.count - 1 }()
            var members: [String: JSONValue] = ["src": .int(index), "loop": .bool(loop ? (step.loop ?? false) : false),
                                                "speed": .double(step.speed ?? 1), "inPlace": .bool(step.inPlace ?? false),
                                                "reverse": .bool(step.reverse ?? false)]
            if let rotation = step.rotation { members["rotation"] = .array(rotation.map { .double($0) }) }
            if let blend = step.blend { members["blend"] = .double(blend) }
            json.append(.object(members))
            let speed = (step.speed ?? 1) > 0 ? step.speed ?? 1 : 1
            seconds += (body.lengths[step.clip] ?? Self.unknownSeconds) / speed
        }
        let message = Self.message("play", [
            "id": .int(id),
            "clips": .array(order.map { .string("body/clip/\($0).vrma") }),
            "sequence": .object(["steps": .array(json), "loop": .bool(loop)]),
        ])
        playMessage = message
        broadcast(message)
        if !loop {
            let grace = graceSeconds
            watchdog = Task { [weak self] in
                try? await Task.sleep(for: .seconds(seconds + grace))
                guard !Task.isCancelled else { return }
                self?.finish(id)
            }
        }
    }

    /// A piece of her voice is coming (before it plays): the page reads her mouth from the sound. Not kept for a page that opens later.
    public func speech(_ number: Int, _ chunk: AudioChunk) {
        broadcast(Self.message("speech", ["index": .int(number), "data": .string(chunk.data.base64EncodedString())]))
    }

    /// The piece of that number plays now: her mouth moves from here on.
    public func speechStarted(_ number: Int) {
        broadcast(Self.message("speech-start", ["index": .int(number)]))
    }

    /// Her speech is over or was stopped: her mouth closes.
    public func speechStopped() {
        broadcast(Self.message("speech-stop"))
    }

    /// The page tells that her model is there.
    public func modelLoaded() {
        onDrawn?(true)
    }

    /// The page tells that an animation that does not loop is over (`ended`), or could not play (`failed`).
    public func ended(_ id: Int) {
        finish(id)
    }

    /// An animation ended (a page told, or the watchdog): told once, for what plays now and does not loop.
    private func finish(_ id: Int) {
        guard id == playing, !playingLoops else { return }
        playingLoops = true
        watchdog?.cancel()
        onEnded?()
    }

    private func broadcast(_ message: JSONValue) {
        send?(message)
    }

    static func message(_ type: String, _ members: [String: JSONValue] = [:]) -> JSONValue {
        .object(members.merging(["type": .string(type)]) { $1 })
    }
}

/// Her files for the scheme handler, which asks from threads of WebKit's.
public final class BodyFilesBox: @unchecked Sendable {
    private let lock = NSLock()
    private var files = BodyFiles.none

    public init() {}

    func set(_ files: BodyFiles) {
        lock.withLock { self.files = files }
    }

    /// The file a path of the renderer's body names: `body/model.vrm`, `body/clip/<uuid>.vrma`; nil for none.
    public func file(_ path: String) -> URL? {
        let files = lock.withLock { self.files }
        if path == "body/model.vrm" { return files.model }
        if path.hasPrefix("body/clip/"), path.hasSuffix(".vrma") {
            return files.clips[String(path.dropFirst("body/clip/".count).dropLast(".vrma".count))]
        }
        return nil
    }
}
