import CoreGraphics
import Foundation

/// A Home Screen widget size, divided into a grid of tile cells. Rows are
/// about 27 pt tall in every size, enough for one line of icon and value.
enum WidgetCanvas: String, Codable, CaseIterable, Identifiable {
    case small
    case medium
    case large
    /// iPad only.
    case extraLarge

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .small: return "Small"
        case .medium: return "Medium"
        case .large: return "Large"
        case .extraLarge: return "Extra large"
        }
    }

    var columns: Int {
        switch self {
        case .small: return 2
        case .medium, .large: return 4
        case .extraLarge: return 8
        }
    }

    var rows: Int { self == .large || self == .extraLarge ? 10 : 4 }

    /// iPhones have no extra large widgets.
    var isPadOnly: Bool { self == .extraLarge }

    /// Widget size on a 6.1-inch iPhone, used for in-app previews.
    var previewSize: CGSize {
        switch self {
        case .small: return CGSize(width: 158, height: 158)
        case .medium: return CGSize(width: 338, height: 158)
        case .large: return CGSize(width: 338, height: 354)
        case .extraLarge: return CGSize(width: 715, height: 354)
        }
    }

    static let contentMargin: CGFloat = 16
    static let tileSpacing: CGFloat = 6
}

enum TileStyle: String, Codable, CaseIterable, Identifiable {
    /// An icon or short label beside the value.
    case standard
    /// Only the value, for people who know where each tile sits.
    case valueOnly

    var id: String { rawValue }
}

/// One item placed on the widget grid. Column and row are zero-based; width
/// and height count cells. The sync tile refreshes data and shows its age.
struct WidgetTile: Codable, Identifiable, Hashable {
    static let syncID = "sync"

    var id: UUID
    var metricID: String
    var column: Int
    var row: Int
    var width: Int
    var height: Int
    var style: TileStyle

    init(
        id: UUID = UUID(),
        metricID: String,
        column: Int,
        row: Int,
        width: Int = 1,
        height: Int = 1,
        style: TileStyle = .standard
    ) {
        self.id = id
        self.metricID = metricID
        self.column = column
        self.row = row
        self.width = width
        self.height = height
        self.style = style
    }

    var isSync: Bool { metricID == Self.syncID }
    var endColumn: Int { column + width }
    var endRow: Int { row + height }

    func overlaps(_ other: WidgetTile) -> Bool {
        column < other.endColumn && other.column < endColumn &&
            row < other.endRow && other.row < endRow
    }
}

