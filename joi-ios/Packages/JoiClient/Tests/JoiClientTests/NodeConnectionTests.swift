import Foundation
import Testing
import JoiProtocol
@testable import JoiClient

@Suite struct NodeConnectionTests {
    private func make(_ server: FakeServer, tools: [ToolDefinition] = []) -> NodeConnection {
        var options = NodeConnection.Options()
        options.heartbeatUnit = .milliseconds(10)
        options.retryUnit = .milliseconds(10)
        options.welcomeTimeout = .milliseconds(200)
        let hello = Hello(protocol: "1.8", nodeId: "n1", name: "phone", kind: "phone", software: "joi-ios 0.1", joi: "j1", parts: [.tools])
        return NodeConnection(url: URL(string: "wss://joi.example.com/api/v1/node")!, hello: hello,
                              manifest: Manifest(version: 1, os: "ios", tools: tools), options: options,
                              authorizer: { "Bearer joia_x" }, channel: { server.makeChannel() })
    }

    @Test func connectingSaysHelloThenTheManifest() async throws {
        let server = FakeServer()
        let connection = make(server)
        let welcome = try await connection.connect()
        #expect(welcome.joi.id == "j1")
        let channel = try #require(await server.connection(0))
        let manifest = try #require(await channel.sent("manifest"))
        #expect(channel.sent.map(\.type) == ["hello", "manifest"])
        #expect(channel.sent[0].id == "hello")
        guard case .hello(let hello)? = try? channel.sent[0].typedData() else { Issue.record("no hello"); return }
        #expect(hello.nodeId == "n1" && hello.kind == "phone" && hello.resumed == nil)
        #expect(manifest.data?["os"] == .string("ios"))
        #expect(server.authorizations == ["Bearer joia_x"])
        #expect(await connection.isConnected)
        await connection.close()
    }

    @Test func noWelcomeInTimeIsAFailure() async throws {
        let server = FakeServer()
        server.welcomes = false
        let connection = make(server)
        await #expect(throws: NodeConnectionError.welcomeTimeout) { try await connection.connect() }
        #expect(await !connection.isConnected)
    }

