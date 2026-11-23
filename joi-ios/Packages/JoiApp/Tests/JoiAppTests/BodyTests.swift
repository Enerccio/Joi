import CryptoKit
import Foundation
import Testing
import JoiClient
import JoiProtocol
@testable import JoiApp

/// Her files as the server makes them, for the tests (the same container layout as `JoimFile` and `JoiaFile` in joi-protocol).
enum Files {
    static func be(_ value: UInt64, _ bytes: Int) -> [UInt8] {
        (0..<bytes).reversed().map { UInt8((value >> UInt64($0 * 8)) & 255) }
    }

    static func section(_ kind: String, _ data: [UInt8]) -> [UInt8] {
        Array(kind.utf8) + be(UInt64(data.count), 8) + Array(SHA256.hash(data: data)) + data
    }

    /// CBOR of `{"animations": [{uuid, name, steps: [{clip, speed}...]}], "clips": [{uuid, duration}...]}` for the animations given.
    static func joia(_ animations: [(String, [String])], clips: [String]) -> Data {
        func text(_ s: String) -> [UInt8] { [0x60 | UInt8(s.utf8.count)] + Array(s.utf8) }
        func array(_ items: [[UInt8]]) -> [UInt8] { [0x80 | UInt8(items.count)] + items.flatMap { $0 } }
        func map(_ items: [(String, [UInt8])]) -> [UInt8] { [0xA0 | UInt8(items.count)] + items.flatMap { text($0.0) + $0.1 } }
        let meta = map([
            ("animations", array(animations.map { uuid, steps in
                map([("uuid", text(uuid)), ("steps", array(steps.map { map([("clip", text($0)), ("speed", [0xF9, 0x40, 0x00])]) }))])
            })),
            ("clips", array(clips.map { map([("uuid", text($0)), ("duration", [0xF9, 0x44, 0x00])]) })), // 4.0 s
        ])
        var out = be(0x1DEA1AC7, 4) + be(1, 2) + be(0, 2) + section("META", meta)
        for clip in clips { out += section("VRMA", Array(clip.utf8)) }
        return Data(out)
    }

    static func joim(model: [UInt8]) -> Data {
        Data(be(0x1DEA1A10, 4) + be(1, 2) + be(0, 2) + [1] + section("META", [0xA0]) + section("VRM ", model))
    }

