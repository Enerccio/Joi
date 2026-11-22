// swift-tools-version: 6.0
import PackageDescription

// The protocol of Joi in Swift. Sources/JoiProtocol/Generated and Tests/JoiProtocolTests/Generated are written by
// SwiftGenerator (joi-protocol) from the JSON Schemas: `mvn -o -Pswift process-test-classes -pl joi-protocol`.
// The other files are written by hand.
let package = Package(
    name: "JoiProtocol",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "JoiProtocol", targets: ["JoiProtocol"]),
    ],
    targets: [
        .target(name: "JoiProtocol"),
        .testTarget(name: "JoiProtocolTests", dependencies: ["JoiProtocol"]),
    ]
)
