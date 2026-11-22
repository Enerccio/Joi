import Foundation
import Testing
import JoiProtocol
@testable import JoiClient

/// Against a real server: `JOI_TEST_SERVER=http://127.0.0.1:8091 swift test` (a server with `joi.api.no-auth`, as `mvn jetty:run` has, or
/// with `JOI_TEST_TOKEN`). A node that connects makes the server start her turn (a greeting), so use a copy of the data, not the real one.
/// Skipped when `JOI_TEST_SERVER` is not set.
@Suite(.enabled(if: ProcessInfo.processInfo.environment["JOI_TEST_SERVER"] != nil))
struct IntegrationTests {
    static let environment = ProcessInfo.processInfo.environment

    private func client() -> JoiClient {
        let credentials = Self.environment["JOI_TEST_TOKEN"].map { Credentials(token: $0) } ?? Credentials()
        return JoiClient(server: URL(string: Self.environment["JOI_TEST_SERVER"]!)!, store: MemoryCredentialStore(credentials))
    }

    @Test func theServerAnswersTheCallsAndTheAnswersDecode() async throws {
        let client = client()
        let info = try await client.info()
        #expect(info.protocol.hasPrefix("1."))
        let me = try await client.me()
        #expect(!me.login.isEmpty)
        let jois = try await client.jois()
        let first = try #require(jois.jois.first, "the server has no assistant")
        let joi = try await client.joi(first.id)
        #expect(joi.id == first.id)
        _ = try await client.history(first.id, limit: 5)
        _ = try await client.body(first.id)
        _ = try await client.tasks(first.id)
    }

    @Test func pairingAsksForACodeAndItIsPendingUntilTheUserApproves() async throws {
        let client = client()
        let grant = try await client.startDeviceGrant()
        #expect(!grant.userCode.isEmpty && grant.expiresIn > 0)
        #expect(grant.verificationUriComplete.contains(grant.userCode))
        #expect(try await client.pollDeviceGrant(grant.deviceCode) == false)
        #expect(await !client.signedIn)
    }

    @Test func aNodeIsWelcomedAndTheServerTalksToIt() async throws {
        let client = client()
        let joi = try #require(try await client.jois().jois.first)
        let hello = Hello(protocol: JoiProtocolInfo.nodeVersion, nodeId: UUID().uuidString, name: "ios-test", kind: "phone",
                          software: "joi-ios tests", joi: joi.id, parts: [.tools])
        let tool = ToolDefinition(name: "ping_phone", description: "Tells that the phone is there", parameters: ["type": .string("object")], tier: .allow)
        let manifest = Manifest(version: 1, os: "ios", tools: [tool])
        let connection = NodeConnection(url: await client.nodeSocket, hello: hello, manifest: manifest,
                                        authorizer: { try await client.authorization() })
        let log = EventLog(connection.events)
        let welcome = try await connection.connect()
        #expect(welcome.joi.id == joi.id)
        #expect(welcome.heartbeatSeconds > 0)
        // the server starts a turn for a first connection, and sends pings; a few seconds are enough to see it answer
        try await Task.sleep(for: .seconds(3))
        #expect(await connection.isConnected)
        await connection.close()
        #expect(await log.next { if case .closed = $0 { true } else { false } } != nil)
    }
}
