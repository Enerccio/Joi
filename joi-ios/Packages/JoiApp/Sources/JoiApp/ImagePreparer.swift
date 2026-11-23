import Foundation
import ImageIO
import UniformTypeIdentifiers

/// What the phone does with a photo before it sends it: reads it from its file (never the whole picture in memory: ImageIO decodes a
/// thumbnail), turns it upright by its orientation, scales it to at most `maxSide` pixels on its longest side and writes a JPEG. The
/// server does the same (`DESIGN.md` → Images in the chat), so this only saves the upload; what it cannot read, the server refuses.
public enum ImagePreparer {
    /// The longest side the server keeps (`ChatImages.MAX_SIDE`).
    public static let maxSide = 1568

    public struct Failure: Error, LocalizedError, Equatable {
        public let message: String
        public var errorDescription: String? { message }
    }

    /// Writes the prepared JPEG to a new file in the temporary folder and returns it; the caller deletes it after the upload.
    public static func prepare(_ file: URL, maxSide: Int = ImagePreparer.maxSide, quality: Double = 0.88) throws -> URL {
        guard let source = CGImageSourceCreateWithURL(file as CFURL, nil) else {
            throw Failure(message: "This is not an image.")
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true, // upright
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxSide,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            throw Failure(message: "The image can't be read.")
        }
        let target = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
        guard let destination = CGImageDestinationCreateWithURL(target as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else {
            throw Failure(message: "The image can't be written.")
        }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else {
            try? FileManager.default.removeItem(at: target)
            throw Failure(message: "The image can't be written.")
        }
        return target
    }
}
