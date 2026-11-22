import Foundation
import JoiProtocol

/// The HTTP API of a Joi server and the pairing (the OAuth device grant, RFC 8628), as the reference node's `ServerClient` does them.
///
/// Calls need no more than the server's address and where to keep the credentials. An OAuth access token that ends is made again
/// from the refresh token before a call, and after a 401; there is **one refresh at a time** (a second one would use up the refresh
/// token the first one already replaced, which ends the pairing).
public actor JoiClient {
    public let server: URL
    private let store: CredentialStore
    private let transport: HTTPTransport
    private let clientID: String
    private let clientName: String
    private let decoder = JoiJSON.makeDecoder()
    private let encoder = JoiJSON.makeEncoder()
    private var refreshing: Task<Void, Error>?
    private let now: @Sendable () -> Date

    /// - Parameters:
    ///   - server: its address (`https://joi.example.com`), without the path of the API.
    ///   - clientID: what the server lists the device under (the OAuth `client_id`).
    ///   - clientName: what the user sees when they approve it (`Joi on Peter's iPhone`).
    public init(server: URL, store: CredentialStore, transport: HTTPTransport = URLSessionTransport(), clientID: String = "joi-ios",
                clientName: String = "Joi iOS", now: @escaping @Sendable () -> Date = { Date() }) {
        self.server = server
        self.store = store
        self.transport = transport
        self.clientID = clientID
        self.clientName = clientName
        self.now = now
    }

    /// The node WebSocket's address (`ws://` or `wss://`).
    public var nodeSocket: URL {
        var components = URLComponents(url: server, resolvingAgainstBaseURL: false)!
        components.scheme = components.scheme == "https" ? "wss" : "ws"
        components.path = trimmedPath + JoiProtocolInfo.node
        return components.url!
    }

    // MARK: the calls

    /// What the server is and how to authenticate (needs no credentials).
    public func info() async throws -> ServerInfo {
        try await get(JoiProtocolInfo.api, authorized: false)
    }

    public func me() async throws -> Me {
        try await get(JoiProtocolInfo.api + "/me")
    }

    public func jois() async throws -> JoiList {
        try await get(JoiProtocolInfo.api + "/jois")
    }

    public func joi(_ id: String) async throws -> JoiInfo {
        try await get(JoiProtocolInfo.api + "/jois/" + escaped(id))
    }

    /// Her latest `limit` exchanges, oldest first; `before` (an id of the answer before) pages back.
    public func history(_ joi: String, limit: Int = 50, before: String? = nil) async throws -> ChatHistory {
        var query = "?limit=\(limit)"
        if let before { query += "&before=" + escaped(before) }
        return try await get(JoiProtocolInfo.api + "/jois/" + escaped(joi) + "/chat" + query)
    }

    /// The body she is in, nil for none.
    public func body(_ joi: String) async throws -> BodyInfo? {
        do {
            return try await get(JoiProtocolInfo.api + "/jois/" + escaped(joi) + "/body")
        } catch let error as ServerError where error.status == 404 && error.code == "no_body" {
            return nil
        }
    }

    /// One turn over HTTP, answered whole (a node uses the WebSocket).
    public func chat(_ joi: String, _ request: ChatRequest) async throws -> ChatReply {
        try await call("POST", JoiProtocolInfo.api + "/jois/" + escaped(joi) + "/chat", body: request)
    }

    /// Her tasks, the newest first; `status` (`waiting`...) limits them.
    public func tasks(_ joi: String, status: String? = nil) async throws -> TaskList {
        let query = status.map { "?status=" + escaped($0) } ?? ""
        return try await get(JoiProtocolInfo.api + "/jois/" + escaped(joi) + "/tasks" + query)
    }

    /// Allows a task of hers that waits; with `always` the tool from now on. A task that was answered already is a 409 (`conflict`).
    public func approveTask(_ joi: String, _ task: String, always: Bool = false) async throws -> TaskInfo {
        try await call("POST", JoiProtocolInfo.api + "/jois/" + escaped(joi) + "/tasks/" + escaped(task) + "/approve",
                       body: always ? TaskApproval(always: true) : nil)
    }

    /// Does not allow a task of hers that waits; she is told, with the reason when there is one.
    public func denyTask(_ joi: String, _ task: String, reason: String? = nil) async throws -> TaskInfo {
        try await call("POST", JoiProtocolInfo.api + "/jois/" + escaped(joi) + "/tasks/" + escaped(task) + "/deny",
                       body: reason.map { TaskDenial(reason: $0) })
    }

    // MARK: credentials

    /// The `Authorization` header now, after making an access token that ends again; nil for none.
    public func authorization() async throws -> String? {
        let credentials = store.load()
        if credentials.token == nil, credentials.refreshToken != nil,
           credentials.accessToken == nil || now() > (credentials.accessExpires ?? .distantPast).addingTimeInterval(-60) {
            try await refresh()
        }
        return store.load().authorization
    }

    /// Whether the app has credentials (it is paired, or has a token).
    public var signedIn: Bool {
        store.load().authorization != nil
    }

    /// Forgets the credentials (the user signs out; the server keeps the device listed until they revoke it).
    public func signOut() {
        store.save(Credentials())
    }

    // MARK: pairing

    /// Asks for a code the user approves in the server's UI (User Settings → Access → Connect a device, or the link it shows).
    public func startDeviceGrant() async throws -> DeviceAuthorization {
        let (data, response) = try await post(JoiProtocolInfo.deviceAuthorization, ["client_id": clientID, "client_name": clientName])
        guard response.statusCode == 200 else { throw oauthError(data, response) }
        return try decoder.decode(DeviceAuthorization.self, from: data)
    }

    /// Asks once whether the user approved the code: true when the tokens are kept, false while it waits (`authorization_pending`,
    /// `slow_down`); a refused or expired code throws.
    public func pollDeviceGrant(_ deviceCode: String) async throws -> Bool {
        let (data, response) = try await post(JoiProtocolInfo.token, [
            "grant_type": JoiProtocolInfo.deviceCodeGrant, "device_code": deviceCode, "client_id": clientID,
        ])
        if response.statusCode == 200 {
            keep(try decoder.decode(TokenResponse.self, from: data))
            return true
        }
        let error = oauthError(data, response)
        if error.code == "authorization_pending" || error.code == "slow_down" {
            return false
        }
        throw error
    }

    /// Polls as often as the server wants (`interval`, longer after `slow_down`) until the user approves or the code ends. Cancel the task to stop.
    public func waitForApproval(_ grant: DeviceAuthorization) async throws {
        var interval = max(1, grant.interval)
        let end = now().addingTimeInterval(TimeInterval(grant.expiresIn))
        while now() < end {
            try await Task.sleep(for: .seconds(interval))
            do {
                if try await pollDeviceGrant(grant.deviceCode) { return }
            } catch let error as ServerError where error.code == "slow_down" {
                interval += 5
            }
        }
        throw ServerError(status: 400, code: "expired_token", message: "The code was not approved in time")
    }

    // MARK: the machinery

    private var trimmedPath: String {
        var path = server.path
        while path.hasSuffix("/") { path.removeLast() }
        return path
    }

    private func url(_ path: String) -> URL {
        // the server's own path (a reverse proxy under /joi) comes first
        URL(string: server.absoluteString.trimmingSlashes + path)!
    }

    private func escaped(_ part: String) -> String {
        part.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/?&=#+"))) ?? part
    }

    private func get<T: Decodable>(_ path: String, authorized: Bool = true) async throws -> T {
        try await call("GET", path, body: EmptyBody?.none, authorized: authorized)
    }

    private func call<T: Decodable, B: Encodable>(_ method: String, _ path: String, body: B?, authorized: Bool = true) async throws -> T {
        // an access token that ended is made again before the call, not after its 401
        let used = authorized ? try await authorization() : nil
        var (data, response) = try await exchange(method, path, body: body, authorization: used)
        if response.statusCode == 401, authorized, try await refreshAfter(used) {
            (data, response) = try await exchange(method, path, body: body, authorization: store.load().authorization)
        }
        guard (200..<300).contains(response.statusCode) else { throw apiError(data, response) }
        return try decoder.decode(T.self, from: data)
    }

    private func exchange<B: Encodable>(_ method: String, _ path: String, body: B?, authorization: String?) async throws -> (Data, HTTPURLResponse) {
        var request = URLRequest(url: url(path), timeoutInterval: 30)
        request.httpMethod = method
        if let authorization { request.setValue(authorization, forHTTPHeaderField: "Authorization") }
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try encoder.encode(body)
        }
        return try await transport.send(request)
    }

    private func post(_ path: String, _ form: [String: String]) async throws -> (Data, HTTPURLResponse) {
        var request = URLRequest(url: url(path), timeoutInterval: 30)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let unreserved = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))
        request.httpBody = Data(form.sorted { $0.key < $1.key }.map {
            $0.key.addingPercentEncoding(withAllowedCharacters: unreserved)! + "=" + $0.value.addingPercentEncoding(withAllowedCharacters: unreserved)!
        }.joined(separator: "&").utf8)
        return try await transport.send(request)
    }

    /// After a 401 to a call made with `used`: refreshes, unless another call did meanwhile (the credentials are no longer `used`).
    /// Returns whether to make the call again.
    private func refreshAfter(_ used: String?) async throws -> Bool {
        guard store.load().refreshToken != nil else { return false }
        if store.load().authorization == used {
            try await refresh()
        }
        return true
    }

    /// One refresh at a time: whoever comes while one runs waits for that one.
    private func refresh() async throws {
        if let running = refreshing {
            return try await running.value
        }
        let task = Task { try await self.refreshNow() }
        refreshing = task
        defer { refreshing = nil }
        try await task.value
    }

    private func refreshNow() async throws {
        var credentials = store.load()
        guard let refreshToken = credentials.refreshToken else { return }
        let (data, response) = try await post(JoiProtocolInfo.token, [
            "grant_type": "refresh_token", "refresh_token": refreshToken, "client_id": clientID,
        ])
        guard response.statusCode == 200 else {
            // the pair is no longer good: the user pairs the app again
            credentials.accessToken = nil
            credentials.refreshToken = nil
            credentials.accessExpires = nil
            store.save(credentials)
            throw oauthError(data, response)
        }
        keep(try decoder.decode(TokenResponse.self, from: data))
    }

    private func keep(_ tokens: TokenResponse) {
        store.save(Credentials(accessToken: tokens.accessToken, refreshToken: tokens.refreshToken,
                               accessExpires: now().addingTimeInterval(TimeInterval(tokens.expiresIn))))
    }

    private func apiError(_ data: Data, _ response: HTTPURLResponse) -> ServerError {
        if let error = try? decoder.decode(ApiError.self, from: data) {
            return ServerError(status: response.statusCode, code: error.code, message: error.message)
        }
        return ServerError(status: response.statusCode, code: "http_\(response.statusCode)", message: "HTTP \(response.statusCode)")
    }

    private func oauthError(_ data: Data, _ response: HTTPURLResponse) -> ServerError {
        if let error = try? decoder.decode(OAuthError.self, from: data) {
            return ServerError(status: response.statusCode, code: error.error, message: error.errorDescription ?? "HTTP \(response.statusCode)")
        }
        return ServerError(status: response.statusCode, code: "http_\(response.statusCode)", message: "HTTP \(response.statusCode)")
    }
}

private struct EmptyBody: Encodable {}

private extension String {
    var trimmingSlashes: String {
        var text = self
        while text.hasSuffix("/") { text.removeLast() }
        return text
    }
}
