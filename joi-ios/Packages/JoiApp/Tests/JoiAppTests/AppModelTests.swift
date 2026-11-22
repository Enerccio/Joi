import Foundation
import Testing
import JoiClient
@testable import JoiApp

@MainActor @Suite struct AppModelTests {

    @Test func credentialsGoOnlyToTheServerThatMadeThem() {
        let paired = URL(string: "https://joi.example.com")!
        #expect(AppModel.credentials(of: "https://joi.example.com", serve: paired))
        #expect(!AppModel.credentials(of: "https://other.example.com", serve: paired))
        #expect(!AppModel.credentials(of: nil, serve: paired))
    }

    @Test func anotherServerStartsWithoutTheCredentialsOfThePairedOne() async throws {
        let suite = "joi-app-model-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set("https://joi.example.com", forKey: "joi.server")
        defaults.set("https://joi.example.com", forKey: "joi.credentialsServer")
        let store = MemoryCredentialStore(Credentials(accessToken: "joia_secret", refreshToken: "joir_secret",
                                                      accessExpires: Date().addingTimeInterval(3600)))
        let model = AppModel(defaults: defaults, store: store)
        // a port where nothing answers: the credentials are dropped before anything is sent
        model.serverText = "http://127.0.0.1:9"
        model.connect()
        for _ in 0..<50 where store.load().authorization != nil {
            try await Task.sleep(for: .milliseconds(20))
        }
        #expect(store.load().authorization == nil)
        #expect(defaults.string(forKey: "joi.credentialsServer") == nil)
        model.cancel()
    }

    @Test func theSameServerKeepsItsCredentials() async throws {
        let suite = "joi-app-model-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set("http://127.0.0.1:9", forKey: "joi.server")
        let store = MemoryCredentialStore(Credentials(token: "joi_secret"))
        let model = AppModel(defaults: defaults, store: store)
        model.serverText = "http://127.0.0.1:9"
        model.connect()
        try await Task.sleep(for: .milliseconds(200))
        #expect(store.load().token == "joi_secret")
        model.cancel()
    }

    @Test func aPairingLinkHasTheServerAndTheOffer() throws {
        let link = try #require(URL(string: "joi://pair?server=http%3A%2F%2F192.168.1.5%3A8080&offer=4F8fd2rhGAXnNlFtHbanQkk2AJ0"))
        let pairing = try #require(AppModel.pairing(from: link))
        #expect(pairing.server.absoluteString == "http://192.168.1.5:8080")
        #expect(pairing.offer == "4F8fd2rhGAXnNlFtHbanQkk2AJ0")
        // not a link of the app, or not one to trust
        #expect(AppModel.pairing(from: URL(string: "https://pair?server=http%3A%2F%2Fa&offer=x")!) == nil)
        #expect(AppModel.pairing(from: URL(string: "joi://other?server=http%3A%2F%2Fa&offer=x")!) == nil)
        #expect(AppModel.pairing(from: URL(string: "joi://pair?server=ftp%3A%2F%2Fa&offer=x")!) == nil)
        #expect(AppModel.pairing(from: URL(string: "joi://pair?server=http%3A%2F%2Fa&offer=a%20b")!) == nil)
        #expect(AppModel.pairing(from: URL(string: "joi://pair?server=http%3A%2F%2Fa")!) == nil)
    }

    @Test func theOfferOfALinkIsGivenToTheServerAndNoCodeIsShown() async throws {
        let suite = "joi-app-model-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let http = RecordingHTTP()
        let model = AppModel(defaults: defaults, store: MemoryCredentialStore(), transport: http)
        model.open(try #require(URL(string: "joi://pair?server=http%3A%2F%2Fphone.test&offer=off-er_1")))
        for _ in 0..<100 where !http.bodies.contains(where: { $0.contains("pairing_offer") }) {
            try await Task.sleep(for: .milliseconds(20))
        }
        #expect(http.bodies.contains { $0.contains("pairing_offer=off-er_1") })
        if case .working(let text) = model.phase {
            #expect(text.contains("Pairing"))
        } else {
            Issue.record("a pairing by link waits for the server's screen, it shows no code: \(model.phase) \(model.error ?? "")")
        }
        model.cancel()
    }
}

/// A server that wants the device grant and never approves.
private final class RecordingHTTP: HTTPTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var recorded: [String] = []
    var bodies: [String] { lock.withLock { recorded } }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let path = request.url?.path ?? ""
        lock.withLock { recorded.append(String(decoding: request.httpBody ?? Data(), as: UTF8.self)) }
        let (status, json): (Int, String)
        switch path {
        case "/api/v1":
            (status, json) = (200, #"{"protocol":"1.9","server":"joi-server","version":"0.1.0","auth":{"methods":["oauth"],"oauth":{"deviceAuthorization":"http://phone.test/oauth/device_authorization","token":"http://phone.test/oauth/token","verification":"http://phone.test/?device"}}}"#)
        case "/oauth/device_authorization":
            (status, json) = (200, #"{"device_code":"dc","user_code":"WDJB-MJHT","verification_uri":"http://phone.test/?device","verification_uri_complete":"http://phone.test/?device=WDJB-MJHT","expires_in":600,"interval":60}"#)
        default:
            (status, json) = (400, #"{"error":"authorization_pending"}"#)
        }
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
        return (Data(json.utf8), response)
    }
}
