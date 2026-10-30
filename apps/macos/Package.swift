// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "SoundLink", platforms: [.macOS(.v14)], products: [
    .executable(name: "SoundLink", targets: ["SoundLink"]),
    .library(name: "SoundLinkCore", targets: ["SoundLinkCore"])
], dependencies: [
    .package(url: "https://github.com/sparkle-project/Sparkle", exact: "2.10.1")
], targets: [
    .target(name: "SoundLinkCore"),
    .executableTarget(name: "SoundLink", dependencies: ["SoundLinkCore", .product(name: "Sparkle", package: "Sparkle")],
                      linkerSettings: [.unsafeFlags(["-Xlinker", "-rpath", "-Xlinker", "@executable_path/../Frameworks"])]),
    .testTarget(name: "SoundLinkCoreTests", dependencies: ["SoundLinkCore"])
])
