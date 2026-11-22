import CryptoKit
import Foundation
@testable import JoiClient

/// Writes the files the server makes (`.joim`, `.joia`) the way `JoimFile` and `JoiaFile` in joi-protocol do, and CBOR for their metadata.
enum BodyFixtures {
    static func cbor(_ value: Any) -> [UInt8] {
        func head(_ major: UInt8, _ n: Int) -> [UInt8] {
            if n < 24 { return [major << 5 | UInt8(n)] }
            if n < 256 { return [major << 5 | 24, UInt8(n)] }
            if n < 65536 { return [major << 5 | 25, UInt8(n >> 8), UInt8(n & 255)] }
            return [major << 5 | 26] + (0..<4).reversed().map { UInt8((n >> ($0 * 8)) & 255) }
        }
        switch value {
        case let value as Bool: return [value ? 0xF5 : 0xF4]
        case let value as Int: return value >= 0 ? head(0, value) : head(1, -1 - value)
        case let value as Double: return [0xFB] + (0..<8).reversed().map { UInt8((value.bitPattern >> UInt64($0 * 8)) & 255) }
        case let value as String: return head(3, value.utf8.count) + Array(value.utf8)
        case let value as [(String, Any)] where !value.isEmpty: return head(5, value.count) + value.flatMap { cbor($0.0) + cbor($0.1) }
        case let value as [Any]: return head(4, value.count) + value.flatMap { cbor($0) }
        default: return [0xF6]
        }
    }

    private static func be(_ value: UInt64, _ bytes: Int) -> [UInt8] {
        (0..<bytes).reversed().map { UInt8((value >> UInt64($0 * 8)) & 255) }
    }

    static func section(_ kind: String, _ data: [UInt8], hash: [UInt8]? = nil) -> [UInt8] {
        Array(kind.utf8) + be(UInt64(data.count), 8) + (hash ?? Array(SHA256.hash(data: data))) + data
    }

    static func joim(model: [UInt8], meta: Any = [("toggles", [Any]())] as [(String, Any)], extra: [UInt8] = [], corrupt: Bool = false) -> Data {
        let bad = Array(repeating: UInt8(0), count: 32)
        return Data(be(0x1DEA1A10, 4) + be(1, 2) + be(0, 2) + [1] + section("META", cbor(meta)) + extra
                    + section("VRM ", model, hash: corrupt ? bad : nil))
    }

    static func joia(clips: [(uuid: String, bytes: [UInt8])], animations: [Any], extra: Bool = false) -> Data {
        let meta: [(String, Any)] = [
            ("animations", animations),
            ("clips", clips.map { [("uuid", $0.uuid), ("duration", 1.5)] as [(String, Any)] }),
        ]
        var out = be(0x1DEA1AC7, 4) + be(1, 2) + be(0, 2) + section("META", cbor(meta))
        if extra { out += section("XXXX", [1, 2, 3]) }
        for clip in clips { out += section("VRMA", clip.bytes) }
        return Data(out)
    }

    static func temporaryFolder() throws -> URL {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("joi-test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    static func hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}
