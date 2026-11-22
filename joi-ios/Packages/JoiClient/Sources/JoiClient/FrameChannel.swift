import Foundation

/// The connection closed (or was lost): `code` is the WebSocket's close code, 1006 when there was none (the network).
public struct ChannelClosed: Error, Equatable, Sendable {
    public let code: Int
    public let reason: String

    public init(code: Int, reason: String = "") {
        self.code = code
        self.reason = reason
    }
}

/// The text frames of a WebSocket, so that tests have no network and the connection's logic does not depend on `URLSession`.
public protocol FrameChannel: Sendable {
    /// Connects (the upgrade carries the `Authorization` header). Throws when it cannot.
    func open(url: URL, authorization: String?) async throws
    func send(_ text: String) async throws
    /// The next text frame; throws `ChannelClosed` when the connection ends.
    func receive() async throws -> String
    func close(code: Int, reason: String) async
}

/// `URLSessionWebSocketTask` as a `FrameChannel`.
public final class URLSessionWebSocketChannel: FrameChannel, @unchecked Sendable {
    private let lock = NSLock()
    private let session = URLSession(configuration: .ephemeral)
    private var task: URLSessionWebSocketTask?

    public init() {}

    public func open(url: URL, authorization: String?) async throws {
        var request = URLRequest(url: url, timeoutInterval: 15)
        if let authorization { request.setValue(authorization, forHTTPHeaderField: "Authorization") }
        let task = session.webSocketTask(with: request)
        lock.withLock { self.task = task }
        task.resume()
    }

    public func send(_ text: String) async throws {
        guard let task = lock.withLock({ self.task }) else { throw ChannelClosed(code: 1006, reason: "not open") }
        do {
            try await task.send(.string(text))
        } catch {
            throw closed(task, error)
        }
    }

    public func receive() async throws -> String {
        guard let task = lock.withLock({ self.task }) else { throw ChannelClosed(code: 1006, reason: "not open") }
        do {
            switch try await task.receive() {
            case .string(let text): return text
            case .data(let data): return String(decoding: data, as: UTF8.self)
            @unknown default: return ""
            }
        } catch {
            throw closed(task, error)
        }
    }

    public func close(code: Int, reason: String) async {
        let task = lock.withLock { self.task }
        task?.cancel(with: URLSessionWebSocketTask.CloseCode(rawValue: code) ?? .normalClosure, reason: Data(reason.utf8))
    }

    private func closed(_ task: URLSessionWebSocketTask, _ error: Error) -> ChannelClosed {
        // the server's own close code (4001 unauthorized...) comes with the close frame; none: the network
        let code = task.closeCode.rawValue
        let reason = task.closeReason.map { String(decoding: $0, as: UTF8.self) } ?? error.localizedDescription
        return ChannelClosed(code: code == 0 ? 1006 : code, reason: reason)
    }
}
