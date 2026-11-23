import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// A photo the user picked in the library, copied to a temporary file (the picker hands it over as a file, never as a block of memory).
struct PickedImage: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .image) { received in
            // the file the system gives is gone after this call: keep a copy
            let copy = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString + "." + (received.file.pathExtension.isEmpty ? "img" : received.file.pathExtension))
            try FileManager.default.copyItem(at: received.file, to: copy)
            return PickedImage(url: copy)
        }
    }
}

#if canImport(UIKit)
import UIKit

/// The camera: one photo, written to a temporary JPEG file.
struct CameraPicker: UIViewControllerRepresentable {
    let onPicked: (URL?) -> Void

    static var isAvailable: Bool { UIImagePickerController.isSourceTypeAvailable(.camera) }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPicked: onPicked) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onPicked: (URL?) -> Void

        init(onPicked: @escaping (URL?) -> Void) { self.onPicked = onPicked }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            var url: URL?
            if let image = info[.originalImage] as? UIImage, let data = image.jpegData(compressionQuality: 0.95) {
                // the camera's own orientation is in the image: ImagePreparer turns it upright from the file's EXIF, which jpegData keeps
                let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
                if (try? data.write(to: file)) != nil { url = file }
            }
            picker.dismiss(animated: true) { self.onPicked(url) }
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            picker.dismiss(animated: true) { self.onPicked(nil) }
        }
    }
}
#endif

/// The button that adds an image to the message: take a photo, or choose one in the library. What was added is `images` (files), at most
/// `limit` in all.
struct ImageAttachButton: View {
    @Binding var images: [URL]
    let limit: Int
    @State private var choosing = false
    @State private var camera = false
    @State private var picked: [PhotosPickerItem] = []

    var body: some View {
        Menu {
            #if canImport(UIKit)
            if CameraPicker.isAvailable {
                Button("Take a photo", systemImage: "camera") { camera = true }
            }
            #endif
            Button("Choose from the library", systemImage: "photo.on.rectangle") { choosing = true }
        } label: {
            Image(systemName: "photo.badge.plus")
        }
        .disabled(images.count >= limit)
        .accessibilityLabel("Attach an image")
        .photosPicker(isPresented: $choosing, selection: $picked, maxSelectionCount: max(1, limit - images.count), matching: .images)
        .onChange(of: picked) {
            let items = picked
            picked = []
            Task {
                for item in items {
                    if let image = try? await item.loadTransferable(type: PickedImage.self), images.count < limit {
                        images.append(image.url)
                    }
                }
            }
        }
        #if canImport(UIKit)
        .fullScreenCover(isPresented: $camera) {
            CameraPicker { url in
                camera = false
                if let url, images.count < limit { images.append(url) }
            }
            .ignoresSafeArea()
        }
        #endif
    }
}

/// The images waiting to be sent, as small pictures with a button to take one away.
struct PendingImages: View {
    @Binding var images: [URL]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(images, id: \.self) { url in
                    thumbnail(url)
                        .frame(width: 56, height: 56)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .overlay(alignment: .topTrailing) {
                            Button {
                                images.removeAll { $0 == url }
                                try? FileManager.default.removeItem(at: url)
                            } label: {
                                Image(systemName: "xmark.circle.fill").symbolRenderingMode(.palette).foregroundStyle(.white, .black.opacity(0.6))
                            }
                            .accessibilityLabel("Remove this image")
                            .padding(2)
                        }
                }
            }
        }
    }

    @ViewBuilder private func thumbnail(_ url: URL) -> some View {
        if let image = Self.small(url) {
            Image(decorative: image, scale: 1).resizable().scaledToFill()
        } else {
            Color.gray.opacity(0.3)
        }
    }

    /// A small picture of the file (decoded at that size: a photo is large).
    static func small(_ url: URL) -> CGImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        return CGImageSourceCreateThumbnailAtIndex(source, 0, [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: 160,
        ] as CFDictionary)
    }
}
