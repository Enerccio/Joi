import Foundation
import Testing
import JoiProtocol
@testable import JoiClient

@Suite struct CBORTests {
    @Test func readsWhatTheMetadataHolds() throws {
        let value = try CBOR.decode(Data(BodyFixtures.cbor([
            ("name", "Jumping jacks"), ("loop", false), ("count", 3), ("down", -7), ("speed", 1.25), ("none", 0.5 as Any?),
            ("steps", [[("clip", "a")] as [(String, Any)]] as [Any]),
        ] as [(String, Any)])))
        guard case .object(let members) = value else { Issue.record("not an object"); return }
        #expect(members["name"] == .string("Jumping jacks"))
        #expect(members["loop"] == .bool(false))
        #expect(members["count"] == .int(3))
        #expect(members["down"] == .int(-7))
        #expect(members["speed"] == .double(1.25))
        #expect(members["steps"] == .array([.object(["clip": .string("a")])]))
    }

    @Test func readsHalfFloatsIndefiniteItemsAndTags() throws {
        #expect(try CBOR.decode(Data([0xF9, 0x3C, 0x00])) == .double(1.0))
        #expect(try CBOR.decode(Data([0x9F, 0x01, 0x02, 0xFF])) == .array([.int(1), .int(2)]))
        #expect(try CBOR.decode(Data([0xC1, 0x1A, 0x51, 0x4B, 0x67, 0xB0])) == .int(1363896240))
        #expect(try CBOR.decode(Data([0x7F, 0x61, 0x61, 0x61, 0x62, 0xFF])) == .string("ab"))
    }

    @Test func refusesWhatIsDamaged() {
        #expect(throws: CBOR.Failure.self) { try CBOR.decode(Data([0x82, 0x01])) }          // an array that ends
        #expect(throws: CBOR.Failure.self) { try CBOR.decode(Data([0x01, 0x02])) }          // bytes after it
        #expect(throws: CBOR.Failure.self) { try CBOR.decode(Data([0xA1, 0x01, 0x02])) }    // a key that is not text
        #expect(throws: CBOR.Failure.self) { try CBOR.decode(Data(repeating: 0x81, count: 100)) } // too deep
    }
}

@Suite struct ContainerTests {
    @Test func aJoimGivesItsModel() throws {
        let folder = try BodyFixtures.temporaryFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let model = Array("a model of hundreds of megabytes".utf8)
        let file = folder.appendingPathComponent("body.joim")
        // a section of a kind it does not know is skipped
        try BodyFixtures.joim(model: model, extra: BodyFixtures.section("NEW ", [9, 9])).write(to: file)
        let out = folder.appendingPathComponent("model.vrm")
        let contents = try JoimFile.read(file, model: out)
        #expect(contents.hasModel)
        #expect(try Data(contentsOf: out) == Data(model))
    }

    @Test func aDamagedModelIsRefusedAndLeavesNoFile() throws {
        let folder = try BodyFixtures.temporaryFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let file = folder.appendingPathComponent("body.joim")
        try BodyFixtures.joim(model: [1, 2, 3, 4], corrupt: true).write(to: file)
        let out = folder.appendingPathComponent("model.vrm")
        #expect(throws: ContainerError.self) { try JoimFile.read(file, model: out) }
        #expect(!FileManager.default.fileExists(atPath: out.path))
    }

    @Test func notAJoimAndANewerOneAreRefused() throws {
        let folder = try BodyFixtures.temporaryFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let file = folder.appendingPathComponent("x.joim")
        try Data("this is a text".utf8).write(to: file)
        #expect(throws: ContainerError.self) { try JoimFile.read(file, model: folder.appendingPathComponent("m")) }
        var newer = BodyFixtures.joim(model: [1])
        newer[5] = 2 // major 2
        try newer.write(to: file)
        #expect(throws: ContainerError.self) { try JoimFile.read(file, model: folder.appendingPathComponent("m")) }
        try Data().write(to: file)
        #expect(throws: ContainerError.self) { try JoimFile.read(file, model: folder.appendingPathComponent("m")) }
    }

    @Test func aJoiaGivesItsAnimationsAndClips() throws {
        let folder = try BodyFixtures.temporaryFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let animation: [(String, Any)] = [
            ("uuid", "wave"), ("name", "Wave"), ("loop", true),
            ("steps", [[("clip", "c1"), ("loop", false), ("speed", 1.5), ("inPlace", true), ("rotation", [0.0, 0.5, 0.0, 0.5] as [Any]), ("translation", [0.0, 0.0, -0.35] as [Any]), ("blend", 0.25)] as [(String, Any)]] as [Any]),
        ]
        let file = folder.appendingPathComponent("body.joia")
        try BodyFixtures.joia(clips: [("c1", [1, 2, 3]), ("c2", [4, 5])], animations: [animation], extra: true).write(to: file)
        let contents = try JoiaFile.read(file) { index, _ in folder.appendingPathComponent("\(index).vrma") }
        #expect(contents.clips.map(\.uuid) == ["c1", "c2"])
        #expect(contents.clips[0].duration == 1.5)
        #expect(contents.animations.count == 1)
        #expect(contents.animations[0].name == "Wave")
        #expect(contents.animations[0].steps == [JoiaStep(clip: "c1", loop: false, speed: 1.5, inPlace: true, rotation: [0, 0.5, 0, 0.5], translation: [0, 0, -0.35], blend: 0.25)])
        #expect(try Data(contentsOf: folder.appendingPathComponent("0.vrma")) == Data([1, 2, 3]))
        #expect(try Data(contentsOf: folder.appendingPathComponent("1.vrma")) == Data([4, 5]))
    }

