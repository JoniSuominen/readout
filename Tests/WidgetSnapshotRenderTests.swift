import SwiftUI
import XCTest
@testable import Readout

/// Renders Home Screen layouts to PNG files for design review. CI sets
/// TEST_RUNNER_WIDGET_SNAPSHOT_DIR (passed to tests as WIDGET_SNAPSHOT_DIR);
/// without it these tests are skipped.
final class WidgetSnapshotRenderTests: XCTestCase {
    @MainActor
    func testRenderLayoutPreviews() throws {
        guard let path = ProcessInfo.processInfo.environment["WIDGET_SNAPSHOT_DIR"], !path.isEmpty else {
            throw XCTSkip("Set TEST_RUNNER_WIDGET_SNAPSHOT_DIR to render widget previews.")
        }
        let directory = URL(fileURLWithPath: path, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        let styled: [(String, WidgetDesign)] = WidgetAppearance.allCases.map { appearance in
            var design = WidgetDesign.starter(for: .medium)
            design.appearance = appearance
            return ("style-\(appearance.rawValue)", design)
        }
        let samples: [(String, WidgetDesign)] = WidgetDesign.starters.map { ("starter", $0) } + [
            ("formats", Self.formatsDesign),
            ("dense", Self.denseSmallDesign)
        ] + styled
        for (name, design) in samples {
            for scheme in [ColorScheme.light, .dark] {
                let renderer = ImageRenderer(content: SnapshotFrame(design: design).environment(\.colorScheme, scheme))
                renderer.scale = 3
                let image = try XCTUnwrap(renderer.uiImage, "Could not render \(design.canvas) \(name)")
                let file = directory.appendingPathComponent("\(design.canvas.rawValue)-\(name)-\(scheme == .dark ? "dark" : "light").png")
                try XCTUnwrap(image.pngData()).write(to: file)
            }
        }
    }

    /// Shows how one metric looks at each tile size and style.
    private static let formatsDesign = WidgetDesign(id: "formats", name: "Formats", canvas: .medium, tiles: [
        WidgetTile(metricID: "steps", column: 0, row: 0, width: 2, height: 2),
        WidgetTile(metricID: "uv", column: 2, row: 0, width: 1, height: 2),
        WidgetTile(metricID: "temperature", column: 3, row: 0),
        WidgetTile(metricID: "rain", column: 3, row: 1, style: .valueOnly),
        WidgetTile(metricID: "nextEvent", column: 0, row: 2, width: 4),
        WidgetTile(metricID: "tomorrow", column: 0, row: 3, width: 2),
        WidgetTile(metricID: "heart", column: 2, row: 3),
        WidgetTile(metricID: WidgetTile.syncID, column: 3, row: 3)
    ])

    /// Eight one-cell tiles in a small widget.
    private static let denseSmallDesign = WidgetDesign(id: "dense", name: "Dense", canvas: .small, tiles: [
        WidgetTile(metricID: "temperature", column: 0, row: 0), WidgetTile(metricID: "uv", column: 1, row: 0),
        WidgetTile(metricID: "rain", column: 0, row: 1), WidgetTile(metricID: "tomorrow", column: 1, row: 1),
        WidgetTile(metricID: "steps", column: 0, row: 2), WidgetTile(metricID: "sleep", column: 1, row: 2),
        WidgetTile(metricID: "nextEvent", column: 0, row: 3), WidgetTile(metricID: WidgetTile.syncID, column: 1, row: 3)
    ])
}

/// A widget on a neutral backdrop, drawn with the same grid and margins as
/// the Home Screen widget.
private struct SnapshotFrame: View {
    let design: WidgetDesign

    var body: some View {
        let size = design.canvas.previewSize
        let now = Date.now
        let metrics = WidgetPrivacy.render(DashboardMetric.defaults, mode: .showValues)
        TileGridView(
            design: design,
            canvas: design.canvas,
            metrics: metrics,
            context: TileContext(
                now: now,
                freshness: DashboardFreshness(
                    metrics: metrics,
                    updatedAtBySource: Dictionary(uniqueKeysWithValues: DataSource.allCases.map { ($0, now.addingTimeInterval(-3 * 60)) }),
                    errorsBySource: [:]
                ),
                privacyMode: .showValues,
                connectedSources: Set(DataSource.allCases),
                isInteractive: false
            )
        )
        .padding(WidgetCanvas.contentMargin)
        .frame(width: size.width, height: size.height)
        .background { design.appearance.previewBackground.clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous)) }
        .widgetAppearance(design.appearance)
        .padding(16)
        .background(Color(.systemGray4))
    }
}
