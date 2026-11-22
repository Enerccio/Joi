// swift-tools-version: 6.0
import PackageDescription

// The client of a Joi server for the iOS app: the HTTP API with the pairing (OAuth device grant) and the node WebSocket.
// No UI and no platform beyond Foundation (and the Keychain for the credentials), so `swift test` runs it on the Mac.
let package = Package(
    name: "JoiClient",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "JoiClient", targets: ["JoiClient"]),
    ],
    dependencies: [
        .package(path: "../JoiProtocol"),
    ],
    targets: [
        .target(name: "JoiClient", dependencies: [.product(name: "JoiProtocol", package: "JoiProtocol")]),
        .testTarget(name: "JoiClientTests", dependencies: ["JoiClient"]),
    ]
)
