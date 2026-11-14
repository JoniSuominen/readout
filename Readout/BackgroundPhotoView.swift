import ImageIO
import SwiftUI
import UIKit

/// Prepares a picked photo for a widget: scales it down, then bakes the
/// chosen crop into a picture of the widget's own size. iOS refuses widget
/// images much larger than the widget, so the widget never loads the original.
enum BackgroundImageEditor {
    /// Longest side kept from the picked photo, enough to zoom in a few times.
    static let maxSourcePixels = 2048

    /// The picked photo, upright and scaled down.
    static func downsampled(_ data: Data) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary) else {
            return nil
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxSourcePixels
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return UIImage(cgImage: image, scale: 1, orientation: .up)
    }

    /// Pixel size of the baked picture: the widget at 3x, or 2x for the
    /// iPad-only size, which keeps every size near one megapixel.
    static func renderSize(for canvas: WidgetCanvas) -> CGSize {
        let scale: CGFloat = canvas == .extraLarge ? 2 : 3
        return CGSize(width: canvas.previewSize.width * scale, height: canvas.previewSize.height * scale)
    }

    /// The cropped picture and its file extension. A photo that does not
    /// cover the widget keeps transparent edges, so it is saved as PNG.
    static func render(_ image: UIImage, crop: BackgroundCrop, canvas: WidgetCanvas) -> (data: Data, fileExtension: String)? {
        let size = renderSize(for: canvas)
        let rect = crop.imageRect(imageSize: image.size, in: size)
        let covers = rect.minX <= 0.5 && rect.minY <= 0.5 && rect.maxX >= size.width - 0.5 && rect.maxY >= size.height - 0.5
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = covers
        format.preferredRange = .standard
        let rendered = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: rect)
        }
        if covers, let data = rendered.jpegData(compressionQuality: 0.85) {
            return (data, "jpg")
        }
        guard let data = rendered.pngData() else { return nil }
        return (data, "png")
    }

    /// Writes the cropped picture, and the source photo when it is new.
    /// Returns nil, leaving no files behind, when either cannot be written.
    static func save(
        _ image: UIImage,
        existingSourceFile: String?,
        crop: BackgroundCrop,
        canvas: WidgetCanvas,
        opacity: Double
    ) -> WidgetBackgroundImage? {
        let crop = crop.clamped(imageSize: image.size, frame: canvas.previewSize)
        guard let rendered = render(image, crop: crop, canvas: canvas) else { return nil }
        let renderedFile = "\(UUID().uuidString).\(rendered.fileExtension)"
        guard WidgetBackgroundStore.write(rendered.data, named: renderedFile) else { return nil }

        let sourceFile: String
        if let existingSourceFile {
            sourceFile = existingSourceFile
        } else {
            sourceFile = "\(UUID().uuidString)-source.jpg"
            guard let data = image.jpegData(compressionQuality: 0.9), WidgetBackgroundStore.write(data, named: sourceFile) else {
                WidgetBackgroundStore.delete(renderedFile)
                return nil
            }
        }
        return WidgetBackgroundImage(sourceFile: sourceFile, renderedFile: renderedFile, crop: crop, opacity: opacity)
    }
}

/// One run of the photo sheet: a newly picked photo, or the saved one.
struct BackgroundPhotoSession: Identifiable {
    let id = UUID()
    let image: UIImage
    /// Nil for a newly picked photo that is not saved yet.
    let sourceFile: String?
    let crop: BackgroundCrop
    let opacity: Double
}

/// Positions a photo behind a layout. The widget is drawn as it will look,
/// tiles included: drag to move the photo, pinch or use the slider to scale it.
struct BackgroundPhotoView: View {
    let session: BackgroundPhotoSession
    let design: WidgetDesign
    let metrics: [DashboardMetric]
    let context: TileContext
    let onDone: (BackgroundCrop, Double) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var crop: BackgroundCrop
    @State private var opacity: Double
    @State private var dragStart: BackgroundCrop?
    @State private var zoomStart: Double?

    init(
        session: BackgroundPhotoSession,
        design: WidgetDesign,
        metrics: [DashboardMetric],
        context: TileContext,
        onDone: @escaping (BackgroundCrop, Double) -> Void
    ) {
        self.session = session
        self.design = design
        self.metrics = metrics
        self.context = context
        self.onDone = onDone
        _crop = State(initialValue: session.crop.clamped(imageSize: session.image.size, frame: design.canvas.previewSize))
        _opacity = State(initialValue: WidgetBackgroundImage.clampedOpacity(session.opacity))
    }