struct WidgetDesign: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var canvas: WidgetCanvas
    var tiles: [WidgetTile]
    var appearance: WidgetAppearance = .system

    private enum CodingKeys: String, CodingKey {
        case id, name, canvas, tiles, appearance
    }

    func fits(_ tile: WidgetTile, in canvas: WidgetCanvas) -> Bool {
        tile.column >= 0 && tile.row >= 0 && tile.width >= 1 && tile.height >= 1 &&
            tile.endColumn <= canvas.columns && tile.endRow <= canvas.rows
    }

    /// True when the tile stays inside this canvas without covering another tile.
    func canPlace(_ tile: WidgetTile) -> Bool {
        fits(tile, in: canvas) && !tiles.contains { $0.id != tile.id && $0.overlaps(tile) }
    }

    /// Every free position for a tile of this size, ignoring the tile's own
    /// current cells.
    func placements(for tile: WidgetTile) -> [WidgetTile] {
        var found: [WidgetTile] = []
        for row in stride(from: 0, through: canvas.rows - tile.height, by: 1) {
            for column in stride(from: 0, through: canvas.columns - tile.width, by: 1) {
                var candidate = tile
                candidate.column = column
                candidate.row = row
                if canPlace(candidate) { found.append(candidate) }
            }
        }
        return found
    }

    /// The free position closest to a wanted top-left corner, given in
    /// fractional cells. `pitch` is one cell plus its gap in points, so a
    /// short row weighs less than a wide column. Nil when nothing free lies
    /// within `maxDistance` points.
    func nearestPlacement(of tile: WidgetTile, column: CGFloat, row: CGFloat, pitch: CGSize, maxDistance: CGFloat) -> WidgetTile? {
        var best: (tile: WidgetTile, distance: CGFloat)?
        for candidate in placements(for: tile) {
            let distance = hypot((CGFloat(candidate.column) - column) * pitch.width, (CGFloat(candidate.row) - row) * pitch.height)
            if distance <= maxDistance, distance < (best?.distance ?? .infinity) {
                best = (candidate, distance)
            }
        }
        return best?.tile
    }

    /// The first free spot in a straight line from the tile, jumping over any
    /// tiles in the way. Nil when the line reaches the edge without one.
    func placement(of tile: WidgetTile, stepping columns: Int, _ rows: Int) -> WidgetTile? {
        guard columns != 0 || rows != 0 else { return nil }
        var candidate = tile
        while true {
            candidate.column += columns
            candidate.row += rows
            guard fits(candidate, in: canvas) else { return nil }
            if canPlace(candidate) { return candidate }
        }
    }

    func openSlot(width: Int, height: Int) -> (column: Int, row: Int)? {
        for row in 0..<canvas.rows {
            for column in 0..<canvas.columns {
                let candidate = WidgetTile(metricID: "", column: column, row: row, width: width, height: height)
                if canPlace(candidate) { return (column, row) }
            }
        }
        return nil
    }

    /// Adds a tile in the first open space. Events try a wider tile first so
    /// the start time and title fit on one line; the agenda also tries to be
    /// tall enough for three events.
    @discardableResult
    mutating func addTile(metricID: String) -> WidgetTile? {
        let wide = min(3, canvas.columns)
        let sizes: [(width: Int, height: Int)]
        switch metricID {
        case "nextEvent": sizes = [(wide, 1), (2, 1), (1, 1)]
        case "agenda": sizes = [(wide, 3), (wide, 2), (2, 2), (wide, 1), (2, 1), (1, 1)]
        default: sizes = [(1, 1)]
        }
        for size in sizes {
            if let slot = openSlot(width: size.width, height: size.height) {
                let tile = WidgetTile(metricID: metricID, column: slot.column, row: slot.row, width: size.width, height: size.height)
                tiles.append(tile)
                return tile
            }
        }
        return nil
    }

    /// Applies a moved or resized tile only when it still fits without overlaps.
    @discardableResult
    mutating func update(_ tile: WidgetTile) -> Bool {
        guard let index = tiles.firstIndex(where: { $0.id == tile.id }), canPlace(tile) else { return false }
        tiles[index] = tile
        return true
    }

    /// Resizes as close to the requested size as fits, never below one cell.
    /// Returns the size actually applied.
    @discardableResult
    mutating func resizeTile(id: UUID, width: Int, height: Int) -> WidgetTile? {
        guard let index = tiles.firstIndex(where: { $0.id == id }) else { return nil }
        let tile = tiles[index]
        let maxWidth = min(max(1, width), canvas.columns - tile.column)
        let maxHeight = min(max(1, height), canvas.rows - tile.row)
        guard maxWidth >= 1, maxHeight >= 1 else { return nil }
        for candidateWidth in stride(from: maxWidth, through: 1, by: -1) {
            for candidateHeight in stride(from: maxHeight, through: 1, by: -1) {
                var candidate = tile
                candidate.width = candidateWidth
                candidate.height = candidateHeight
                if canPlace(candidate) {
                    tiles[index] = candidate
                    return candidate
                }
            }
        }
        return nil
    }

    mutating func removeTile(id: UUID) {
        tiles.removeAll { $0.id == id }
    }

    /// A layout chosen for another widget size shows the tiles that fit.
    func visibleTiles(on canvas: WidgetCanvas) -> [WidgetTile] {
        tiles.filter { fits($0, in: canvas) }
    }
}

extension WidgetDesign {
    /// Layouts saved before appearances existed have none and stay on System.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        canvas = try container.decode(WidgetCanvas.self, forKey: .canvas)
        tiles = try container.decode([WidgetTile].self, forKey: .tiles)
        appearance = (try? container.decodeIfPresent(WidgetAppearance.self, forKey: .appearance)) ?? .system
    }
}

extension WidgetDesign {
    /// Starter layouts shown until the first edit. Sizes follow common widget
    /// guidance: a few glanceable values in small, more detail in large.
    static let starters: [WidgetDesign] = WidgetCanvas.allCases.map { WidgetDesign.makeStarter($0) }

    static func starter(for canvas: WidgetCanvas) -> WidgetDesign {
        starters.first { $0.canvas == canvas } ?? makeStarter(canvas)
    }

