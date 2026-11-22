import Foundation
import Testing
import JoiProtocol
@testable import JoiClient

@Suite struct JoiClientTests {
    let server = URL(string: "https://joi.example.com")!

    private func tokens(_ access: String, _ refresh: String) -> String {
        #"{"access_token":"\#(access)","token_type":"Bearer","expires_in":3600,"refresh_token":"\#(refresh)"}"#
    }

    @Test func aCallCarriesTheTokenAndTheAnswerDecodes() async throws {
        let http = FakeHTTP { _ in (200, #"{"login":"peter","name":"Peter","method":"token"}"#) }
        let client = JoiClient(server: server, store: MemoryCredentialStore(Credentials(token: "joi_abc")), transport: http)
        let me = try await client.me()
        #expect(me.login == "peter")
        #expect(me.method == .token)
        #expect(http.recorded.first?.path == "/api/v1/me")
        #expect(http.recorded.first?.authorization == "Bearer joi_abc")
    }

    @Test func infoNeedsNoCredentials() async throws {
        let http = FakeHTTP { _ in (200, #"{"protocol":"1.8","server":"joi-server","version":"0.1.0","auth":{"methods":["none"],"oauth":null}}"#) }
        let client = JoiClient(server: server, store: MemoryCredentialStore(Credentials(token: "joi_abc")), transport: http)
        let info = try await client.info()
        #expect(info.auth.methods == [.noneValue])
        #expect(http.recorded.first?.authorization == nil)
    }

    @Test func theServersOwnPathComesFirst() async throws {
        let http = FakeHTTP { _ in (200, #"{"jois":[]}"#) }
        let client = JoiClient(server: URL(string: "https://example.com/joi/")!, store: MemoryCredentialStore(Credentials(token: "t")), transport: http)
        _ = try await client.jois()
        #expect(http.recorded.first?.path == "/joi/api/v1/jois")
        #expect(await client.nodeSocket.absoluteString == "wss://example.com/joi/api/v1/node")
    }

    @Test func anErrorIsTheServersError() async throws {
        let http = FakeHTTP { call in
            call.path.hasSuffix("/body") ? (404, #"{"code":"no_body","message":"She has no body"}"#) : (500, "<html>")
        }
        let client = JoiClient(server: server, store: MemoryCredentialStore(Credentials(token: "t")), transport: http)
        #expect(try await client.body("j1") == nil)
        await #expect(throws: ServerError(status: 500, code: "http_500", message: "HTTP 500")) { try await client.joi("j1") }
    }

    @Test func aPathPartIsEscaped() async throws {
        let http = FakeHTTP { _ in (200, #"{"messages":[],"more":false}"#) }
        let client = JoiClient(server: server, store: MemoryCredentialStore(Credentials(token: "t")), transport: http)
        _ = try await client.history("a/b?c", limit: 5, before: "m 1")
        #expect(http.recorded.first?.path == "/api/v1/jois/a%2Fb%3Fc/chat?limit=5&before=m%201")
    }

    @Test func after401TheRefreshTokenMakesANewPairAndTheCallIsMadeAgain() async throws {
        let store = MemoryCredentialStore(Credentials(accessToken: "old", refreshToken: "r1", accessExpires: Date().addingTimeInterval(3600)))
        let http = FakeHTTP { call in
            if call.path == "/oauth/token" { return (200, #"{"access_token":"new","token_type":"Bearer","expires_in":3600,"refresh_token":"r2"}"#) }
            return call.authorization == "Bearer new" ? (200, #"{"jois":[]}"#) : (401, #"{"code":"unauthorized","message":"no"}"#)
        }
        let client = JoiClient(server: server, store: store, transport: http)
        _ = try await client.jois()
        #expect(http.recorded.map(\.path) == ["/api/v1/jois", "/oauth/token", "/api/v1/jois"])
        #expect(http.recorded[1].body == "client_id=joi-ios&grant_type=refresh_token&refresh_token=r1")
        #expect(store.load().refreshToken == "r2")
        #expect(store.load().authorization == "Bearer new")
    }

    @Test func anAccessTokenThatEndedIsMadeAgainBeforeTheCall() async throws {
        let store = MemoryCredentialStore(Credentials(accessToken: "old", refreshToken: "r1", accessExpires: Date().addingTimeInterval(10)))
        let http = FakeHTTP { call in
            call.path == "/oauth/token" ? (200, self.tokens("new", "r2")) : (200, #"{"jois":[]}"#)
        }
        let client = JoiClient(server: server, store: store, transport: http)
        _ = try await client.jois()
        #expect(http.recorded.map(\.path) == ["/oauth/token", "/api/v1/jois"])
        #expect(http.recorded[1].authorization == "Bearer new")
    }

    @Test func callsMadeTogetherRefreshOnce() async throws {
        let store = MemoryCredentialStore(Credentials(accessToken: "old", refreshToken: "r1", accessExpires: Date().addingTimeInterval(3600)))
        let http = FakeHTTP { call in
            if call.path == "/oauth/token" { return (200, self.tokens("new", "r2")) }
            return call.authorization == "Bearer new" ? (200, #"{"jois":[]}"#) : (401, #"{"code":"unauthorized","message":"no"}"#)
        }
        let client = JoiClient(server: server, store: store, transport: http)
        try await withThrowingTaskGroup(of: Void.self) { group in
            for _ in 0..<4 { group.addTask { _ = try await client.jois() } }
            try await group.waitForAll()
        }
        // a second refresh would have used the refresh token the first one replaced
        #expect(http.recorded.filter { $0.path == "/oauth/token" }.count == 1)
    }

    @Test func aRefreshThatFailsEndsThePairing() async throws {
        let store = MemoryCredentialStore(Credentials(accessToken: "old", refreshToken: "r1", accessExpires: Date().addingTimeInterval(3600)))
        let http = FakeHTTP { call in
            call.path == "/oauth/token" ? (400, #"{"error":"invalid_grant","error_description":"Used"}"#) : (401, #"{"code":"unauthorized","message":"no"}"#)
        }
        let client = JoiClient(server: server, store: store, transport: http)
        await #expect(throws: ServerError(status: 400, code: "invalid_grant", message: "Used")) { try await client.jois() }
        #expect(store.load().authorization == nil)
        #expect(await !client.signedIn)
    }

    @Test func pairingAsksForACodeAndPollsUntilTheUserApproves() async throws {
        let store = MemoryCredentialStore()
        let polls = Counter()
        let http = FakeHTTP { call in
            if call.path == "/oauth/device_authorization" {
                return (200, #"{"device_code":"dc","user_code":"WDJB-MJHT","verification_uri":"https://h/?device","verification_uri_complete":"https://h/?device=WDJB-MJHT","expires_in":600,"interval":1}"#)
            }
            return polls.next() < 2 ? (400, #"{"error":"authorization_pending"}"#) : (200, self.tokens("a1", "r1"))
        }
        let client = JoiClient(server: server, store: store, transport: http, clientName: "Joi on a phone")
        let grant = try await client.startDeviceGrant()
        #expect(grant.userCode == "WDJB-MJHT")
        #expect(http.recorded[0].body == "client_id=joi-ios&client_name=Joi%20on%20a%20phone")
        #expect(try await client.pollDeviceGrant(grant.deviceCode) == false)
        #expect(try await client.pollDeviceGrant(grant.deviceCode) == false)
        #expect(try await client.pollDeviceGrant(grant.deviceCode) == true)
        #expect(http.recorded[1].body.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"))
        #expect(store.load().refreshToken == "r1")
        #expect(await client.signedIn)
    }

    @Test func aPairingOfferIsGivenAndAskedAboutAtOnce() async throws {
        let store = MemoryCredentialStore()
        let http = FakeHTTP { call in
            call.path == "/oauth/device_authorization"
                ? (200, #"{"device_code":"dc","user_code":"WDJB-MJHT","verification_uri":"https://h/?device","verification_uri_complete":"https://h/?device=WDJB-MJHT","expires_in":600,"interval":60}"#)
                : (200, self.tokens("a1", "r1"))
        }
        let client = JoiClient(server: server, store: store, transport: http)
        let grant = try await client.startDeviceGrant(offer: "off-er_1")
        #expect(http.recorded[0].body == "client_id=joi-ios&client_name=Joi%20iOS&pairing_offer=off-er_1")
        // the interval is a minute, and the first ask does not wait for it
        try await client.waitForApproval(grant, askAtOnce: true)
        #expect(store.load().refreshToken == "r1")
    }

    @Test func tokensThatCouldNotBeKeptAreNoPairing() async throws {
        final class Refusing: CredentialStore, @unchecked Sendable {
            func load() -> Credentials { Credentials() }
            func save(_ credentials: Credentials) {}
            var saveFailure: String? { "the Keychain refused the credentials (status -34018)" }
        }
        let http = FakeHTTP { _ in (200, self.tokens("a1", "r1")) }
        let client = JoiClient(server: server, store: Refusing(), transport: http)
        await #expect(throws: ServerError.self) { try await client.pollDeviceGrant("dc") }
    }

    @Test func aRefusedCodeThrows() async throws {
        let http = FakeHTTP { _ in (400, #"{"error":"access_denied","error_description":"The user said no"}"#) }
        let client = JoiClient(server: server, store: MemoryCredentialStore(), transport: http)
        await #expect(throws: ServerError(status: 400, code: "access_denied", message: "The user said no")) { try await client.pollDeviceGrant("dc") }
    }

    @Test func approvingATaskPostsItsAnswer() async throws {
        let task = #"{"id":"t1","title":"Weather","tool":"web_search","status":"done","question":"?","created":"2026-11-20T10:00:00Z"}"#
        let http = FakeHTTP { _ in (200, task) }
        let client = JoiClient(server: server, store: MemoryCredentialStore(Credentials(token: "t")), transport: http)
        _ = try await client.approveTask("j1", "t1", always: true)
        _ = try await client.denyTask("j1", "t1", reason: "Not now")
        #expect(http.recorded[0].path == "/api/v1/jois/j1/tasks/t1/approve")
        #expect(http.recorded[0].body == #"{"always":true}"#)
        #expect(http.recorded[1].path == "/api/v1/jois/j1/tasks/t1/deny")
        #expect(http.recorded[1].body == #"{"reason":"Not now"}"#)
    }
}

final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var value = 0
    func next() -> Int { lock.withLock { defer { value += 1 }; return value } }
}