    private var image: UIImage { session.image }
    private var widgetSize: CGSize { design.canvas.previewSize }
    private var minZoom: Double { BackgroundCrop.minZoom(imageSize: image.size, frame: widgetSize) }

    var body: some View {
        NavigationStack {
            GeometryReader { proxy in
                // The preview stays outside the scroll view so that dragging
                // the photo never scrolls the sheet.
                VStack(spacing: 0) {
                    preview(scale: previewScale(in: proxy.size))
                        .frame(maxWidth: .infinity)
                        .padding()
                    ScrollView {
                        controls.padding([.horizontal, .bottom])
                    }
                }
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Background photo")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        onDone(crop, opacity)
                        dismiss()
                    }
                }
            }
        }
    }

    /// As large as fits the sheet's width and about half its height.
    private func previewScale(in available: CGSize) -> CGFloat {
        let byWidth = (available.width - 32) / widgetSize.width
        let byHeight = (available.height * 0.5) / widgetSize.height
        return max(0.4, min(byWidth, byHeight, 1.8))
    }

    private func preview(scale: CGFloat) -> some View {
        let frame = CGSize(width: widgetSize.width * scale, height: widgetSize.height * scale)
        let rect = crop.imageRect(imageSize: image.size, in: frame)
        let shape = RoundedRectangle(cornerRadius: 22 * scale, style: .continuous)
        var tileContext = context
        tileContext.scale = scale
        return ZStack(alignment: .topLeading) {
            Image(uiImage: image)
                .resizable()
                .frame(width: rect.width, height: rect.height)
                .offset(x: rect.minX, y: rect.minY)
                .opacity(opacity)
        }
        .frame(width: frame.width, height: frame.height, alignment: .topLeading)
        .background { design.appearance.previewBackground }
        .overlay {
            TileGridView(design: design, canvas: design.canvas, metrics: metrics, context: tileContext)
                .padding(WidgetCanvas.contentMargin * scale)
                .widgetAppearance(design.appearance)
                .allowsHitTesting(false)
        }
        .clipShape(shape)
        .overlay(shape.stroke(Color.secondary.opacity(0.25)))
        .contentShape(shape)
        .gesture(
            DragGesture()
                .onChanged { value in
                    let start = dragStart ?? crop
                    dragStart = start
                    var next = crop
                    next.offsetX = start.offsetX + Double(value.translation.width / frame.width)
                    next.offsetY = start.offsetY + Double(value.translation.height / frame.height)
                    crop = next.clamped(imageSize: image.size, frame: widgetSize)
                }
                .onEnded { _ in dragStart = nil }
        )
        .simultaneousGesture(
            MagnifyGesture()
                .onChanged { value in
                    let start = zoomStart ?? crop.zoom
                    zoomStart = start
                    setZoom(start * Double(value.magnification))
                }
                .onEnded { _ in zoomStart = nil }
        )
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Widget preview with the photo behind its tiles")
        .accessibilityHint("Use the controls below to scale the photo.")
    }

    private var controls: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 6) {
                Text("Scale").font(.subheadline.bold())
                Slider(
                    value: Binding(get: { crop.zoom }, set: { setZoom($0) }),
                    in: minZoom...max(minZoom + 0.01, BackgroundCrop.maxZoom)
                ) {
                    Text("Photo scale")
                } minimumValueLabel: {
                    Image(systemName: "minus.magnifyingglass")
                } maximumValueLabel: {
                    Image(systemName: "plus.magnifyingglass")
                }
                HStack {
                    Button("Fill widget") { setZoom(1, recenter: true) }
                    Spacer()
                    Button("Show whole photo") { setZoom(minZoom, recenter: true) }
                }
                .font(.subheadline)
                .buttonStyle(.bordered)
            }
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("Opacity").font(.subheadline.bold())
                    Spacer()
                    Text("\(Int((opacity * 100).rounded()))%")
                        .font(.subheadline)
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                }
                Slider(value: $opacity, in: WidgetBackgroundImage.opacityRange, step: 0.05) {
                    Text("Photo opacity")
                }
            }
            Text("Drag the photo to move it. Pinch or use the slider to scale it. The layout's style shows through a faded photo and around one that does not fill the widget.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func setZoom(_ zoom: Double, recenter: Bool = false) {
        var next = crop
        next.zoom = zoom
        if recenter {
            next.offsetX = 0
            next.offsetY = 0
        }
        crop = next.clamped(imageSize: image.size, frame: widgetSize)
    }
}
