import CryptoKit
import Foundation

/// SHA-256 and file copying in blocks, so that a model of hundreds of megabytes is never in memory.
enum FileHash {
    static let block = 256 * 1024

    static func hex(_ digest: SHA256.Digest) -> String {
        digest.map { String(format: "%02x", $0) }.joined()
    }

    /// The SHA-256 of a file in hex.
    static func sha256(of file: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: file)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let data = try handle.read(upToCount: block), !data.isEmpty {
            hasher.update(data: data)
        }
        return hex(hasher.finalize())
    }
}
