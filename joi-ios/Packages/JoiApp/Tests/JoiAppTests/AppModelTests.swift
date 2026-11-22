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
}
