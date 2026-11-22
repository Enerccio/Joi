import Foundation
import JoiProtocol

/// A small CBOR reader (RFC 8949) for the metadata of a `.joim` and a `.joia`: JSON as CBOR. A byte string is read as text, a tag is read
/// through, as `Cbor` in `joi-protocol` does. Maps with other keys than text are refused.
public enum CBOR {
    public struct Failure: Error, Equatable, CustomStringConvertible {
        public let description: String
    }

    /// Decodes one document; bytes after it are an error.
    public static func decode(_ data: Data) throws -> JSONValue {
        var reader = Reader(bytes: [UInt8](data))
        let value = try reader.read(depth: 0)
        guard reader.position == reader.bytes.count else { throw Failure(description: "bytes after the CBOR document") }
        return value
    }

    private struct Reader {
        let bytes: [UInt8]
        var position = 0

        mutating func byte() throws -> UInt8 {
            guard position < bytes.count else { throw Failure(description: "the CBOR ends too soon") }
            defer { position += 1 }
            return bytes[position]
        }

        mutating func take(_ count: Int) throws -> ArraySlice<UInt8> {
            guard count >= 0, position + count <= bytes.count else { throw Failure(description: "the CBOR ends too soon") }
            defer { position += count }
            return bytes[position..<position + count]
        }

        mutating func unsigned(_ info: UInt8) throws -> UInt64? {
            switch info {
            case 0..<24: return UInt64(info)
            case 24: return UInt64(try byte())
            case 25: return try take(2).reduce(0) { $0 << 8 | UInt64($1) }
            case 26: return try take(4).reduce(0) { $0 << 8 | UInt64($1) }
            case 27: return try take(8).reduce(0) { $0 << 8 | UInt64($1) }
            case 31: return nil // indefinite length
            default: throw Failure(description: "a damaged CBOR head")
            }
        }

        mutating func read(depth: Int) throws -> JSONValue {
            guard depth < 64 else { throw Failure(description: "the CBOR is nested too deep") }
            let head = try byte()
            let major = head >> 5, info = head & 31
            switch major {
            case 0:
                guard let value = try unsigned(info) else { throw Failure(description: "a damaged CBOR head") }
                guard value <= UInt64(Int.max) else { return .double(Double(value)) }
                return .int(Int(value))
            case 1:
                guard let value = try unsigned(info) else { throw Failure(description: "a damaged CBOR head") }
                guard value <= UInt64(Int.max) else { return .double(-1 - Double(value)) }
                return .int(-1 - Int(value))
            case 2, 3:
                return .string(try text(info, major: major))
            case 4:
                var items: [JSONValue] = []
                if let count = try unsigned(info) {
                    guard count <= UInt64(bytes.count) else { throw Failure(description: "the CBOR ends too soon") }
                    for _ in 0..<count { items.append(try read(depth: depth + 1)) }
                } else {
                    while try !stop() { items.append(try read(depth: depth + 1)) }
                }
                return .array(items)
            case 5:
                var members: [String: JSONValue] = [:]
                func member(_ reader: inout Reader) throws {
                    guard case .string(let key) = try reader.read(depth: depth + 1) else { throw Failure(description: "a map key that is not text") }
                    members[key] = try reader.read(depth: depth + 1)
                }
                if let count = try unsigned(info) {
                    guard count <= UInt64(bytes.count) else { throw Failure(description: "the CBOR ends too soon") }
                    for _ in 0..<count { try member(&self) }
                } else {
                    while try !stop() { try member(&self) }
                }
                return .object(members)
            case 6:
                _ = try unsigned(info)
                return try read(depth: depth + 1)
            default:
                switch info {
                case 20: return .bool(false)
                case 21: return .bool(true)
                case 22, 23: return .null
                case 25:
                    let bits = try take(2).reduce(UInt16(0)) { $0 << 8 | UInt16($1) }
                    return .double(Double(Float16(bitPattern: bits)))
                case 26:
                    let bits = try take(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
                    return .double(Double(Float(bitPattern: bits)))
                case 27:
                    let bits = try take(8).reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
                    return .double(Double(bitPattern: bits))
                default:
                    throw Failure(description: "a CBOR value that is not JSON")
                }
            }
        }

        /// The break of an indefinite item, consumed when it is next.
        mutating func stop() throws -> Bool {
            guard position < bytes.count else { throw Failure(description: "the CBOR ends too soon") }
            if bytes[position] == 0xFF {
                position += 1
                return true
            }
            return false
        }

        mutating func text(_ info: UInt8, major: UInt8) throws -> String {
            var data = Data()
            if let length = try unsigned(info) {
                guard length <= UInt64(bytes.count) else { throw Failure(description: "the CBOR ends too soon") }
                data.append(contentsOf: try take(Int(length)))
            } else {
                while try !stop() {
                    let head = try byte()
                    guard head >> 5 == major, let length = try unsigned(head & 31) else { throw Failure(description: "a damaged CBOR string") }
                    guard length <= UInt64(bytes.count) else { throw Failure(description: "the CBOR ends too soon") }
                    data.append(contentsOf: try take(Int(length)))
                }
            }
            return String(decoding: data, as: UTF8.self)
        }
    }
}
