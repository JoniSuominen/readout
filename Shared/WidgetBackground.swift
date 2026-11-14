import SwiftUI
import UIKit

/// How a photo sits in the widget: how far it is zoomed and where its centre
/// lies. Stored as fractions so the same crop applies at any drawing size.
struct BackgroundCrop: Codable, Hashable {
    /// Zoom relative to just covering the widget. Below 1 the photo no longer
    /// covers it and the layout's style shows around the photo.
    var zoom: Double = 1
    /// The photo centre's distance from the widget centre, as a fraction of
    /// the widget's width and height.
    var offsetX: Double = 0
    var offsetY: Double = 0

    static let maxZoom = 5.0

    private static func fillScale(imageSize: CGSize, frame: CGSize) -> CGFloat {
        max(frame.width / imageSize.width, frame.height / imageSize.height)
    }

    /// The zoom at which the whole photo is visible.
    static func minZoom(imageSize: CGSize, frame: CGSize) -> Double {
        guard imageSize.width > 0, imageSize.height > 0, frame.width > 0, frame.height > 0 else { return 1 }
        let fit = min(frame.width / imageSize.width, frame.height / imageSize.height)
        return Double(fit / fillScale(imageSize: imageSize, frame: frame))
    }

    /// Where the photo is drawn in a widget of this size.
    func imageRect(imageSize: CGSize, in frame: CGSize) -> CGRect {
        guard imageSize.width > 0, imageSize.height > 0 else { return CGRect(origin: .zero, size: frame) }
        let scale = Self.fillScale(imageSize: imageSize, frame: frame) * CGFloat(zoom)
        let size = CGSize(width: imageSize.width * scale, height: imageSize.height * scale)
        return CGRect(
            x: (frame.width - size.width) / 2 + CGFloat(offsetX) * frame.width,
            y: (frame.height - size.height) / 2 + CGFloat(offsetY) * frame.height,
            width: size.width,
            height: size.height
        )
    }

    /// The nearest crop that keeps a zoomed-in photo covering the widget and
    /// a zoomed-out photo wholly inside it.
    func clamped(imageSize: CGSize, frame: CGSize) -> BackgroundCrop {
        guard imageSize.width > 0, imageSize.height > 0, frame.width > 0, frame.height > 0 else { return BackgroundCrop() }
        var result = self
        let lowest = Self.minZoom(imageSize: imageSize, frame: frame)
        result.zoom = min(max(zoom.isFinite ? zoom : 1, lowest), max(lowest, Self.maxZoom))
        let scale = Self.fillScale(imageSize: imageSize, frame: frame) * CGFloat(result.zoom)
        let limitX = Double(abs(imageSize.width * scale - frame.width) / 2 / frame.width)
        let limitY = Double(abs(imageSize.height * scale - frame.height) / 2 / frame.height)
        result.offsetX = min(max(offsetX.isFinite ? offsetX : 0, -limitX), limitX)
        result.offsetY = min(max(offsetY.isFinite ? offsetY : 0, -limitY), limitY)
        return result
    }
}

/// A photo drawn over a layout's style. The files live in the app group so
/// the widget can read them; the photo never leaves the device.
struct WidgetBackgroundImage: Codable, Hashable {
    /// The picked photo, scaled down, kept so the crop can be changed later.
    var sourceFile: String
    /// The cropped picture the widget draws, already at widget size.
    var renderedFile: String
    var crop: BackgroundCrop
    /// How strongly the photo covers the style beneath it.
    var opacity: Double

    static let defaultOpacity = 0.5
    static let opacityRange = 0.1...1.0

    private enum CodingKeys: String, CodingKey {
        case sourceFile, renderedFile, crop, opacity
    }

    init(sourceFile: String, renderedFile: String, crop: BackgroundCrop = BackgroundCrop(), opacity: Double = WidgetBackgroundImage.defaultOpacity) {
        self.sourceFile = sourceFile
        self.renderedFile = renderedFile
        self.crop = crop
        self.opacity = Self.clampedOpacity(opacity)
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        sourceFile = try container.decode(String.self, forKey: .sourceFile)
        renderedFile = try container.decode(String.self, forKey: .renderedFile)
        crop = (try? container.decodeIfPresent(BackgroundCrop.self, forKey: .crop)) ?? BackgroundCrop()
        opacity = Self.clampedOpacity((try? container.decodeIfPresent(Double.self, forKey: .opacity)) ?? Self.defaultOpacity)
    }

    static func clampedOpacity(_ value: Double) -> Double {
        guard value.isFinite else { return defaultOpacity }
        return min(max(value, opacityRange.lowerBound), opacityRange.upperBound)
    }
}

/// Background photo files in the shared app group.
enum WidgetBackgroundStore {
    private static let cache = NSCache<NSString, UIImage>()

    static var directory: URL? {
        let base = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: DashboardStore.appGroupID)
            ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
        return base?.appendingPathComponent("WidgetBackgrounds", isDirectory: true)
    }

    /// File names come from saved layouts, so one is never allowed to point
    /// outside the backgrounds folder.
    static func url(for fileName: String) -> URL? {
        guard !fileName.isEmpty, !fileName.hasPrefix("."),
              fileName == (fileName as NSString).lastPathComponent else { return nil }
        return directory?.appendingPathComponent(fileName)
    }

    /// The widget draws the same picture for every timeline entry, so decoded
    /// pictures are kept. Pass `cache: false` for the large source photo.
    static func image(named fileName: String, cache useCache: Bool = true) -> UIImage? {
        let key = fileName as NSString
        if useCache, let cached = cache.object(forKey: key) { return cached }
        guard let url = url(for: fileName), let image = UIImage(contentsOfFile: url.path) else { return nil }
        if useCache { cache.setObject(image, forKey: key) }
        return image
    }

    @discardableResult
    static func write(_ data: Data, named fileName: String) -> Bool {
        guard let directory, let url = url(for: fileName) else { return false }
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try data.write(to: url, options: .atomic)
            cache.removeObject(forKey: fileName as NSString)
            return true
        } catch {
            return false
        }
    }

    static func delete(_ fileName: String) {
        cache.removeObject(forKey: fileName as NSString)
        guard let url = url(for: fileName) else { return }
        try? FileManager.default.removeItem(at: url)
    }

    static func delete(_ background: WidgetBackgroundImage?) {
        guard let background else { return }
        delete(background.sourceFile)
        delete(background.renderedFile)
    }

    static func removeAll() {
        cache.removeAllObjects()
        guard let directory else { return }
        try? FileManager.default.removeItem(at: directory)
    }
}

/// A layout's background: its style, with the photo on top when it has one.
struct WidgetBackgroundView: View {
    let appearance: WidgetAppearance
    let photo: WidgetBackgroundImage?
    /// In-app previews stand a transparent widget on a sample wallpaper.
    var isPreview = false

    var body: some View {
        style
            .overlay {
                if let photo, let image = WidgetBackgroundStore.image(named: photo.renderedFile) {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .opacity(photo.opacity)
                        .accessibilityHidden(true)
                }
            }
            .clipped()
    }

    @ViewBuilder
    private var style: some View {
        if isPreview {
            appearance.previewBackground
        } else {
            appearance.background
        }
    }
}
