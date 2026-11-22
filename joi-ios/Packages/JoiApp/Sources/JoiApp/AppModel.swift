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
        case ready(ChatModel, AvatarModel)
    }

    public private(set) var phase: Phase = .setup
    public var serverText: String
    public private(set) var error: String?

    private let defaults: UserDefaults
    private let store: CredentialStore
    private let transport: HTTPTransport
    private var client: JoiClient?
    private var task: Task<Void, Never>?
    /// The pairing offer of a QR code scanned with the camera, for the next `run`.
    private var offer: String?

    public init(defaults: UserDefaults = .standard, store: CredentialStore = KeychainCredentialStore(),
                transport: HTTPTransport = URLSessionTransport()) {
        self.defaults = defaults
        self.store = store
        self.transport = transport
        serverText = defaults.string(forKey: Keys.server) ?? ""
    }

    private enum Keys {
        static let server = "joi.server"
        /// The server the credentials of the store were made by (they are sent to no other).
        static let credentialsServer = "joi.credentialsServer"
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
        offer = nil
        task = Task { await run() }
    }

    /// The app was opened by a link: `joi://pair?server=...&offer=...` is the QR code of the server's *Pair a phone* screen. The server
    /// is taken as the user's (a pairing they asked for by scanning), and the server's screen approves the phone: no code to type.
    public func open(_ url: URL) {
        guard let pairing = Self.pairing(from: url) else { return }
        task?.cancel()
        if case .ready(let chat, _) = phase {
            Task { await chat.stop() }
        }
        // a different server (or the same, paired again) starts from nothing
        store.save(Credentials())
        defaults.removeObject(forKey: Keys.server)
        serverText = pairing.server.absoluteString
        offer = pairing.offer
        task = Task { await run() }
    }

    /// The server and the offer of a pairing link, nil when it is not one.
    static func pairing(from url: URL) -> (server: URL, offer: String)? {
        guard url.scheme?.lowercased() == "joi", url.host() == "pair",
              let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
              let server = items.first(where: { $0.name == "server" })?.value.flatMap(Self.url),
              let offer = items.first(where: { $0.name == "offer" })?.value,
              !offer.isEmpty, offer.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }) else { return nil }
        return (server, offer)
    }

    public func cancel() {
        task?.cancel()
        phase = .setup
    }

    /// Forgets the pairing and the server's address (the server keeps the device listed until it is revoked there).
    public func signOut() {
        task?.cancel()
        if case .ready(let chat, _) = phase {
            Task { await chat.stop() }
        }
        store.save(Credentials())
        defaults.removeObject(forKey: Keys.server)
        defaults.removeObject(forKey: Keys.credentialsServer)
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
        // the credentials are those of one server: another one (typed after an error, or in place of the one paired) would get its token
        let owner = defaults.string(forKey: Keys.credentialsServer) ?? defaults.string(forKey: Keys.server)
        if !Self.credentials(of: owner, serve: server) {
            store.save(Credentials())
            defaults.removeObject(forKey: Keys.credentialsServer)
        }
        let client = JoiClient(server: server, store: store, transport: transport, clientName: "Joi on \(Self.deviceName)")
        self.client = client
        do {
            let info = try await client.info()
            if await !client.signedIn, !info.auth.methods.contains(.noneValue) {
                guard info.auth.methods.contains(.oauth) else {
                    throw ServerError(status: 0, code: "no_pairing", message: "This server takes tokens only; the app pairs by the device grant (OAuth).")
                }
                // an offer is used once: the server's screen approves the phone by it, and no code is shown to type
                let offer = self.offer
                self.offer = nil
                let grant = try await client.startDeviceGrant(offer: offer)
                phase = offer == nil ? .pairing(grant) : .working("Pairing with \(server.host() ?? server.absoluteString)")
                try await client.waitForApproval(grant, askAtOnce: offer != nil)
                defaults.set(server.absoluteString, forKey: Keys.credentialsServer)
            }
            phase = .working("Signing in")
            _ = try await client.me()
            defaults.set(server.absoluteString, forKey: Keys.server)
            let (chat, avatar) = try await chat(client)
            phase = .ready(chat, avatar)
        } catch is CancellationError {
            return
        } catch {
            if case .ready = phase { return }
            self.error = ChatModel.describe(error)
            phase = .setup
        }
    }

    private func chat(_ client: JoiClient) async throws -> (ChatModel, AvatarModel) {
        guard let joi = try await client.jois().jois.first else {
            throw ServerError(status: 0, code: "no_joi", message: "The server has no assistant yet.")
        }
        // the animations chosen for her: what her body does while she sleeps, waits and speaks
        let roles = try? await client.joi(joi.id).animations
        let avatar = AvatarModel(page: Self.rendererPage)
        let hello = Hello(protocol: JoiProtocolInfo.nodeVersion, nodeId: nodeId, name: "phone", kind: "phone", software: "joi-ios 0.1",
                          joi: joi.id, parts: [.tools, .audioOut])
        let link = NodeConnection(url: await client.nodeSocket, hello: hello, manifest: Manifest(version: 1, os: "ios", tools: []),
                                  authorizer: { try await client.authorization() })
        let speech = Self.makeSpeech()
        if let player = speech as? SpeechPlayer {
            // her mouth follows her voice: the page reads each piece and is told when it plays
            let bridge = avatar.bridge
            player.onQueued = { number, chunk in bridge.speech(number, chunk) }
            player.onStarted = { number in bridge.speechStarted(number) }
            player.onStopped = { bridge.speechStopped() }
        }
        let model = ChatModel(joiID: joi.id, joiName: joi.name, history: client, link: link, speech: speech,
                              player: avatar.bridge, roles: roles)
        model.onServerEvent = { [weak model, weak avatar] name in
            // the server tells that her body or her settings changed
            switch name {
            case "body":
                avatar?.load(from: client, joi: joi.id)
            case "joi":
                Task { model?.setRoles(try? await client.joi(joi.id).animations) }
            default:
                break
            }
        }
        model.onPartKept = { [weak avatar] in avatar?.load(from: client, joi: joi.id) }
        avatar.load(from: client, joi: joi.id)
        Task { await model.start() }
        return (model, avatar)
    }

    /// The renderer page (`joi-node/src/main/renderer`, built into the app by Maven as the folder `Renderer`).
    static var rendererPage: URL? {
        Bundle.main.resourceURL?.appendingPathComponent("Renderer")
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

    /// Whether credentials made by the server `owner` (its address as kept, nil for not known) may be sent to `server`.
    static func credentials(of owner: String?, serve server: URL) -> Bool {
        owner == server.absoluteString
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
