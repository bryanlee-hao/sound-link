import AppKit
// Canonical vector geometry shared with the Android launcher. Build-time only.
let root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
// Xcode generates only Mac assets in its own build directory.
let macOnly = CommandLine.arguments.count == 3 && CommandLine.arguments[1] == "--mac-output"
let output = macOnly ? URL(fileURLWithPath: CommandLine.arguments[2]) : root.appendingPathComponent(".local")
let source = try JSONSerialization.jsonObject(with: Data(contentsOf: root.appendingPathComponent("assets/brand/icon.json"))) as! [String: Any]
let paths = source["paths"] as! [[String: Any]]
let iconset = output.appendingPathComponent("SoundLink.iconset")
try FileManager.default.createDirectory(at: iconset, withIntermediateDirectories: true)
func drawMark(_ ctx: CGContext) {
    for item in paths {
        let p = CGMutablePath()
        for command in item["commands"] as! [[Any]] {
            let v = command.dropFirst().map { ($0 as! NSNumber).doubleValue }
            switch command[0] as! String {
            case "M": p.move(to: CGPoint(x: v[0], y: v[1]))
            case "L": p.addLine(to: CGPoint(x: v[0], y: v[1]))
            case "C": p.addCurve(to: CGPoint(x: v[4], y: v[5]), control1: CGPoint(x: v[0], y: v[1]), control2: CGPoint(x: v[2], y: v[3]))
            default: fatalError("Unsupported icon primitive")
            }
        }
        ctx.setLineWidth(CGFloat((item["width"] as! NSNumber).doubleValue)); ctx.addPath(p); ctx.strokePath()
    }
}
func render(_ pixels: Int) throws {
    let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: pixels, pixelsHigh: pixels, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
    let ctx = NSGraphicsContext.current!.cgContext
    ctx.scaleBy(x: CGFloat(pixels)/1024, y: CGFloat(pixels)/1024)
    ctx.setFillColor(NSColor(srgbRed: 19/255, green: 109/255, blue: 122/255, alpha: 1).cgColor)
    ctx.addPath(CGPath(roundedRect: CGRect(x: 80, y: 80, width: 864, height: 864), cornerWidth: 192, cornerHeight: 192, transform: nil)); ctx.fillPath()
    ctx.translateBy(x: 80, y: 944); ctx.scaleBy(x: 18, y: -18)
    ctx.setStrokeColor(NSColor.white.cgColor); ctx.setLineCap(.round); ctx.setLineJoin(.round)
    drawMark(ctx)
    NSGraphicsContext.restoreGraphicsState()
    let png = bitmap.representation(using: .png, properties: [:])!
    let names: [Int: [String]] = [16:["16x16"],32:["16x16@2x","32x32"],64:["32x32@2x"],128:["128x128"],256:["128x128@2x","256x256"],512:["256x256@2x","512x512"],1024:["512x512@2x"]]
    for name in names[pixels]! { try png.write(to: iconset.appendingPathComponent("icon_\(name).png")) }
    if pixels == 1024 && !macOnly { try png.write(to: root.appendingPathComponent("assets/brand/icon-preview.png")) }
}
for size in [16,32,64,128,256,512,1024] { try render(size) }
if !macOnly {
    let pathData = paths.map { p -> String in
        let commands = (p["commands"] as! [[Any]]).map { c in (c[0] as! String) + c.dropFirst().map { ($0 as! NSNumber).stringValue }.joined(separator: ",") }.joined()
        return "<path android:fillColor=\"@android:color/transparent\" android:strokeColor=\"#FFFFFF\" android:strokeWidth=\"\(p["width"]!)\" android:strokeLineCap=\"round\" android:strokeLineJoin=\"round\" android:pathData=\"\(commands)\"/>"
    }.joined(separator: "\n")
    let res = root.appendingPathComponent("apps/android/app/src/main/res")
    let vector = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" android:width=\"108dp\" android:height=\"108dp\" android:viewportWidth=\"108\" android:viewportHeight=\"108\"><group android:translateX=\"18\" android:translateY=\"18\" android:scaleX=\"1.5\" android:scaleY=\"1.5\">\(pathData)</group></vector>\n"
    try vector.write(to: res.appendingPathComponent("drawable/ic_soundlink_foreground.xml"), atomically: true, encoding: .utf8)
    try "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" android:width=\"24dp\" android:height=\"24dp\" android:viewportWidth=\"48\" android:viewportHeight=\"48\">\(pathData)</vector>\n".write(to: res.appendingPathComponent("drawable/ic_soundlink.xml"), atomically: true, encoding: .utf8)
}

// Monochrome system menu-bar template, retaining the launcher geometry.
// Draw in pixels first, then assign the logical size to avoid Retina double scaling.
let menuImage = NSImage(size: NSSize(width: 18, height: 18))
for scale in [1, 2] {
    let pixels = 18 * scale
    let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: pixels, pixelsHigh: pixels, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
    let ctx = NSGraphicsContext.current!.cgContext
    ctx.scaleBy(x: CGFloat(scale), y: CGFloat(scale))
    ctx.translateBy(x: 9 - 24 * 0.5, y: 9 + 22.75 * 0.5); ctx.scaleBy(x: 0.5, y: -0.5)
    ctx.setStrokeColor(NSColor.black.cgColor); ctx.setLineCap(.round); ctx.setLineJoin(.round)
    drawMark(ctx)
    NSGraphicsContext.restoreGraphicsState()
    bitmap.size = NSSize(width: 18, height: 18)
    menuImage.addRepresentation(bitmap)
    if scale == 2 { try bitmap.representation(using: .png, properties: [:])!.write(to: output.appendingPathComponent("MenuBarIcon-preview.png")) }
}
try menuImage.tiffRepresentation!.write(to: output.appendingPathComponent("MenuBarIcon.tiff"))
