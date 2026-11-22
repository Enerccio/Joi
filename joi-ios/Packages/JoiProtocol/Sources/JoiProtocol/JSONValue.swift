import Foundation

/// Any JSON value: the free-form parts of the protocol (a tool's JSON Schema, a call's arguments, the data of a frame
/// of a type this version does not know).
public enum JSONValue: Hashable, Sendable, Codable {
    case null
    case bool(Bool)
    case int(Int)
    case double(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Int.self) {
            self = .int(value)
        } else if let value = try? container.decode(Double.self) {
            self = .double(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
        } else {
            self = .object(try container.decode([String: JSONValue].self))
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null: try container.encodeNil()
        case .bool(let value): try container.encode(value)
        case .int(let value): try container.encode(value)
        case .double(let value): try container.encode(value)
        case .string(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .object(let value): try container.encode(value)
        }
    }

    /// The same value without the object keys that are null (the protocol takes a missing field and a null one alike).
    public var removingNulls: JSONValue {
        switch self {
        case .object(let members):
            return .object(members.filter { $0.value != .null }.mapValues(\.removingNulls))
        case .array(let items):
            return .array(items.map(\.removingNulls))
        default:
            return self
        }
    }
}

public enum JoiProtocolError: Error, Equatable {
    /// A frame of a type that carries data came without it.
    case missingData(type: String)
    /// A value that should encode as a JSON object did not.
    case notAnObject
    /// A date that is not ISO 8601.
    case badDate(String)
}

/// The coders for the protocol's JSON: dates are ISO 8601 (with fractional seconds or without), Base64 for binary.
public enum JoiJSON {
    public static func makeDecoder() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let text = try decoder.singleValueContainer().decode(String.self)
            if let date = try? Date(text, strategy: Date.ISO8601FormatStyle(includingFractionalSeconds: true)) {
                return date
            }
            do {
                return try Date(text, strategy: Date.ISO8601FormatStyle())
            } catch {
                throw JoiProtocolError.badDate(text)
            }
        }
        return decoder
    }

    public static func makeEncoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        encoder.dateEncodingStrategy = .custom { date, encoder in
            let whole = date.timeIntervalSince1970.rounded(.down)
            let fractional = date.timeIntervalSince1970 - whole != 0
            var container = encoder.singleValueContainer()
            try container.encode(date.formatted(Date.ISO8601FormatStyle(includingFractionalSeconds: fractional)))
        }
        return encoder
    }
}

extension Frame {
    /// The data of the frame as a type (what the generated `typedData()` does for each type of frame).
    func decodeData<T: Decodable>(_ type: T.Type) throws -> T {
        guard let data else { throw JoiProtocolError.missingData(type: self.type) }
        let bytes = try JoiJSON.makeEncoder().encode(JSONValue.object(data))
        return try JoiJSON.makeDecoder().decode(T.self, from: bytes)
    }

    /// A value as the data of a frame.
    static func encodeData<T: Encodable>(_ value: T) throws -> [String: JSONValue] {
        let bytes = try JoiJSON.makeEncoder().encode(value)
        guard case .object(let members) = try JSONDecoder().decode(JSONValue.self, from: bytes) else {
            throw JoiProtocolError.notAnObject
        }
        return members
    }
}