    @Test func aJoiaWithTooFewOrTooManyClipsIsRefused() throws {
        let folder = try BodyFixtures.temporaryFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let file = folder.appendingPathComponent("body.joia")
        var data = BodyFixtures.joia(clips: [("c1", [1])], animations: [])
        data.append(contentsOf: BodyFixtures.section("VRMA", [2]))
        try data.write(to: file)
        #expect(throws: ContainerError.self) { try JoiaFile.read(file) { _, _ in nil } }
        let fewer = BodyFixtures.joia(clips: [("c1", [1]), ("c2", [2])], animations: []).dropLast(4 + 8 + 32 + 1) // the last clip is missing
        try Data(fewer).write(to: file)
        #expect(throws: ContainerError.self) { try JoiaFile.read(file) { _, _ in nil } }
    }
}

@Suite struct DownloadTests {
    let server = URL(string: "https://joi.example.com")!

    private func file(_ body: String, url: String = "/api/v1/bodies/b1/joim") -> Download {
        Download(url: url, sha256: BodyFixtures.hex(Data(body.utf8)), size: body.utf8.count, writtenAt: Date(), current: true)
    }

    @Test func aFileIsDownloadedCheckedAndKeptByItsHash() async throws {
        let folder = try BodyFixtures.temporaryFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let http = FakeHTTP { _ in (200, "the model") }
        let client = JoiClient(server: server, store: MemoryCredentialStore(Credentials(token: "joi_abc")), transport: http)
        let download = file("the model")
        let kept = try await client.download(download, into: folder)
        #expect(kept.lastPathComponent == download.sha256 + ".joim")
        #expect(try String(contentsOf: kept, encoding: .utf8) == "the model")
        #expect(http.recorded.first?.path == "/api/v1/bodies/b1/joim")
        #expect(http.recorded.first?.authorization == "Bearer joi_abc")
        // the next time it is in the cache
        _ = try await client.download(download, into: folder)
        #expect(http.recorded.count == 1)
    }

    @Test func theAddressTheServerWasReachedAtDoesNotCount() async throws {
        let folder = try BodyFixtures.temporaryFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let http = FakeHTTP { _ in (200, "clips") }
        let client = JoiClient(server: server, store: MemoryCredentialStore(Credentials(token: "t")), transport: http)
        _ = try await client.download(file("clips", url: "http://localhost:8080/api/v1/bodies/b1/joia?v=2"), into: folder)
        #expect(http.recorded.first?.path == "/api/v1/bodies/b1/joia?v=2")
    }

    @Test func aFileThatIsNotWhatTheServerSaidIsNotKept() async throws {
        let folder = try BodyFixtures.temporaryFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let http = FakeHTTP { _ in (200, "something else") }
        let client = JoiClient(server: server, store: MemoryCredentialStore(Credentials(token: "t")), transport: http)
        let download = file("the model")
        await #expect(throws: ServerError.self) { try await client.download(download, into: folder) }
        #expect(try FileManager.default.contentsOfDirectory(atPath: folder.path).isEmpty)
    }

    @Test func aHashThatCouldLeadOutOfTheCacheIsRefused() async throws {
        let folder = try BodyFixtures.temporaryFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let http = FakeHTTP { _ in (200, "x") }
        let client = JoiClient(server: server, store: MemoryCredentialStore(Credentials(token: "t")), transport: http)
        let bad = Download(url: "/x/joim", sha256: "../../etc/passwd", size: 1, writtenAt: Date(), current: true)
        await #expect(throws: ServerError.self) { try await client.download(bad, into: folder) }
        #expect(http.recorded.isEmpty)
    }

    @Test func anErrorOfTheServerIsItsError() async throws {
        let folder = try BodyFixtures.temporaryFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let http = FakeHTTP { _ in (404, #"{"code":"not_found","message":"No such file"}"#) }
        let client = JoiClient(server: server, store: MemoryCredentialStore(Credentials(token: "t")), transport: http)
        do {
            _ = try await client.download(file("x"), into: folder)
            Issue.record("it should fail")
        } catch let error as ServerError {
            #expect(error.status == 404)
            #expect(error.code == "not_found")
        }
    }
}