    @Test func aPingIsAnsweredWithAPong() async throws {
        let server = FakeServer()
        let connection = make(server)
        _ = try await connection.connect()
        let channel = try #require(await server.connection(0))
        channel.push(#"{"type":"ping","id":"p9"}"#)
        let pong = try #require(await channel.sent("pong"))
        #expect(pong.ref == "p9")
        await connection.close()
    }

    @Test func whatTheUserSaysIsSentAndHerAnswerComesAsEvents() async throws {
        let server = FakeServer()
        let connection = make(server)
        let log = EventLog(connection.events)
        _ = try await connection.connect()
        let channel = try #require(await server.connection(0))

        let turn = try await connection.say("Hi", speak: false)
        #expect(turn == "t1")
        let say = try #require(await channel.sent("say"))
        #expect(say.id == "t1")
        #expect(say.data?["text"] == .string("Hi") && say.data?["speak"] == .bool(false))

        channel.push(#"{"type":"hologram","data":{"depth":3}}"#) // a type of a newer version is not told
        channel.push(#"{"type":"text","ref":"t1","data":{"delta":"Hel"}}"#)
        channel.push(#"{"type":"done","ref":"t1","data":{"messageId":"m1"}}"#)
        let first = try #require(await log.next { if case .frame = $0 { true } else { false } })
        guard case .frame(let text) = first else { return }
        #expect(text.ref == "t1")
        #expect(text.data == .text(TextDelta(delta: "Hel")))
        let done = await log.next(after: 1) { if case .frame = $0 { true } else { false } }
        guard case .frame(let end)? = done, case .done = end.data else { Issue.record("no done: \(String(describing: done))"); return }
        await connection.close()
    }

    @Test func aCallOfAToolIsAnsweredByTheHandler() async throws {
        let server = FakeServer()
        let connection = make(server)
        await connection.setToolHandler { call in
            ToolResult(callId: call.callId, ok: true, content: "read \(call.arguments["path"] ?? .null)")
        }
        _ = try await connection.connect()
        let channel = try #require(await server.connection(0))
        channel.push(#"{"type":"tool_call","data":{"callId":"c1","tool":"read_file","arguments":{"path":"a.md"},"timeoutSeconds":60}}"#)
        let result = try #require(await channel.sent("tool_result"))
        #expect(result.data?["callId"] == .string("c1") && result.data?["ok"] == .bool(true))
        #expect(result.data?["content"] == .string("read string(\"a.md\")"))
        await connection.close()
    }

    @Test func withoutAHandlerACallFails() async throws {
        let server = FakeServer()
        let connection = make(server)
        _ = try await connection.connect()
        let channel = try #require(await server.connection(0))
        channel.push(#"{"type":"tool_call","data":{"callId":"c1","tool":"x","arguments":{},"timeoutSeconds":60}}"#)
        let result = try #require(await channel.sent("tool_result"))
        #expect(result.data?["ok"] == .bool(false) && result.data?["error"] == .string("unavailable"))
        await connection.close()
    }

    @Test func aLostConnectionIsMadeAgainTellingWhatWasLost() async throws {
        let server = FakeServer()
        let connection = make(server)
        let log = EventLog(connection.events)
        _ = try await connection.connect()
        let first = try #require(await server.connection(0))
        first.drop(1006)

        _ = try #require(await log.next { if case .disconnected(1006, _) = $0 { true } else { false } })
        _ = try #require(await log.next { if case .retrying(1) = $0 { true } else { false } })
        _ = try #require(await log.next { if case .reconnected = $0 { true } else { false } })
        let second = try #require(await server.connection(1))
        guard case .hello(let hello)? = try? second.sent[0].typedData() else { Issue.record("no hello"); return }
        #expect(hello.resumed == true)
        #expect(hello.resume?.closeCode == 1006)
        #expect(hello.resume?.serverStartedAt == "2026-11-22T09:00:00Z")
        #expect(hello.resume?.lostAt != nil)
        _ = try #require(await second.sent("manifest"))
        #expect(await connection.isConnected)
        await connection.close()
    }

    @Test func aTryThatFailsIsTriedAgainLater() async throws {
        let server = FakeServer()
        let connection = make(server)
        let log = EventLog(connection.events)
        _ = try await connection.connect()
        server.failOpens = 2
        try #require(await server.connection(0)).drop(1006)
        _ = try #require(await log.next { if case .reconnected = $0 { true } else { false } })
        let waits = log.all.compactMap { event -> Int? in if case .retrying(let s) = event { s } else { nil } }
        #expect(waits == [1, 2, 4]) // the wait doubles with each try
        await connection.close()
    }

    @Test func aCloseCodeOfRevokedIsTheEnd() async throws {
        let server = FakeServer()
        let connection = make(server)
        let log = EventLog(connection.events)
        _ = try await connection.connect()
        try #require(await server.connection(0)).drop(4009, "revoked")
        let closed = try #require(await log.next { if case .closed = $0 { true } else { false } })
        guard case .closed(let code, let reason) = closed else { return }
        #expect(code == 4009 && reason == "revoked")
        #expect(await log.waitUntilFinished())
        #expect(server.connections.count == 1) // it did not try again
        await #expect(throws: NodeConnectionError.notConnected) { try await connection.say("Hi") }
    }

    @Test func aServerThatStopsAnsweringIsLeft() async throws {
        let server = FakeServer()
        server.heartbeatSeconds = 1
        let connection = make(server)
        let log = EventLog(connection.events)
        _ = try await connection.connect()
        let lost = try #require(await log.next { if case .disconnected(4008, _) = $0 { true } else { false } })
        _ = lost
        let first = try #require(await server.connection(0))
        #expect(first.sent.contains { $0.type == "ping" })
        _ = try #require(await log.next { if case .reconnected = $0 { true } else { false } })
        await connection.close()
    }

    @Test func closingIsForGood() async throws {
        let server = FakeServer()
        let connection = make(server)
        let log = EventLog(connection.events)
        _ = try await connection.connect()
        await connection.close()
        #expect(await log.next { if case .closed(1000, _) = $0 { true } else { false } } != nil)
        #expect(server.connections[0].closedWith?.0 == 1000)
        #expect(await !connection.isConnected)
        await #expect(throws: NodeConnectionError.notConnected) { try await connection.say("Hi") }
    }

    @Test func aChangedManifestIsSentAtOnce() async throws {
        let server = FakeServer()
        let connection = make(server)
        _ = try await connection.connect()
        let channel = try #require(await server.connection(0))
        try await connection.setManifest(Manifest(version: 2, os: "ios", tools: []))
        let manifests = channel.sent.filter { $0.type == "manifest" }
        #expect(manifests.count == 2)
        #expect(manifests[1].data?["version"] == .int(2))
        await connection.close()
    }
}
