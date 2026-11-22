import Foundation
import JoiProtocol
@testable import JoiClient

/// The server's end of the connections a `NodeConnection` makes, scripted by the tests. It welcomes a hello by itself (unless told not to).
final class FakeServer: @unchecked Sendable {
    private let lock = NSLock()
    private var channels: [FakeChannel] = []
    /// The next tries to open fail with this many errors of the network.
    var failOpens = 0
    var welcomes = true
    var heartbeatSeconds = 30
    var serverStartedAt: String? = "2026-11-22T09:00:00Z"
    private(set) var authorizations: [String?] = []

    func makeChannel() -> FakeChannel {
        let channel = FakeChannel(server: self)
        lock.withLock { channels.append(channel) }
        return channel
    }

    var connections: [FakeChannel] { lock.withLock { channels } }

    func opened(_ authorization: String?) -> Bool {
        lock.withLock {
            authorizations.append(authorization)
            if failOpens > 0 {
                failOpens -= 1
                return false
            }
            return true
        }
    }

    /// The nth connection, waiting until it is made.
    func connection(_ n: Int, timeout: Duration = .seconds(3)) async -> FakeChannel? {
        let end = ContinuousClock.now + timeout
        while ContinuousClock.now < end {
            if let channel = connections[safe: n] { return channel }
            try? await Task.sleep(for: .milliseconds(5))
        }
        return nil
    }

    var welcome: String {
        let started = serverStartedAt.map { #","serverStartedAt":"\#($0)""# } ?? ""
        return #"{"type":"welcome","ref":"hello","data":{"protocol":"1.8","server":"joi-server","version":"0.1.0","session":"s1","joi":{"id":"j1","name":"Joi"},"heartbeatSeconds":\#(heartbeatSeconds)\#(started)}}"#
    }
}

final class FakeChannel: FrameChannel, @unchecked Sendable {
    private let server: FakeServer
    private let lock = NSLock()
    private var frames: [Frame] = []
    private(set) var closedWith: (Int, String)?
    private let stream: AsyncThrowingStream<String, Error>
    private let continuation: AsyncThrowingStream<String, Error>.Continuation
    private var iterator: AsyncThrowingStream<String, Error>.AsyncIterator

    init(server: FakeServer) {
        self.server = server
        (stream, continuation) = AsyncThrowingStream.makeStream(of: String.self)
        iterator = stream.makeAsyncIterator()
    }

    // the channel as the app sees it

    func open(url: URL, authorization: String?) async throws {
        if !server.opened(authorization) { throw ChannelClosed(code: 1006, reason: "no network") }
    }

    func send(_ text: String) async throws {
        let frame = try JoiJSON.makeDecoder().decode(Frame.self, from: Data(text.utf8))
        lock.withLock { frames.append(frame) }
        if frame.type == "hello", server.welcomes {
            continuation.yield(server.welcome)
        }
    }

    func receive() async throws -> String {
        guard let text = try await iterator.next() else { throw ChannelClosed(code: 1000, reason: "closed") }
        return text
    }

    func close(code: Int, reason: String) async {
        lock.withLock { closedWith = (code, reason) }
        continuation.finish(throwing: ChannelClosed(code: code, reason: reason))
    }

    // the server's end

    func push(_ json: String) {
        continuation.yield(json)
    }

    /// The connection ends: the network (1006) or a close code of the server.
    func drop(_ code: Int = 1006, _ reason: String = "lost") {
        continuation.finish(throwing: ChannelClosed(code: code, reason: reason))
    }

    var sent: [Frame] { lock.withLock { frames } }

    /// The first frame of this type that was sent, waiting for it.
    func sent(_ type: String, timeout: Duration = .seconds(3)) async -> Frame? {
        let end = ContinuousClock.now + timeout
        while ContinuousClock.now < end {
            if let frame = sent.first(where: { $0.type == type }) { return frame }
            try? await Task.sleep(for: .milliseconds(5))
        }
        return nil
    }
}

extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}

/// What a connection told, collected as it comes.
final class EventLog: @unchecked Sendable {
    private let lock = NSLock()
    private var collected: [NodeConnection.Event] = []
    private var task: Task<Void, Never>?
    private(set) var finished = false

    init(_ events: AsyncStream<NodeConnection.Event>) {
        task = Task { [self] in
            for await event in events {
                lock.withLock { collected.append(event) }
            }
            lock.withLock { finished = true }
        }
    }

    var all: [NodeConnection.Event] { lock.withLock { collected } }

    /// The first event after `after` events that `matches`, waiting for it.
    func next(after: Int = 0, timeout: Duration = .seconds(3), _ matches: (NodeConnection.Event) -> Bool) async -> NodeConnection.Event? {
        let end = ContinuousClock.now + timeout
        while ContinuousClock.now < end {
            if let event = all.dropFirst(after).first(where: matches) { return event }
            try? await Task.sleep(for: .milliseconds(5))
        }
        return nil
    }

    func waitUntilFinished(timeout: Duration = .seconds(3)) async -> Bool {
        let end = ContinuousClock.now + timeout
        while ContinuousClock.now < end {
            if lock.withLock({ finished }) { return true }
            try? await Task.sleep(for: .milliseconds(5))
        }
        return false
    }

    deinit { task?.cancel() }
}
