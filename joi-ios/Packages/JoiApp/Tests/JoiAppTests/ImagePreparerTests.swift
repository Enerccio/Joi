import CoreGraphics
import Foundation
import ImageIO
import Testing
import UniformTypeIdentifiers
@testable import JoiApp

enum TestImages {
    /// A PNG of a plain colour in a new temporary file.
    static func png(width: Int, height: Int, orientation: Int? = nil, type: UTType = .png) throws -> URL {
        let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        context.setFillColor(CGColor(red: 0.2, green: 0.5, blue: 0.8, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + (type == .png ? ".png" : ".jpg"))
        let destination = CGImageDestinationCreateWithURL(file as CFURL, type.identifier as CFString, 1, nil)!
        var properties: [CFString: Any] = [:]
        if let orientation { properties[kCGImagePropertyOrientation] = orientation }
        CGImageDestinationAddImage(destination, context.makeImage()!, properties as CFDictionary)
        precondition(CGImageDestinationFinalize(destination))
        return file
    }

    static func size(_ file: URL) -> (width: Int, height: Int) {
        let source = CGImageSourceCreateWithURL(file as CFURL, nil)!
        let p = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as! [CFString: Any]
        return (p[kCGImagePropertyPixelWidth] as! Int, p[kCGImagePropertyPixelHeight] as! Int)
    }
}

@Suite struct ImagePreparerTests {
    @Test func aLargePictureIsScaledToTheLongestSideAndWrittenAsJpeg() throws {
        let file = try TestImages.png(width: 3200, height: 1600)
        defer { try? FileManager.default.removeItem(at: file) }
        let out = try ImagePreparer.prepare(file)
        defer { try? FileManager.default.removeItem(at: out) }
        let size = TestImages.size(out)
        #expect(size.width == 1568 && size.height == 784)
        let head = try Data(contentsOf: out).prefix(2)
        #expect(Array(head) == [0xFF, 0xD8])
    }

    @Test func aSmallPictureKeepsItsSize() throws {
        let file = try TestImages.png(width: 40, height: 20)
        defer { try? FileManager.default.removeItem(at: file) }
        let out = try ImagePreparer.prepare(file)
        defer { try? FileManager.default.removeItem(at: out) }
        let size = TestImages.size(out)
        #expect(size.width == 40 && size.height == 20)
    }

    @Test func thePicturesOrientationTurnsItUpright() throws {
        // orientation 6: stored lying, shown turned a quarter
        let file = try TestImages.png(width: 60, height: 30, orientation: 6, type: .jpeg)
        defer { try? FileManager.default.removeItem(at: file) }
        let out = try ImagePreparer.prepare(file)
        defer { try? FileManager.default.removeItem(at: out) }
        let size = TestImages.size(out)
        #expect(size.width == 30 && size.height == 60)
    }

    @Test func whatIsNoImageIsRefused() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".txt")
        try Data("not an image".utf8).write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        #expect(throws: ImagePreparer.Failure.self) { try ImagePreparer.prepare(file) }
    }
}
