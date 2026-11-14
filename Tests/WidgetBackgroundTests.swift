import UIKit
import XCTest
@testable import Readout

final class WidgetBackgroundTests: XCTestCase {
    private let wide = CGSize(width: 400, height: 200)
    private let square = CGSize(width: 100, height: 100)

    private func samplePhoto(_ size: CGSize) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor.systemTeal.setFill()
            context.fill(CGRect(origin: .zero, size: size))
        }
    }

    func testCropFillsTheWidgetByDefaultAndFitsTheWholePhotoAtItsLowestZoom() {
        let filled = BackgroundCrop().imageRect(imageSize: wide, in: square)
        XCTAssertEqual(filled, CGRect(x: -50, y: 0, width: 200, height: 100))

        let lowest = BackgroundCrop.minZoom(imageSize: wide, frame: square)
        XCTAssertEqual(lowest, 0.5, accuracy: 0.0001)
        let fitted = BackgroundCrop(zoom: lowest).imageRect(imageSize: wide, in: square)
        XCTAssertEqual(fitted, CGRect(x: 0, y: 25, width: 100, height: 50))

        // Offsets are fractions of the widget, so a crop scales with it.
        let moved = BackgroundCrop(zoom: 1, offsetX: 0.25, offsetY: 0)
        XCTAssertEqual(moved.imageRect(imageSize: wide, in: CGSize(width: 300, height: 300)).minX, -75, accuracy: 0.0001)
    }

    func testClampKeepsAZoomedPhotoCoveringAndAFittedPhotoInside() {
        let covering = BackgroundCrop(zoom: 1, offsetX: 2, offsetY: 1).clamped(imageSize: wide, frame: square)
        XCTAssertEqual(covering.offsetX, 0.5, accuracy: 0.0001, "the photo's left edge stops at the widget's left edge")
        XCTAssertEqual(covering.offsetY, 0, accuracy: 0.0001)

        let inside = BackgroundCrop(zoom: 0.5, offsetX: 1, offsetY: -1).clamped(imageSize: wide, frame: square)
        XCTAssertEqual(inside.offsetX, 0, accuracy: 0.0001)
        XCTAssertEqual(inside.offsetY, -0.25, accuracy: 0.0001, "the photo's top edge stops at the widget's top edge")

        XCTAssertEqual(BackgroundCrop(zoom: 99).clamped(imageSize: wide, frame: square).zoom, BackgroundCrop.maxZoom)
        XCTAssertEqual(BackgroundCrop(zoom: 0.01).clamped(imageSize: wide, frame: square).zoom, 0.5, accuracy: 0.0001)
        XCTAssertEqual(BackgroundCrop(zoom: .nan, offsetX: .infinity).clamped(imageSize: wide, frame: square), BackgroundCrop())
        XCTAssertEqual(BackgroundCrop(zoom: 3).clamped(imageSize: .zero, frame: square), BackgroundCrop())
    }

    func testLayoutsLoadWithoutAPhotoAndKeepAChosenOne() throws {
        let legacy = Data(#"{"id":"old","name":"Old","canvas":"small","tiles":[]}"#.utf8)
        XCTAssertNil(try JSONDecoder().decode(WidgetDesign.self, from: legacy).backgroundImage)

        var design = WidgetDesign(id: "photo", name: "Photo", canvas: .medium, tiles: [])
        design.backgroundImage = WidgetBackgroundImage(
            sourceFile: "a-source.jpg", renderedFile: "a.jpg",
            crop: BackgroundCrop(zoom: 2, offsetX: 0.1, offsetY: -0.2), opacity: 0.35
        )
        XCTAssertEqual(try JSONDecoder().decode(WidgetDesign.self, from: JSONEncoder().encode(design)), design)

        // A damaged photo entry costs the photo, not the layout.
        let damaged = Data(#"{"id":"x","name":"X","canvas":"small","tiles":[],"backgroundImage":{"opacity":"high"}}"#.utf8)
        let loaded = try JSONDecoder().decode(WidgetDesign.self, from: damaged)
        XCTAssertEqual(loaded.id, "x")
        XCTAssertNil(loaded.backgroundImage)

        let extreme = Data(#"{"sourceFile":"s.jpg","renderedFile":"r.jpg","opacity":7}"#.utf8)
        let clamped = try JSONDecoder().decode(WidgetBackgroundImage.self, from: extreme)
        XCTAssertEqual(clamped.opacity, 1)
        XCTAssertEqual(clamped.crop, BackgroundCrop())
    }

    func testFileNamesCannotLeaveTheBackgroundsFolder() {
        XCTAssertNil(WidgetBackgroundStore.url(for: "../secrets.jpg"))
        XCTAssertNil(WidgetBackgroundStore.url(for: "nested/photo.jpg"))
        XCTAssertNil(WidgetBackgroundStore.url(for: ".hidden"))
        XCTAssertNil(WidgetBackgroundStore.url(for: ""))
        XCTAssertEqual(WidgetBackgroundStore.url(for: "photo.jpg")?.lastPathComponent, "photo.jpg")
        XCTAssertFalse(WidgetBackgroundStore.write(Data([1]), named: "../outside.jpg"))
    }

    func testSavingBakesTheCropAtWidgetSizeAndReplacingKeepsTheSource() throws {
        let photo = samplePhoto(wide)
        let first = try XCTUnwrap(BackgroundImageEditor.save(
            photo, existingSourceFile: nil, crop: BackgroundCrop(zoom: 2, offsetX: 9, offsetY: 0), canvas: .small, opacity: 0.4
        ))
        defer { WidgetBackgroundStore.delete(first) }
        XCTAssertEqual(first.opacity, 0.4)
        XCTAssertEqual(first.crop.zoom, 2)
        XCTAssertLessThan(first.crop.offsetX, 9, "the saved crop is clamped")
        XCTAssertTrue(first.renderedFile.hasSuffix(".jpg"), "a photo that covers the widget needs no transparency")

        let expected = BackgroundImageEditor.renderSize(for: .small)
        let rendered = try XCTUnwrap(WidgetBackgroundStore.image(named: first.renderedFile))
        XCTAssertEqual(CGSize(width: rendered.size.width * rendered.scale, height: rendered.size.height * rendered.scale), expected)
        XCTAssertNotNil(WidgetBackgroundStore.image(named: first.sourceFile, cache: false))

        // Showing the whole photo leaves transparent bands, and reuses the source.
        let source = try XCTUnwrap(WidgetBackgroundStore.image(named: first.sourceFile, cache: false))
        let lowest = BackgroundCrop.minZoom(imageSize: source.size, frame: WidgetCanvas.small.previewSize)
        let second = try XCTUnwrap(BackgroundImageEditor.save(
            source, existingSourceFile: first.sourceFile, crop: BackgroundCrop(zoom: lowest), canvas: .small, opacity: 1
        ))
        XCTAssertEqual(second.sourceFile, first.sourceFile)
        XCTAssertNotEqual(second.renderedFile, first.renderedFile)
        XCTAssertTrue(second.renderedFile.hasSuffix(".png"))

        WidgetBackgroundStore.delete(second)
        XCTAssertNil(WidgetBackgroundStore.image(named: second.renderedFile))
        XCTAssertNil(WidgetBackgroundStore.image(named: first.sourceFile, cache: false))
    }

    func testEveryWidgetSizeBakesToAboutOneMegapixel() {
        for canvas in WidgetCanvas.allCases {
            let size = BackgroundImageEditor.renderSize(for: canvas)
            XCTAssertLessThanOrEqual(size.width * size.height, 1_100_000, "\(canvas)")
            XCTAssertEqual(size.width / size.height, canvas.previewSize.width / canvas.previewSize.height, accuracy: 0.0001)
        }
    }
}
