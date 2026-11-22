import Foundation
import JoiProtocol

public enum NodeConnectionError: Error, Equatable, Sendable {
    /// Nothing was sent: there is no connection now. (She does not keep it for later: the user says it again when she is back.)
    case notConnected
    case alreadyConnected
    case welcomeTimeout
}

/// The app's end of the node WebSocket (`asyncapi.yaml`), as the reference node's `NodeSocket` is: `hello` and the manifest, her
/// turns told as `events` as their frames come, tool calls answered by the `toolHandler`, the heartbeat, and connecting again
/// (after 1 s, 2 s, 4 s... up to 30 s) with `hello.resumed` when the connection is lost.
///
/// It does not know `URLSession`: the connection is a `FrameChannel`.
public actor NodeConnection {
    public struct Options: Sendable {
        /// What a second of the heartbeat is (tests make it short).
        public var heartbeatUnit: Duration = .seconds(1)
        /// What a second of the wait before a new try is.
        public var retryUnit: Duration = .seconds(1)
        public var maxRetrySeconds = 30
        public var welcomeTimeout: Duration = .seconds(15)

        public init() {}
    }

    /// A frame of hers, with the ids it came with: `ref` is the turn it belongs to.
    public struct Incoming: Sendable {
        public let id: String?
        public let ref: String?
        public let data: NodeFrameData

        public init(id: String? = nil, ref: String? = nil, data: NodeFrameData) {
            self.id = id
            self.ref = ref
            self.data = data
        }
    }

    public enum Event: Sendable {
        /// A try to connect again begins.
        case connecting
        /// The connection was lost: it connects again until it is back (`reconnected`) or over (`closed`).
        case disconnected(code: Int, reason: String)
        /// The next try is in this many seconds.
        case retrying(seconds: Int)
        /// It is connected again (`hello` with `resumed`); a turn that ran when it was lost is lost.
        case reconnected(Welcome)
        /// Over for good: `close()`, or a close code after which it does not try again (unauthorized, version, replaced, not found, revoked).
        case closed(code: Int, reason: String)
        /// A frame of the server that is for the app: her turn (`text`, `reply`, `audio`, `done`...), `event`, `task`, `part_kept`...
        /// (`ping`, `welcome` and `tool_call` are answered here; a type of a newer version is not told).
        case frame(Incoming)
    }

    /// The close codes after which it does not connect again: unauthorized, version, replaced, not found, revoked.
    static let finalCodes: Set<Int> = [4001, 4002, 4003, 4004, 4009]

    public nonisolated let events: AsyncStream<Event>
    private let output: AsyncStream<Event>.Continuation

    private let url: URL
    private let hello: Hello
    private var manifest: Manifest
    private let authorizer: @Sendable () async throws -> String?
    private let makeChannel: @Sendable () -> any FrameChannel
    private let options: Options

    private var toolHandler: (@Sendable (ToolCall) async -> ToolResult)?
    private var channel: (any FrameChannel)?
    /// Each try to connect is a generation; what an older one says is ignored.
    private var generation = 0
    private var endedGeneration = -1
    private var connected = false
    private var closing = false
    private var over = false
    private var attempting = false
    private var tries = 0
    private var lastHeard = ContinuousClock.now
    private var turns = 0
    private var pings = 0
    private var readTask: Task<Void, Never>?
    private var heartbeatTask: Task<Void, Never>?
    private var retryTask: Task<Void, Never>?
    /// What it knows of the connection it lost, told in the next hello.
    private var serverStartedAt: String?
    private var lostAt: String?
    private var lostCode: Int?

    /// - Parameters:
    ///   - url: the node WebSocket (`JoiClient.nodeSocket`).
    ///   - authorizer: the `Authorization` header of each try (an access token that ended is made again: `JoiClient.authorization`).
    public init(url: URL, hello: Hello, manifest: Manifest, options: Options = Options(),
                authorizer: @escaping @Sendable () async throws -> String?,
                channel: @escaping @Sendable () -> any FrameChannel = { URLSessionWebSocketChannel() }) {
        (events, output) = AsyncStream.makeStream(of: Event.self)
        self.url = url
        self.hello = hello
        self.manifest = manifest
        self.options = options
        self.authorizer = authorizer
        self.makeChannel = channel
    }

    // MARK: use

    /// Connects, and returns hers once the server welcomes it. Throws when it cannot (no try again: that is for a connection that was).
    public func connect() async throws -> Welcome {
        guard !over, !connected else { throw NodeConnectionError.alreadyConnected }
        return try await open(hello, authorization: try await authorizer())
    }

    public var isConnected: Bool { connected }

    /// Answers the calls of her tools (`<node>__<tool>` are this node's tools in the manifest).
    public func setToolHandler(_ handler: (@Sendable (ToolCall) async -> ToolResult)?) {
        toolHandler = handler
    }

    /// The user said something: returns the id of the turn her answer will refer to.
    public func say(_ text: String, speak: Bool? = nil, reasoning: Bool? = nil, withEvents: Bool? = nil) async throws -> String {
        turns += 1
        let id = "t\(turns)"
        try await send(Frame.say(Say(text: text, speak: speak, reasoning: reasoning, withEvents: withEvents), id: id))
        return id
    }

    /// Stops a turn of this node (nil: the one that runs).
    public func stop(turn: String? = nil) async throws {
        try await send(Frame.stop(Stop(turn: turn)))
    }

    /// Tells her where she is shown (the Looking Glass), as the server starts her turn about it.
    public func display(_ lookingGlass: Bool) async throws {
        try await send(Frame.display(Display(lookingGlass: lookingGlass)))
    }

    /// The tools changed: sent at once when connected, and again after a reconnect. Give it a higher `version`.
    public func setManifest(_ manifest: Manifest) async throws {
        self.manifest = manifest
        if connected {
            try await send(Frame.manifest(manifest))
        }
    }

    /// Sends any frame.
    public func send(_ frame: Frame) async throws {
        guard connected, let channel else { throw NodeConnectionError.notConnected }
        try await channel.send(Self.encode(frame))
    }

    /// Tries to connect again now, without waiting for the next try (the user asked). Nothing when it is connected or over.
    public func reconnectNow() {
        guard !connected, !closing, !over, !attempting, retryTask != nil else { return }
        retryTask?.cancel()
        retryTask = nil
        tries = 0
        Task { await self.attempt() }
    }

    /// Closes it for good.
    public func close() async {
        guard !closing else { return }
        closing = true
        generation += 1
        retryTask?.cancel()
        readTask?.cancel()
        heartbeatTask?.cancel()
        connected = false
        let channel = self.channel
        self.channel = nil
        await channel?.close(code: 1000, reason: "closed by the app")
        finish(code: 1000, reason: "closed by the app")
    }

    // MARK: connecting

    private func open(_ hello: Hello, authorization: String?) async throws -> Welcome {
        generation += 1
        let gen = generation
        let channel = makeChannel()
        self.channel = channel
        do {
            try await channel.open(url: url, authorization: authorization)
            try await channel.send(Self.encode(try Frame.hello(hello, id: "hello")))
            let welcome = try await welcomed(channel)
            guard gen == generation, !closing else { throw ChannelClosed(code: 1000, reason: "closed") }
            lastHeard = .now
            connected = true
            try await channel.send(Self.encode(try Frame.manifest(manifest)))
            serverStartedAt = welcome.serverStartedAt
            tries = 0
            readTask = Task { await self.read(channel, gen) }
            heartbeatTask = Task { await self.beat(gen, seconds: welcome.heartbeatSeconds) }
            return welcome
        } catch {
            if gen == generation {
                self.channel = nil
                connected = false
            }
            await channel.close(code: 1001, reason: "no welcome")
            throw error
        }
    }

    /// Reads until the server's `welcome`; not in time, it is `welcomeTimeout` (and the channel is closed).
    private func welcomed(_ channel: any FrameChannel) async throws -> Welcome {
        let timeout = options.welcomeTimeout
        let late = Flag()
        do {
            return try await withThrowingTaskGroup(of: Welcome.self) { group in
                group.addTask {
                    while true {
                        let frame = try Self.decode(try await channel.receive())
                        if case .welcome(let welcome)? = try? frame.typedData() {
                            return welcome
                        }
                        // anything else before welcome is not for us (the server closes the connection when it refuses)
                    }
                }
                group.addTask {
                    try await Task.sleep(for: timeout)
                    late.set()
                    await channel.close(code: 4008, reason: "no welcome")
                    throw NodeConnectionError.welcomeTimeout
                }
                defer { group.cancelAll() }
                return try await group.next()!
            }
        } catch {
            // closing the channel made the reader fail too, and it may be the one that is told first
            throw late.isSet ? NodeConnectionError.welcomeTimeout : error
        }
    }

    // MARK: while connected

    private func read(_ channel: any FrameChannel, _ gen: Int) async {
        while !Task.isCancelled {
            do {
                received(try await channel.receive(), gen)
            } catch let closed as ChannelClosed {
                ended(gen, closed.code, closed.reason)
                return
            } catch {
                ended(gen, 1006, "\(error)")
                return
            }
        }
    }

    private func received(_ text: String, _ gen: Int) {
        guard gen == generation, let frame = try? Self.decode(text) else { return }
        lastHeard = .now
        guard let data = try? frame.typedData() else { return }
        switch data {
        case .unknown, .welcome, .pong, .toolCancel:
            // a type of a newer version is ignored; the tools of this node finish quickly, a long one would stop on toolCancel
            break
        case .ping:
            Task { try? await self.send(Frame.pong(ref: frame.id)) }
        case .toolCall(let call):
            let handler = toolHandler
            Task {
                let result = await handler?(call) ?? ToolResult(callId: call.callId, ok: false, content: "This node has no tools", error: "unavailable")
                try? await self.send(Frame.toolResult(result))
            }
        default:
            output.yield(.frame(Incoming(id: frame.id, ref: frame.ref, data: data)))
        }
    }

    /// A ping now and then, and the end of a connection the server stopped answering on.
    private func beat(_ gen: Int, seconds: Int) async {
        let every = options.heartbeatUnit * max(1, seconds / 2)
        let silent = options.heartbeatUnit * max(2, seconds * 2)
        while !Task.isCancelled {
            try? await Task.sleep(for: every)
            guard gen == generation, connected, !Task.isCancelled else { return }
            if lastHeard.duration(to: .now) > silent {
                let channel = self.channel
                ended(gen, 4008, "The server did not answer in time")
                await channel?.close(code: 4008, reason: "silent")
                return
            }
            pings += 1
            try? await send(Frame.ping(id: "ping-\(pings)"))
        }
    }

    /// The connection of that generation ended (once; an older one's end is ignored).
    private func ended(_ gen: Int, _ code: Int, _ reason: String) {
        guard gen == generation, endedGeneration != gen else { return }
        endedGeneration = gen
        readTask?.cancel()
        heartbeatTask?.cancel()
        connected = false
        channel = nil
        if closing || Self.finalCodes.contains(code) {
            finish(code: code, reason: reason)
            return
        }
        lostAt = Date.now.formatted(.iso8601)
        lostCode = code
        output.yield(.disconnected(code: code, reason: reason))
        scheduleRetry()
    }

    private func finish(code: Int, reason: String) {
        guard !over else { return }
        over = true
        output.yield(.closed(code: code, reason: reason))
        output.finish()
    }

    // MARK: connecting again

    private func scheduleRetry() {
        guard !closing, !over else { return }
        let seconds = min(options.maxRetrySeconds, 1 << min(tries, 5))
        tries += 1
        output.yield(.retrying(seconds: seconds))
        let wait = options.retryUnit * seconds
        retryTask = Task {
            try? await Task.sleep(for: wait)
            guard !Task.isCancelled else { return }
            await self.attempt()
        }
    }

    /// A try to connect again: `hello` with what it knows of the connection it lost.
    private func attempt() async {
        guard !closing, !over, !attempting else { return }
        attempting = true
        defer { attempting = false }
        retryTask = nil
        output.yield(.connecting)
        var again = hello
        again.resumed = true
        again.resume = Resume(serverStartedAt: serverStartedAt, lostAt: lostAt, closeCode: lostCode)
        do {
            let welcome = try await open(again, authorization: try await authorizer())
            output.yield(.reconnected(welcome))
        } catch let closed as ChannelClosed where Self.finalCodes.contains(closed.code) {
            finish(code: closed.code, reason: closed.reason)
        } catch {
            scheduleRetry()
        }
    }

    // MARK: frames as text

    static func encode(_ frame: Frame) throws -> String {
        String(decoding: try JoiJSON.makeEncoder().encode(frame), as: UTF8.self)
    }

    static func decode(_ text: String) throws -> Frame {
        try JoiJSON.makeDecoder().decode(Frame.self, from: Data(text.utf8))
    }
}

/// A flag that tasks set and read.
private final class Flag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false

    func set() { lock.withLock { value = true } }
    var isSet: Bool { lock.withLock { value } }
}