    static func folder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("joi-app-test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}

@MainActor @Suite struct BodyFilesTests {
    @Test func herFilesAreUnpackedOnceAndTheClipsAreFoundByTheirUuid() throws {
        let folder = try Files.folder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let joim = folder.appendingPathComponent("abc.joim"), joia = folder.appendingPathComponent("def.joia")
        try Files.joim(model: [7, 8, 9]).write(to: joim)
        try Files.joia([("wave", ["c1", "c2"])], clips: ["c1", "c2"]).write(to: joia)
        let cache = folder.appendingPathComponent("cache")
        let files = try BodyFiles.unpack(joim: joim, joia: joia, into: cache)
        #expect(try Data(contentsOf: files.model!) == Data([7, 8, 9]))
        #expect(files.animations["wave"]?.steps?.map(\.clip) == ["c1", "c2"])
        #expect(try Data(contentsOf: files.clips["c2"]!) == Data("c2".utf8))
        #expect(files.lengths["c1"] == 4.0)
        // the second time nothing is unpacked again, and it is the same
        try FileManager.default.removeItem(at: joim)
        try FileManager.default.removeItem(at: joia)
        let again = try BodyFiles.unpack(joim: joim.deletingLastPathComponent().appendingPathComponent("abc.joim"), joia: nil, into: cache)
        #expect(again.model == files.model)
    }

    @Test func noFilesIsNoBody() throws {
        let files = try BodyFiles.unpack(joim: nil, joia: nil, into: try Files.folder())
        #expect(files.model == nil && files.animations.isEmpty)
    }

    @Test func theFilesOfOtherBodiesAreCleanedAway() throws {
        let folder = try Files.folder()
        defer { try? FileManager.default.removeItem(at: folder) }
        for name in ["a.joim", "a-joim", "b.joim", "b-joim", "other.txt"] {
            FileManager.default.createFile(atPath: folder.appendingPathComponent(name).path, contents: nil)
        }
        BodyFiles.clean(folder, keeping: ["a.joim", "a-joim"])
        #expect(Set(try FileManager.default.contentsOfDirectory(atPath: folder.path)) == ["a.joim", "a-joim", "other.txt"])
    }
}

@MainActor @Suite struct RendererBridgeTests {
    private func bridge(_ animations: [JoiaAnimation] = [], lengths: [String: Double] = [:]) -> (RendererBridge, Sent) {
        let bridge = RendererBridge()
        let sent = Sent()
        bridge.send = { sent.messages.append($0) }
        var files = BodyFiles(model: URL(fileURLWithPath: "/m.vrm"), animations: Dictionary(uniqueKeysWithValues: animations.map { ($0.uuid, $0) }),
                              clips: ["c1": URL(fileURLWithPath: "/c1.vrma")], lengths: lengths)
        files = BodyFiles(model: files.model, animations: files.animations, clips: files.clips, lengths: files.lengths)
        bridge.show(files)
        sent.messages = []
        return (bridge, sent)
    }

    @MainActor final class Sent {
        var messages: [JSONValue] = []
        func types() -> [String] { messages.compactMap { if case .object(let o) = $0, case .string(let t)? = o["type"] { t } else { nil } } }
    }

    private func object(_ value: JSONValue) -> [String: JSONValue] {
        if case .object(let members) = value { return members }
        return [:]
    }

    @Test func aLoopingAnimationIsSentAsMade() {
        let wave = JoiaAnimation(uuid: "wave", name: "Wave", steps: [JoiaStep(clip: "c1", loop: true, speed: 2, inPlace: true, rotation: [0, 1, 0, 0], blend: 0.5)])
        let (bridge, sent) = bridge([wave])
        bridge.play("wave", loop: true)
        let message = object(sent.messages[0])
        #expect(message["type"] == .string("play"))
        #expect(message["clips"] == .array([.string("body/clip/c1.vrma")]))
        let sequence = object(message["sequence"]!)
        #expect(sequence["loop"] == .bool(true))
        guard case .array(let steps)? = sequence["steps"] else { Issue.record("no steps"); return }
        let step = object(steps[0])
        #expect(step["src"] == .int(0) && step["loop"] == .bool(true) && step["speed"] == .double(2) && step["inPlace"] == .bool(true))
        #expect(step["rotation"] == .array([.double(0), .double(1), .double(0), .double(0)]) && step["blend"] == .double(0.5))
    }

    @Test func herVoiceIsSentForHerMouthAndNotKept() {
        let (bridge, sent) = bridge()
        bridge.speech(2, AudioChunk(index: 0, text: "Hi", mimeType: "audio/wav", data: Data([1, 2, 3])))
        bridge.speechStarted(2)
        bridge.speechStopped()
        #expect(sent.types() == ["speech", "speech-start", "speech-stop"])
        #expect(object(sent.messages[0])["data"] == .string("AQID") && object(sent.messages[0])["index"] == .int(2))
        sent.messages = []
        bridge.pageReady()
        #expect(!sent.types().contains { $0.hasPrefix("speech") })
    }

    @Test func herEyesAndMoodAreSentAndSentAgainToANewPage() {
        let (bridge, sent) = bridge()
        bridge.setEyes("closed", seconds: 3)
        bridge.setMood(MoodState(mood: "sleepy", name: "Sleepy", expression: "relaxed", weight: 0.3, eyes: "half"))
        bridge.setMood(nil)
        #expect(sent.types() == ["eyes", "mood", "mood"])
        #expect(object(sent.messages[0])["eyes"] == .string("closed") && object(sent.messages[0])["ease"] == .double(3))
        let mood = object(sent.messages[1])
        #expect(mood["mood"] == .string("sleepy") && mood["expression"] == .string("relaxed") && mood["weight"] == .double(0.3) && mood["eyes"] == .string("half"))
        #expect(object(sent.messages[2])["mood"] == nil)
        sent.messages = []
        bridge.pageReady()
        let again = sent.messages.filter { if case .string(let t)? = object($0)["type"] { t == "eyes" || t == "mood" } else { false } }
        #expect(again.count == 2 && object(again[1])["mood"] == nil)
    }

    @Test func anAnimationsFaceGoesWithItsSequence() {
        let sleeping = JoiaAnimation(uuid: "sleep", steps: [JoiaStep(clip: "c1")], eyes: "closed", expression: "sad")
        let plain = JoiaAnimation(uuid: "plain", steps: [JoiaStep(clip: "c1")])
        let (bridge, sent) = bridge([sleeping, plain])
        bridge.play("sleep", loop: true)
        bridge.play("plain", loop: true)
        let face = object(object(sent.messages[0])["sequence"]!)["face"]
        #expect(face == .object(["eyes": .string("closed"), "expression": .string("sad")]))
        #expect(object(object(sent.messages[1])["sequence"]!)["face"] == nil)
    }

    @Test func aClipPlayedByTwoStepsIsSentOnce() {
        let wave = JoiaAnimation(uuid: "wave", steps: [JoiaStep(clip: "c1"), JoiaStep(clip: "c2"), JoiaStep(clip: "c1")])
        let (bridge, sent) = bridge([wave])
        bridge.play("wave", loop: false)
        let message = object(sent.messages[0])
        #expect(message["clips"] == .array([.string("body/clip/c1.vrma"), .string("body/clip/c2.vrma")]))
        guard case .array(let steps)? = object(message["sequence"]!)["steps"] else { return }
        #expect(steps.map { object($0)["src"] } == [.int(0), .int(1), .int(0)])
        // once: no step loops
        #expect(steps.allSatisfy { object($0)["loop"] == .bool(false) })
    }

    @Test func theEndOfAnAnimationThatDoesNotLoopIsToldOnce() {
        let (bridge, _) = bridge([JoiaAnimation(uuid: "wave", steps: [JoiaStep(clip: "c1")])])
        var ended = 0
        bridge.onEnded = { ended += 1 }
        bridge.play("wave", loop: false)
        bridge.ended(1)
        bridge.ended(1)
        #expect(ended == 1)
        // one that was replaced is not told
        bridge.play("wave", loop: false)
        bridge.play("wave", loop: true)
        bridge.ended(2)
        #expect(ended == 1)
    }

    @Test func anAnimationHerBodyDoesNotHaveEndsAtOnce() async {
        let (bridge, sent) = bridge()
        var ended = 0
        bridge.onEnded = { ended += 1 }
        bridge.play("missing", loop: false)
        await Task.yield()
        #expect(sent.types() == ["stop"])
        #expect(ended == 1)
        bridge.play(nil, loop: true)
        await Task.yield()
        #expect(ended == 1) // a loop is never over
    }

    @Test func anAnimationThatTheRendererDoesNotTellAboutEndsByItself() async throws {
        let (bridge, _) = bridge([JoiaAnimation(uuid: "wave", steps: [JoiaStep(clip: "c1", speed: 100)])], lengths: ["c1": 0.001])
        bridge.graceSeconds = 0.05
        var ended = 0
        bridge.onEnded = { ended += 1 }
        bridge.play("wave", loop: false)
        #expect(ended == 0)
        // its length and the grace
        for _ in 0..<400 where ended == 0 { try await Task.sleep(for: .milliseconds(10)) }
        #expect(ended == 1)
    }

    @Test func aPageThatOpensLaterGetsTheStateAsItIs() {
        let bridge = RendererBridge()
        bridge.show(BodyFiles(model: URL(fileURLWithPath: "/m.vrm")))
        bridge.setToggles([BodyToggle(mesh: "Hat", hides: false, shown: false)])
        bridge.setOffline(true)
        let sent = Sent()
        bridge.send = { sent.messages.append($0) }
        #expect(sent.messages.isEmpty)
        bridge.pageReady()
        #expect(sent.types() == ["model", "toggles", "offline"])
        #expect(object(sent.messages[0])["src"] == .string("body/model.vrm?1"))
    }

    @Test func whatSheWasToPlayBeforeHerBodyCameIsPlayedWhenItComes() {
        let bridge = RendererBridge()
        let sent = Sent()
        bridge.send = { sent.messages.append($0) }
        bridge.play("idle", loop: true) // no body yet
        bridge.play("wave", loop: false) // a gesture is not waited for
        sent.messages = []
        bridge.show(BodyFiles(model: URL(fileURLWithPath: "/m.vrm"), animations: ["idle": JoiaAnimation(uuid: "idle", steps: [JoiaStep(clip: "c1")])]))
        #expect(sent.types() == ["model", "play"])
    }

    @Test func theModelIsDrawnWhenThePageSaysSo() {
        let bridge = RendererBridge()
        var drawn: [Bool] = []
        bridge.onDrawn = { drawn.append($0) }
        bridge.show(BodyFiles(model: URL(fileURLWithPath: "/m.vrm")))
        bridge.modelLoaded()
        bridge.show(BodyFiles(model: URL(fileURLWithPath: "/n.vrm")))
        #expect(drawn == [false, true, false])
    }

    @Test func noModelStopsTheRenderer() {
        let (bridge, sent) = bridge()
        bridge.show(.none)
        #expect(sent.types() == ["stop"])
        sent.messages = []
        bridge.pageReady()
        #expect(sent.messages.isEmpty)
    }

    @Test func theSchemeHandlerFindsHerFilesByTheirPaths() {
        let (bridge, _) = bridge()
        #expect(bridge.files.file("body/model.vrm")?.path == "/m.vrm")
        #expect(bridge.files.file("body/clip/c1.vrma")?.path == "/c1.vrma")
        #expect(bridge.files.file("body/clip/other.vrma") == nil)
        #expect(bridge.files.file("body/../etc") == nil)
    }
}

/// Serves what the tests say.
struct FakeBodySource: BodySource {
    var info: BodyInfo?
    var files: [String: URL] = [:]
    var error: Error?

    func body(_ joi: String) async throws -> BodyInfo? {
        if let error { throw error }
        return info
    }

    func download(_ file: Download, into cache: URL) async throws -> URL {
        guard let url = files[file.url] else { throw ServerError(status: 404, code: "not_found", message: "No such file") }
        return url
    }
}

@MainActor @Suite struct AvatarModelTests {
    private func download(_ name: String) -> Download {
        Download(url: "/api/v1/bodies/b/" + name, sha256: String(repeating: "a", count: 64), size: 1_500_000, writtenAt: Date(), current: true)
    }

    private func info(model: Bool, animations: Bool) -> BodyInfo {
        BodyInfo(id: "b", model: model ? BodyModel(id: "m", joim: download("joim")) : nil, parts: [],
                 toggles: [BodyToggle(mesh: "Hat", hides: false, shown: false)],
                 animations: BodyAnimations(all: true, animations: [], joia: animations ? download("joia") : nil))
    }

    @Test func herBodyIsDownloadedUnpackedAndShown() async throws {
        let folder = try Files.folder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let joim = folder.appendingPathComponent("x.joim"), joia = folder.appendingPathComponent("y.joia")
        try Files.joim(model: [1, 2, 3]).write(to: joim)
        try Files.joia([("wave", ["c1"])], clips: ["c1"]).write(to: joia)
        let avatar = AvatarModel(page: nil, cache: folder.appendingPathComponent("cache"))
        let sent = RendererBridgeTests.Sent()
        avatar.bridge.send = { sent.messages.append($0) }
        avatar.load(from: FakeBodySource(info: info(model: true, animations: true),
                                         files: ["/api/v1/bodies/b/joim": joim, "/api/v1/bodies/b/joia": joia]), joi: "j1")
        await avatar.loaded()
        #expect(avatar.status == .shown)
        #expect(sent.types() == ["model", "toggles"])
        avatar.bridge.play("wave", loop: true)
        #expect(sent.types().last == "play")
        #expect(avatar.bridge.files.file("body/model.vrm") != nil)
    }

    @Test func aModelWithoutAnimationsIsShownToo() async throws {
        let folder = try Files.folder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let joim = folder.appendingPathComponent("x.joim")
        try Files.joim(model: [1]).write(to: joim)
        let avatar = AvatarModel(page: nil, cache: folder.appendingPathComponent("cache"))
        avatar.load(from: FakeBodySource(info: info(model: true, animations: false), files: ["/api/v1/bodies/b/joim": joim]), joi: "j1")
        await avatar.loaded()
        #expect(avatar.status == .shown)
    }

    @Test func theSameFilesAreNotShownAgainOnlyHerPartsAreRead() async throws {
        let folder = try Files.folder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let joim = folder.appendingPathComponent("x.joim")
        try Files.joim(model: [1]).write(to: joim)
        let avatar = AvatarModel(page: nil, cache: folder.appendingPathComponent("cache"))
        let sent = RendererBridgeTests.Sent()
        avatar.bridge.send = { sent.messages.append($0) }
        var changed = info(model: true, animations: false)
        avatar.load(from: FakeBodySource(info: changed, files: ["/api/v1/bodies/b/joim": joim]), joi: "j1")
        await avatar.loaded()
        #expect(sent.types() == ["model", "toggles"])
        changed.parts = [BodyPart(id: "mesh:Hat", name: "Hat", state: "off", default: false)]
        changed.toggles = [BodyToggle(mesh: "Hat", hides: false, shown: false)]
        avatar.load(from: FakeBodySource(info: changed, files: ["/api/v1/bodies/b/joim": joim]), joi: "j1")
        await avatar.loaded()
        #expect(sent.types() == ["model", "toggles", "toggles"]) // no model again
        #expect(avatar.parts.map(\.name) == ["Hat"])
        // her outfits, the one she wears marked (the same files: only what changed is read)
        changed.outfits = [BodyOutfit(id: "o1", name: "Casual", description: "A hoodie.", worn: false), BodyOutfit(id: "o2", name: "Sailor", worn: true)]
        avatar.load(from: FakeBodySource(info: changed, files: ["/api/v1/bodies/b/joim": joim]), joi: "j1")
        await avatar.loaded()
        #expect(avatar.outfits.map(\.name) == ["Casual", "Sailor"] && avatar.outfits.map(\.worn) == [false, true])
    }

    @Test func noBodyAndNoModelAreNoBody() async {
        for source in [FakeBodySource(info: nil), FakeBodySource(info: info(model: false, animations: false))] {
            let avatar = AvatarModel(page: nil)
            avatar.load(from: source, joi: "j1")
            await avatar.loaded()
            #expect(avatar.status == .none)
        }
    }

    @Test func aFailureIsToldAndKeepsNothingShown() async {
        let avatar = AvatarModel(page: nil)
        avatar.load(from: FakeBodySource(error: ServerError(status: 500, code: "x", message: "broken")), joi: "j1")
        await avatar.loaded()
        #expect(avatar.status == .failed("broken"))
        avatar.load(from: FakeBodySource(info: info(model: true, animations: false)), joi: "j1") // the file is not there
        await avatar.loaded()
        #expect(avatar.status == .failed("No such file"))
    }
}
