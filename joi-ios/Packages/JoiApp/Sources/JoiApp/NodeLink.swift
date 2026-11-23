import Foundation
import JoiClient
import JoiProtocol

/// What the chat needs of the node connection, so that its tests have no WebSocket.
public protocol NodeLink: Sendable {
    var events: AsyncStream<NodeConnection.Event> { get }
    func connect() async throws -> Welcome
    func say(_ text: String, speak: Bool?, reasoning: Bool?, withEvents: Bool?, images: [String]?) async throws -> String
    func send(_ frame: Frame) async throws
    func close() async
}

extension NodeConnection: NodeLink {}

/// Her history, so that the chat's tests have no server.
public protocol HistorySource: Sendable {
    func history(_ joi: String, limit: Int, before: String?) async throws -> ChatHistory
    /// An image for her, streamed from `file`.
    func uploadImage(_ joi: String, file: URL) async throws -> ImageUpload
    /// Allows a task of hers that waits; with `always` the tool from now on.
    func approveTask(_ joi: String, _ task: String, always: Bool) async throws -> TaskInfo
    /// Does not allow a task of hers that waits.
    func denyTask(_ joi: String, _ task: String, reason: String?) async throws -> TaskInfo
}

extension HistorySource {
    // for a source that takes no images (a fake)
    public func uploadImage(_ joi: String, file: URL) async throws -> ImageUpload {
        throw ServerError(status: 0, code: "no_images", message: "This source takes no images.")
    }

    // for a source that has no tasks (a fake)
    public func approveTask(_ joi: String, _ task: String, always: Bool) async throws -> TaskInfo {
        throw ServerError(status: 0, code: "no_tasks", message: "This source has no tasks.")
    }

    public func denyTask(_ joi: String, _ task: String, reason: String?) async throws -> TaskInfo {
        throw ServerError(status: 0, code: "no_tasks", message: "This source has no tasks.")
    }
}

extension JoiClient: HistorySource {}

/// Plays her voice.
@MainActor public protocol SpeechPlaying: AnyObject {
    func enqueue(_ chunk: AudioChunk)
    func stop()
    /// Called when the last piece that was given has been played.
    var onFinished: (@MainActor () -> Void)? { get set }
}
