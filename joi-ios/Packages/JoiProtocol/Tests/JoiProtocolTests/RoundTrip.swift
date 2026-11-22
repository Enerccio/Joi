import Foundation
@testable import JoiProtocol

// No Testing here: with Foundation in the same file it needs a cross-import module that the Command Line Tools do not have.
// A fixture that fails throws, and the test (Generated/FixtureTests.swift) fails with it.

struct FixtureError: Error, CustomStringConvertible {
    let description: String
}

/// Decodes a sample of the server's JSON as `T`, encodes it again, and expects the same JSON (a null is a missing field).
func roundTrip<T: Codable & Equatable>(_ type: T.Type, _ json: String) throws {
    let original = try JSONDecoder().decode(JSONValue.self, from: Data(json.utf8))
    let value = try JoiJSON.makeDecoder().decode(T.self, from: Data(json.utf8))
    let again = try JSONDecoder().decode(JSONValue.self, from: try JoiJSON.makeEncoder().encode(value))
    guard again.removingNulls == original.removingNulls else {
        throw FixtureError(description: "\(T.self) came out different:\n\(original.removingNulls)\n\(again.removingNulls)")
    }
}

/// A frame of a type of this version has its data decoded as that type, not as `.unknown`.
func typedFrame(_ json: String) throws {
    let frame = try JoiJSON.makeDecoder().decode(Frame.self, from: Data(json.utf8))
    if case .unknown = try frame.typedData() {
        throw FixtureError(description: "the frame \(frame.type) is unknown")
    }
}

// Helpers of HandWrittenTests (which has no Foundation, see above).

func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
    try JoiJSON.makeDecoder().decode(T.self, from: Data(json.utf8))
}

func encodeToString<T: Encodable>(_ value: T) throws -> String {
    String(decoding: try JoiJSON.makeEncoder().encode(value), as: UTF8.self)
}
