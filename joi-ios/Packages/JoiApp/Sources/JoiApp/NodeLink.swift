import Foundation
import JoiClient
import JoiProtocol

/// What the chat needs of the node connection, so that its tests have no WebSocket.
public protocol NodeLink: Sendable {
    var events: AsyncStream<NodeConnection.Event> { get }
    func connect() async throws -> Welcome
    func say(_ text: String, speak: Bool?, reasoning: Bool?, withEvents: Bool?) async throws -> String
    func send(_ frame: Frame) async throws
    func close() async
}

extension NodeConnection: NodeLink {}

/// Her history, so that the chat's tests have no server.
public protocol HistorySource: Sendable {
    func history(_ joi: String, limit: Int, before: String?) async throws -> ChatHistory
}

extension JoiClient: HistorySource {}

/// Plays her voice.
@MainActor public protocol SpeechPlaying: AnyObject {
    func enqueue(_ chunk: AudioChunk)
    func stop()
    /// Called when the last piece that was given has been played.
    var onFinished: (@MainActor () -> Void)? { get set }
}
