import Foundation
import JoiClient
import JoiProtocol
import Observation

/// The app's top: which server, the pairing, and once it is paired the chat with her.
@MainActor @Observable
public final class AppModel {
    public enum Phase {
        /// Asks for the server's address.
        case setup
        /// Waiting for something: connecting, loading.
        case working(String)
        /// The user approves this code in the server's UI.
        case pairing(DeviceAuthorization)
        case ready(ChatModel)
    }

    public private(set) var phase: Phase = .setup
    public var serverText: String
    public private(set) var error: String?

    private let defaults: UserDefaults
    private let store: CredentialStore
    private var client: JoiClient?
    private var task: Task<Void, Never>?

    public init(defaults: UserDefaults = .standard, store: CredentialStore = KeychainCredentialStore()) {
        self.defaults = defaults
        self.store = store
        serverText = defaults.string(forKey: Keys.server) ?? ""
    }

    private enum Keys {
        static let server = "joi.server"
        static let nodeId = "joi.nodeId"
    }

    /// The app starts: a server that it signed in to before (its address is kept only then; a server without auth has no credentials) goes
    /// on to the chat, else it asks.
    public func launch() {
        guard !serverText.isEmpty, defaults.string(forKey: Keys.server) != nil else { return }
        connect()
    }

    /// Connects to `serverText`: pairs when the server needs it.
    public func connect() {
        task?.cancel()
        task = Task { await run() }
    }

    public func cancel() {
        task?.cancel()
        phase = .setup
    }

    /// Forgets the pairing and the server's address (the server keeps the device listed until it is revoked there).
    public func signOut() {
        task?.cancel()
        if case .ready(let chat) = phase {
            Task { await chat.stop() }
        }
        store.save(Credentials())
        defaults.removeObject(forKey: Keys.server)
        client = nil
        phase = .setup
    }

    private func run() async {
        error = nil
        guard let server = Self.url(serverText) else {
            error = "That is not an address of a server."
            phase = .setup
            return
        }
        phase = .working("Connecting to \(server.host() ?? server.absoluteString)")
        let client = JoiClient(server: server, store: store, clientName: "Joi on \(Self.deviceName)")
        self.client = client
        do {
            let info = try await client.info()
            if await !client.signedIn, !info.auth.methods.contains(.noneValue) {
                guard info.auth.methods.contains(.oauth) else {
                    throw ServerError(status: 0, code: "no_pairing", message: "This server takes tokens only; the app pairs by the device grant (OAuth).")
                }
                let grant = try await client.startDeviceGrant()
                phase = .pairing(grant)
                try await client.waitForApproval(grant)
            }
            phase = .working("Signing in")
            _ = try await client.me()
            defaults.set(server.absoluteString, forKey: Keys.server)
            phase = .ready(try await chat(client))
        } catch is CancellationError {
            return
        } catch {
            if case .ready = phase { return }
            self.error = ChatModel.describe(error)
            phase = .setup
        }
    }

    private func chat(_ client: JoiClient) async throws -> ChatModel {
        guard let joi = try await client.jois().jois.first else {
            throw ServerError(status: 0, code: "no_joi", message: "The server has no assistant yet.")
        }
        let hello = Hello(protocol: JoiProtocolInfo.nodeVersion, nodeId: nodeId, name: "phone", kind: "phone", software: "joi-ios 0.1",
                          joi: joi.id, parts: [.tools, .audioOut])
        let link = NodeConnection(url: await client.nodeSocket, hello: hello, manifest: Manifest(version: 1, os: "ios", tools: []),
                                  authorizer: { try await client.authorization() })
        let model = ChatModel(joiID: joi.id, joiName: joi.name, history: client, link: link, speech: Self.makeSpeech())
        Task { await model.start() }
        return model
    }

    private var nodeId: String {
        if let id = defaults.string(forKey: Keys.nodeId) { return id }
        let id = UUID().uuidString
        defaults.set(id, forKey: Keys.nodeId)
        return id
    }

    static func makeSpeech() -> SpeechPlaying? {
        SpeechPlayer()
    }

    /// `joi.example.com` is `https://joi.example.com`; an address with its scheme is taken as it is.
    static func url(_ text: String) -> URL? {
        var text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        if !text.contains("://") { text = "https://" + text }
        guard let url = URL(string: text), url.host() != nil, ["http", "https"].contains(url.scheme) else { return nil }
        return url
    }

    static var deviceName: String {
        #if os(iOS)
        return ProcessInfo.processInfo.hostName.replacingOccurrences(of: ".local", with: "")
        #else
        return Host.current().localizedName ?? "this computer"
        #endif
    }
}
