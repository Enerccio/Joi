// swift-tools-version: 6.0
import PackageDescription

// The iOS app's own code: the models (pairing, the chat) and the SwiftUI views. The Xcode project is only a shell around `RootView`, so
// all of it builds and is tested on the Mac with `swift test`.
let package = Package(
    name: "JoiApp",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "JoiApp", targets: ["JoiApp"]),
    ],
    dependencies: [
        .package(path: "../JoiProtocol"),
        .package(path: "../JoiClient"),
    ],
    targets: [
        .target(name: "JoiApp", dependencies: [
            .product(name: "JoiProtocol", package: "JoiProtocol"),
            .product(name: "JoiClient", package: "JoiClient"),
        ]),
        .testTarget(name: "JoiAppTests", dependencies: ["JoiApp"]),
    ]
)
