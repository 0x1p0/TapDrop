// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "TapDrop",
    platforms: [.macOS(.v15)],
    targets: [
        .executableTarget(name: "TapDrop", path: "Sources/TapDrop")
    ],
    swiftLanguageModes: [.v5]
)
