import Foundation
import JoiClient
import JoiProtocol

/// Her body as the renderer reads it: the VRM out of her `.joim` and the clips out of her `.joia`, unpacked once into a folder of the cache
/// named by the files' hashes, with the animations of the `.joia`. Either file may be missing (no model, or no animations).
public struct BodyFiles: Sendable {
    public let model: URL?
    public let animations: [String: JoiaAnimation]
    public let clips: [String: URL]
    /// A clip's length in seconds by its uuid, for the clips that say it.
    public let lengths: [String: Double]

    public init(model: URL? = nil, animations: [String: JoiaAnimation] = [:], clips: [String: URL] = [:], lengths: [String: Double] = [:]) {
        self.model = model
        self.animations = animations
        self.clips = clips
        self.lengths = lengths
    }

    /// No body: the renderer shows nothing.
    public static let none = BodyFiles()

    private static let done = ".unpacked"

    /// Unpacks her files into `cache` (left as they are when an earlier run unpacked them).
    ///
    /// - Parameters:
    ///   - joim: her model's file, nil for none.
    ///   - joia: her animations' file, nil for none.
    public static func unpack(joim: URL?, joia: URL?, into cache: URL) throws -> BodyFiles {
        let fm = FileManager.default
        var model: URL?
        if let joim {
            let dir = cache.appendingPathComponent(stem(joim))
            model = dir.appendingPathComponent("model.vrm")
            if !fm.fileExists(atPath: dir.appendingPathComponent(done).path) {
                try fm.createDirectory(at: dir, withIntermediateDirectories: true)
                let contents = try JoimFile.read(joim, model: model!)
                guard contents.hasModel else { throw ContainerError(description: "her model's file holds no model") }
                fm.createFile(atPath: dir.appendingPathComponent(done).path, contents: nil)
            }
        }
        var animations: [String: JoiaAnimation] = [:]
        var clips: [String: URL] = [:]
        var lengths: [String: Double] = [:]
        if let joia {
            let dir = cache.appendingPathComponent(stem(joia))
            let unpacked = fm.fileExists(atPath: dir.appendingPathComponent(done).path)
            try fm.createDirectory(at: dir, withIntermediateDirectories: true)
            let contents = try JoiaFile.read(joia) { index, _ in unpacked ? nil : dir.appendingPathComponent("\(index).vrma") }
            for (index, clip) in contents.clips.enumerated() {
                clips[clip.uuid] = dir.appendingPathComponent("\(index).vrma")
                if let duration = clip.duration { lengths[clip.uuid] = duration }
            }
            for animation in contents.animations { animations[animation.uuid] = animation }
            if !unpacked { fm.createFile(atPath: dir.appendingPathComponent(done).path, contents: nil) }
        }
        return BodyFiles(model: model, animations: animations, clips: clips, lengths: lengths)
    }

    /// `<hash>.joim` is unpacked into `<hash>-joim`.
    private static func stem(_ file: URL) -> String {
        let ext = file.pathExtension
        return file.deletingPathExtension().lastPathComponent + "-" + (ext.isEmpty ? "bin" : ext)
    }

    /// The files that are not of the bodies kept, deleted: the cache holds hashes of what she wore before.
    public static func clean(_ cache: URL, keeping keep: Set<String>) {
        let fm = FileManager.default
        guard let names = try? fm.contentsOfDirectory(atPath: cache.path) else { return }
        for name in names where !keep.contains(name) && (name.hasSuffix("-joim") || name.hasSuffix("-joia") || name.hasSuffix(".joim") || name.hasSuffix(".joia")) {
            try? fm.removeItem(at: cache.appendingPathComponent(name))
        }
    }
}
