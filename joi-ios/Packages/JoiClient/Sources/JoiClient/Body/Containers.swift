import CryptoKit
import Foundation
import JoiProtocol

/// What reading a `.joim` or a `.joia` can fail on.
public struct ContainerError: Error, Equatable, CustomStringConvertible, Sendable {
    public let description: String

    public init(description: String) {
        self.description = description
    }
}

/// The sections of a `.joim` / `.joia` (`joim.md`, `joia.md` in joi-protocol): kind (4 ASCII), length (u64), SHA-256 (32), data; numbers big-endian.
/// Read as a stream: a section's data is hashed as it is copied, never whole in memory (a model is hundreds of megabytes).
final class SectionStream {
    struct Head {
        let kind: String
        let length: UInt64
        let hash: [UInt8]
    }

    private let handle: FileHandle
    let major: Int
    let minor: Int
    let profile: Int?

    /// - Parameter profile: whether a byte of profile follows the version (a `.joim` has one, a `.joia` not).
    init(file: URL, magic: UInt32, name: String, supportedMajor: Int, profile: Bool) throws {
        handle = try FileHandle(forReadingFrom: file)
        do {
            let head = try Self.exactly(handle, profile ? 9 : 8, name: name)
            guard head.prefix(4).reduce(UInt32(0), { $0 << 8 | UInt32($1) }) == magic else { throw ContainerError(description: "not a \(name)") }
            major = Int(head[4]) << 8 | Int(head[5])
            minor = Int(head[6]) << 8 | Int(head[7])
            guard major <= supportedMajor else {
                throw ContainerError(description: "the file is of a newer format (\(major).\(minor)): update Joi to read it")
            }
            self.profile = profile ? Int(head[8]) : nil
        } catch {
            try? handle.close()
            throw error
        }
    }

    deinit {
        try? handle.close()
    }

    private static func exactly(_ handle: FileHandle, _ count: Int, name: String) throws -> [UInt8] {
        var out = [UInt8]()
        out.reserveCapacity(count)
        while out.count < count {
            guard let data = try handle.read(upToCount: count - out.count), !data.isEmpty else {
                throw ContainerError(description: out.isEmpty && count == 4 ? "not a \(name)" : "the \(name) ends too soon")
            }
            out.append(contentsOf: data)
        }
        return out
    }

    /// The next section's head, nil at the end of the file.
    func next() throws -> Head? {
        guard let first = try handle.read(upToCount: 1), !first.isEmpty else { return nil }
        let rest = try Self.exactly(handle, 3 + 8 + 32, name: "file")
        let kind = String(decoding: first + rest.prefix(3), as: UTF8.self)
        let length = rest[3..<11].reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
        guard length <= UInt64(Int64.max) else { throw ContainerError(description: "a damaged section: \(kind)") }
        return Head(kind: kind, length: length, hash: Array(rest[11..<43]))
    }

    /// A section that is small (the metadata), whole and checked.
    func data(_ head: Head, limit: UInt64 = 64 * 1024 * 1024) throws -> Data {
        guard head.length <= limit else { throw ContainerError(description: "the section \(head.kind) is too large") }
        let bytes = try Self.exactly(handle, Int(head.length), name: "file")
        guard Array(SHA256.hash(data: bytes)) == head.hash else { throw ContainerError(description: "the section \(head.kind) is damaged (its hash differs)") }
        return Data(bytes)
    }

    /// A section copied into a file, checked as it goes; a damaged one leaves no file.
    func copy(_ head: Head, to destination: URL) throws {
        let fm = FileManager.default
        try? fm.removeItem(at: destination)
        guard fm.createFile(atPath: destination.path, contents: nil) else { throw ContainerError(description: "\(destination.path) could not be written") }
        let out = try FileHandle(forWritingTo: destination)
        var good = false
        defer {
            try? out.close()
            if !good { try? fm.removeItem(at: destination) }
        }
        var hasher = SHA256()
        var left = head.length
        while left > 0 {
            guard let chunk = try handle.read(upToCount: Int(min(UInt64(FileHash.block), left))), !chunk.isEmpty else {
                throw ContainerError(description: "the section \(head.kind) ends too soon")
            }
            hasher.update(data: chunk)
            try out.write(contentsOf: chunk)
            left -= UInt64(chunk.count)
        }
        guard Array(hasher.finalize()) == head.hash else { throw ContainerError(description: "the section \(head.kind) is damaged (its hash differs)") }
        good = true
    }

    func skip(_ head: Head) throws {
        let position = try handle.offset()
        try handle.seek(toOffset: position + head.length)
    }
}

/// A `.joim`: a model with what was decided on it. Only its VRM is of use to the app (the toggles come from the server's `BodyInfo`).
public enum JoimFile {
    public static let magic: UInt32 = 0x1DEA1A10
    public static let supportedMajor = 1

    public struct Contents: Sendable {
        public let meta: JSONValue?
        public let hasModel: Bool
    }