    private static func makeStarter(_ canvas: WidgetCanvas) -> WidgetDesign {
        func tile(_ metricID: String, _ column: Int, _ row: Int, _ width: Int = 1, _ height: Int = 1) -> WidgetTile {
            WidgetTile(metricID: metricID, column: column, row: row, width: width, height: height)
        }
        // Fills all ten rows so no band of empty space is left over.
        let largeTiles = [
            tile("temperature", 0, 0, 2, 3), tile("uv", 2, 0, 2, 3),
            tile("rain", 0, 3, 2, 2), tile("tomorrow", 2, 3, 2, 2),
            tile("nextEvent", 0, 5, 3, 1), tile(WidgetTile.syncID, 3, 5),
            tile("steps", 0, 6, 2, 2), tile("sleep", 2, 6, 2, 2),
            tile("heart", 0, 8, 2, 2), tile("readiness", 2, 8, 2, 2)
        ]
        let tiles: [WidgetTile]
        switch canvas {
        case .small:
            tiles = [
                tile("temperature", 0, 0, 1, 2), tile("uv", 1, 0, 1, 2),
                tile("nextEvent", 0, 2, 2, 1),
                tile("steps", 0, 3), tile(WidgetTile.syncID, 1, 3)
            ]
        case .medium:
            tiles = [
                tile("temperature", 0, 0, 2, 2), tile("uv", 2, 0, 2, 2),
                tile("rain", 0, 2), tile("tomorrow", 1, 2), tile("steps", 2, 2), tile("sleep", 3, 2),
                tile("nextEvent", 0, 3, 3, 1), tile(WidgetTile.syncID, 3, 3)
            ]
        case .large:
            tiles = largeTiles
        case .extraLarge:
            // The large layout on the left; agenda, air, and activity beside it.
            tiles = largeTiles + [
                tile("agenda", 4, 0, 4, 4),
                tile("feelsLike", 4, 4, 2, 2), tile("wind", 6, 4, 2, 2),
                tile("airQuality", 4, 6, 2, 2), tile("humidity", 6, 6, 2, 2),
                tile("activeEnergy", 4, 8, 2, 2), tile("exercise", 6, 8, 2, 2)
            ]
        }
        return WidgetDesign(id: "starter.\(canvas.rawValue)", name: canvas.displayName, canvas: canvas, tiles: tiles)
    }
}

/// Layouts live in the shared app group so the widget extension can draw them
/// and list them in Edit Widget.
enum WidgetDesignStore {
    private static let designsKey = "widget.designs"

    private static var defaults: UserDefaults {
        UserDefaults(suiteName: DashboardStore.appGroupID) ?? .standard
    }

    /// Saved layouts, or one starter layout per size before the first edit.
    static var designs: [WidgetDesign] {
        guard let data = defaults.data(forKey: designsKey),
              let saved = try? JSONDecoder().decode([WidgetDesign].self, from: data) else {
            return WidgetDesign.starters
        }
        return saved
    }

    static func designs(for canvas: WidgetCanvas) -> [WidgetDesign] {
        designs.filter { $0.canvas == canvas }
    }

    static func design(id: String?) -> WidgetDesign? {
        guard let id else { return nil }
        return designs.first { $0.id == id }
    }

    /// A widget whose layout was deleted, or that never chose one, uses the
    /// first layout of its size.
    static func resolvedDesign(id: String?, for canvas: WidgetCanvas) -> WidgetDesign {
        design(id: id) ?? designs(for: canvas).first ?? WidgetDesign.starter(for: canvas)
    }

    static func save(_ design: WidgetDesign) {
        var all = designs
        if let index = all.firstIndex(where: { $0.id == design.id }) {
            all[index] = design
        } else {
            all.append(design)
        }
        write(all)
    }

    static func delete(id: String) {
        write(designs.filter { $0.id != id })
    }

    static func newDesign(for canvas: WidgetCanvas) -> WidgetDesign {
        let count = designs(for: canvas).count
        var design = WidgetDesign.starter(for: canvas)
        design.id = UUID().uuidString
        design.name = count == 0 ? canvas.displayName : "\(canvas.displayName) \(count + 1)"
        design.tiles = design.tiles.map { tile in
            var copy = tile
            copy.id = UUID()
            return copy
        }
        return design
    }

    static func reset() {
        defaults.removeObject(forKey: designsKey)
    }

    private static func write(_ designs: [WidgetDesign]) {
        defaults.set(try? JSONEncoder().encode(designs), forKey: designsKey)
    }
}
