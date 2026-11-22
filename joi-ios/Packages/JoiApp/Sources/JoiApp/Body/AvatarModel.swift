import Foundation
import JoiClient
import JoiProtocol
import Observation

/// What loading her body needs of the server, so that its tests have none.
public protocol BodySource: Sendable {
    func body(_ joi: String) async throws -> BodyInfo?
    func download(_ file: Download, into cache: URL) async throws -> URL
}

extension JoiClient: BodySource {}

/// Her body in the app: loaded from the server (her `.joim` and `.joia`, downloaded once and kept by their hashes), unpacked, and shown by
/// the renderer page (`AvatarView`) through the `bridge`.
@MainActor @Observable
public final class AvatarModel {
    public enum Status: Equatable, Sendable {
        case loading(String)
        /// Her body is shown.
        case shown
        /// She has no body (a model was not chosen for her).
        case none
        case failed(String)
    }

    public private(set) var status: Status = .loading("Looking for her body")
    /// Whether the page has drawn her model (it takes a moment after it is shown: the model is decoded in the page).
    public private(set) var drawn = false
    @ObservationIgnored public let bridge = RendererBridge()
    /// Where the renderer page is (`index.html` and its assets): a folder of the app's bundle.
    public let page: URL?
    private let cache: URL
    private var loading: Task<Void, Never>?

    public init(page: URL?, cache: URL = AvatarModel.defaultCache) {
        self.page = page
        self.cache = cache
        bridge.onDrawn = { [weak self] drawn in self?.drawn = drawn }
    }

    public static var defaultCache: URL {
        (FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory)
            .appendingPathComponent("joi-body")
    }

    /// Loads her body, and again when it changed (the server told). One load at a time: a new one ends the one before.
    public func load(from source: BodySource, joi: String) {
        loading?.cancel()
        loading = Task { await run(source, joi) }
    }

    /// For tests: waits for the load.
    func loaded() async {
        await loading?.value
    }

    private func run(_ source: BodySource, _ joi: String) async {
        do {
            guard let info = try await source.body(joi), let joim = info.model?.joim else {
                bridge.show(.none)
                drawn = false
                status = .none
                return
            }
            let cache = self.cache
            status = .loading("Downloading her model (\(Self.size(joim.size)))")
            let joimFile = try await source.download(joim, into: cache)
            var joiaFile: URL?
            if let joia = info.animations.joia {
                status = .loading("Downloading her animations (\(Self.size(joia.size)))")
                joiaFile = try await source.download(joia, into: cache)
            }
            try Task.checkCancellation()
            status = .loading("Unpacking")
            let joiaURL = joiaFile
            let files = try await Task.detached { try BodyFiles.unpack(joim: joimFile, joia: joiaURL, into: cache) }.value
            try Task.checkCancellation()
            // what she wore before is no longer needed
            let keep = Set([joimFile, joiaFile].compactMap { $0 }.flatMap {
                [$0.lastPathComponent, $0.deletingPathExtension().lastPathComponent + "-" + $0.pathExtension]
            })
            BodyFiles.clean(cache, keeping: keep)
            bridge.show(files)
            bridge.setToggles(info.toggles ?? [])
            status = .shown
        } catch is CancellationError {
            return
        } catch {
            status = .failed(ChatModel.describe(error))
        }
    }

    static func size(_ bytes: Int) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
    }
}