    /// Reads the file; its model is copied into `model` (checked against its hash).
    @discardableResult
    public static func read(_ file: URL, model: URL) throws -> Contents {
        let stream = try SectionStream(file: file, magic: magic, name: "Joi model file", supportedMajor: supportedMajor, profile: true)
        var meta: JSONValue?
        var hasModel = false
        while let head = try stream.next() {
            switch head.kind {
            case "META":
                meta = try CBOR.decode(try stream.data(head))
            case "VRM " where !hasModel:
                try stream.copy(head, to: model)
                hasModel = true
            default:
                try stream.skip(head)
            }
        }
        return Contents(meta: meta, hasModel: hasModel)
    }
}

/// One step of an animation: a clip, played by itself, as `joia.md` says.
public struct JoiaStep: Codable, Hashable, Sendable {
    public var clip: String
    public var loop: Bool?
    public var speed: Double?
    public var inPlace: Bool?
    public var reverse: Bool?
    /// A unit quaternion `[x, y, z, w]`.
    public var rotation: [Double]?
    /// An offset `[x, y, z]` in metres, in the model's space, after the rotation.
    public var translation: [Double]?
    /// Seconds of crossfade from the step before.
    public var blend: Double?

    public init(clip: String, loop: Bool? = nil, speed: Double? = nil, inPlace: Bool? = nil, reverse: Bool? = nil, rotation: [Double]? = nil,
                translation: [Double]? = nil, blend: Double? = nil) {
        self.clip = clip
        self.loop = loop
        self.speed = speed
        self.inPlace = inPlace
        self.reverse = reverse
        self.rotation = rotation
        self.translation = translation
        self.blend = blend
    }
}

public struct JoiaAnimation: Codable, Hashable, Sendable {
    public var uuid: String
    public var name: String?
    public var loop: Bool?
    public var steps: [JoiaStep]?
    /// How her eyes are while it plays: `closed`, `half` or `wide`; none for her own.
    public var eyes: String?
    /// The face it shows: `happy`, `sad`, `angry`, `relaxed` or `surprised`; none for her mood's.
    public var expression: String?

    public init(uuid: String, name: String? = nil, loop: Bool? = nil, steps: [JoiaStep]? = nil, eyes: String? = nil, expression: String? = nil) {
        self.uuid = uuid
        self.name = name
        self.loop = loop
        self.steps = steps
        self.eyes = eyes
        self.expression = expression
    }
}

public struct JoiaClip: Codable, Hashable, Sendable {
    public var uuid: String
    public var name: String?
    /// Seconds.
    public var duration: Double?

    public init(uuid: String, name: String? = nil, duration: Double? = nil) {
        self.uuid = uuid
        self.name = name
        self.duration = duration
    }
}

/// A `.joia`: animations with their clips.
public enum JoiaFile {
    public static let magic: UInt32 = 0x1DEA1AC7
    public static let supportedMajor = 1

    public struct Contents: Sendable {
        public let animations: [JoiaAnimation]
        public let clips: [JoiaClip]

        public init(animations: [JoiaAnimation], clips: [JoiaClip]) {
            self.animations = animations
            self.clips = clips
        }
    }

    /// Reads the file; the clip with the index (the order of the metadata) is copied to the file `clipFile` gives for it, or skipped for nil.
    public static func read(_ file: URL, clipFile: (Int, JoiaClip) -> URL?) throws -> Contents {
        let stream = try SectionStream(file: file, magic: magic, name: "Joi animations file", supportedMajor: supportedMajor, profile: false)
        var animations: [JoiaAnimation] = []
        var clips: [JoiaClip] = []
        var haveMeta = false
        var index = 0
        while let head = try stream.next() {
            switch head.kind {
            case "META" where !haveMeta:
                let meta = try CBOR.decode(try stream.data(head))
                guard case .object(let members) = meta else { throw ContainerError(description: "the metadata is not an object") }
                let decoder = JSONDecoder()
                func list<T: Decodable>(_ key: String, _ type: T.Type) throws -> [T] {
                    guard let value = members[key], value != .null else { return [] }
                    return try decoder.decode([T].self, from: JSONEncoder().encode(value))
                }
                animations = try list("animations", JoiaAnimation.self)
                clips = try list("clips", JoiaClip.self)
                haveMeta = true
            case "VRMA":
                guard haveMeta else { throw ContainerError(description: "a clip before the metadata") }
                guard index < clips.count else { throw ContainerError(description: "more clips than the metadata names") }
                if let destination = clipFile(index, clips[index]) {
                    try stream.copy(head, to: destination)
                } else {
                    try stream.skip(head)
                }
                index += 1
            default:
                try stream.skip(head)
            }
        }
        guard haveMeta else { throw ContainerError(description: "the file has no metadata") }
        guard index == clips.count else { throw ContainerError(description: "fewer clips than the metadata names") }
        return Contents(animations: animations, clips: clips)
    }
}
