import SwiftUI
import WidgetKit

/// Edits one layout on its widget grid: tap a tile to select it and see where
/// it can go, then tap a free spot or drag the tile there. Drag its corner to
/// resize. Smaller tiles switch to a shorter format.
struct LayoutEditorView: View {
    @EnvironmentObject private var connectorManager: ConnectorManager
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var design: WidgetDesign
    @State private var selectedID: UUID?
    @State private var drag: TileDrag?
    @State private var availableWidth: CGFloat = 343
    @State private var message: String?
    @State private var showingDeleteConfirmation = false
    private let onChange: () -> Void

    init(design: WidgetDesign, onChange: @escaping () -> Void) {
        _design = State(initialValue: design)
        self.onChange = onChange
    }

    private struct TileDrag {
        enum Kind { case move, resize }
        let kind: Kind
        let tileID: UUID
        var translation: CGSize
    }

    private static let syncDescriptor = MetricDescriptor(id: WidgetTile.syncID, name: "Refresh and age", source: .weather, symbol: "arrow.clockwise")

    private var scale: CGFloat {
        let size = design.canvas.previewSize
        let widthScale = (availableWidth - 32) / size.width
        let maxScale: CGFloat
        switch design.canvas {
        case .small: maxScale = 1.8
        case .medium: maxScale = 1.15
        case .large, .extraLarge: maxScale = 1.0
        }
        return max(0.5, min(widthScale, maxScale))
    }

    private var selectedTile: WidgetTile? {
        design.tiles.first { $0.id == selectedID }
    }

    /// Motion for snapping and highlights. None when Reduce Motion is on.
    private var snapAnimation: Animation? { reduceMotion ? nil : .snappy(duration: 0.22) }
    private var quickAnimation: Animation? { reduceMotion ? nil : .easeOut(duration: 0.15) }

    private var shownMetrics: [DashboardMetric] {
        WidgetPrivacy.render(connectorManager.connectedSources.isEmpty ? DashboardMetric.defaults : connectorManager.metrics, mode: .showValues)
    }

    private var context: TileContext {
        let tileIDs = Set(design.tiles.map(\.metricID))
        return TileContext(
            now: .now,
            freshness: DashboardFreshness.stored(for: shownMetrics.filter { tileIDs.contains($0.id) }),
            privacyMode: .showValues,
            connectedSources: connectorManager.connectedSources.isEmpty ? Set(DataSource.allCases) : connectorManager.connectedSources,
            isInteractive: false,
            showsUnavailable: true,
            scale: scale
        )
    }

    private var addableItems: [MetricDescriptor] {
        let used = Set(design.tiles.map(\.metricID))
        return (MetricCatalog.all + [Self.syncDescriptor]).filter { !used.contains($0.id) }
    }

    var body: some View {
        VStack(spacing: 0) {
            editorCanvas
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(
                    GeometryReader { proxy in
                        Color.clear.preference(key: AvailableWidthKey.self, value: proxy.size.width)
                    }
                )
            List {
                if let tile = selectedTile {
                    selectedTileControls(tile)
                } else {
                    Section {
                        Text("Tap a tile to select it. Green spots show where it can go: tap one, or drag the tile onto it. Drag the corner to resize. Smaller tiles show less: a full card, an icon and value, or just the value.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                Section("Add") {
                    if let message {
                        Text(message).font(.footnote).foregroundStyle(.orange)
                    }
                    ForEach(addableItems) { item in
                        Button {
                            add(item.id)
                        } label: {
                            Label(item.name, systemImage: item.symbol)
                        }
                    }
                    if addableItems.isEmpty {
                        Text("Every item is already on this layout.").foregroundStyle(.secondary)
                    }
                }
                Section("Style") {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 12) {
                            ForEach(WidgetAppearance.allCases) { appearance in
                                AppearanceSwatch(appearance: appearance, isSelected: design.appearance == appearance) {
                                    design.appearance = appearance
                                }
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
                Section("Layout") {
                    TextField("Name", text: $design.name)
                    Button("Delete layout", role: .destructive) { showingDeleteConfirmation = true }
                }
            }
        }
        .background(Color(.systemGroupedBackground))
        .onPreferenceChange(AvailableWidthKey.self) { availableWidth = $0 }
        .navigationTitle(design.name.isEmpty ? "Layout" : design.name)
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: design) { _, newValue in
            WidgetDesignStore.save(newValue)
            onChange()
        }
        .onDisappear { WidgetCenter.shared.reloadAllTimelines() }
        .confirmationDialog("Delete this layout?", isPresented: $showingDeleteConfirmation, titleVisibility: .visible) {
            Button("Delete layout", role: .destructive) {
                WidgetDesignStore.delete(id: design.id)
                onChange()
                dismiss()
            }
        } message: {
            Text("Widgets using it switch to another \(design.canvas.displayName.lowercased()) layout.")
        }
    }

    // MARK: Canvas

    private struct Cell: Hashable {
        var column: Int
        var row: Int
    }

    private var editorCanvas: some View {
        let size = design.canvas.previewSize
        let margin = WidgetCanvas.contentMargin * scale
        let inner = CGSize(width: size.width * scale - 2 * margin, height: size.height * scale - 2 * margin)
        let grid = TileGridGeometry(size: inner, canvas: design.canvas, spacing: WidgetCanvas.tileSpacing * scale)
        let cells = Array(0..<(design.canvas.columns * design.canvas.rows))
        let available = availableCells
        let target = dropTarget
        let borderColor: Color = target.map { $0.isValid ? Color.green : Color.red } ?? Color.secondary.opacity(0.25)
        return ZStack(alignment: .topLeading) {
            ForEach(cells, id: \.self) { index in
                let cell = Cell(column: index % design.canvas.columns, row: index / design.canvas.columns)
                let frame = grid.frame(of: WidgetTile(metricID: "", column: cell.column, row: cell.row))
                let isAvailable = available.contains(cell)
                // Free spots get a solid outline and a fill; the rest stay dashed, so color is not the only cue.
                RoundedRectangle(cornerRadius: 5 * scale)
                    .fill(isAvailable ? Color.green.opacity(0.14) : Color.clear)
                    .overlay(
                        RoundedRectangle(cornerRadius: 5 * scale)
                            .strokeBorder(
                                isAvailable ? Color.green.opacity(0.7) : Color.secondary.opacity(0.2),
                                style: isAvailable ? StrokeStyle(lineWidth: 1.5) : StrokeStyle(lineWidth: 1, dash: [3, 3])
                            )
                    )
                    .frame(width: frame.width, height: frame.height)
                    .offset(x: frame.minX, y: frame.minY)
                    .animation(quickAnimation, value: isAvailable)
            }
            // Empty space: tap a green spot to place the selected tile there, anywhere else to deselect.
            Color.clear
                .frame(width: inner.width, height: inner.height)
                .contentShape(Rectangle())
                .gesture(SpatialTapGesture().onEnded { place(atCanvasPoint: $0.location, grid: grid, available: available) })
            if let target {
                let frame = grid.frame(of: target.tile)
                let tint: Color = target.isValid ? .green : .red
                // A check on a solid outline marks a spot that works; a cross on a dashed one marks a blocked spot.
                RoundedRectangle(cornerRadius: 6 * scale)
                    .fill(tint.opacity(target.isValid ? 0.3 : 0.2))
                    .overlay(
                        RoundedRectangle(cornerRadius: 6 * scale)
                            .strokeBorder(tint, style: target.isValid ? StrokeStyle(lineWidth: 2) : StrokeStyle(lineWidth: 2, dash: [5, 3]))
                    )
                    .overlay(
                        Image(systemName: target.isValid ? "checkmark" : "xmark")
                            .font(.system(size: 11 * scale, weight: .bold))
                            .foregroundStyle(tint)
                    )
                    .frame(width: frame.width, height: frame.height)
                    .offset(x: frame.minX, y: frame.minY)
                    .zIndex(0.5)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
                    .animation(quickAnimation, value: target.tile)
            }
            ForEach(design.tiles) { tile in
                editableTile(tile, grid: grid)
            }
        }
        .frame(width: inner.width, height: inner.height, alignment: .topLeading)
        .coordinateSpace(name: "canvas")
        .padding(margin)
        .background { design.appearance.previewBackground.clipShape(RoundedRectangle(cornerRadius: 22 * scale, style: .continuous)) }
        .overlay(
            RoundedRectangle(cornerRadius: 22 * scale, style: .continuous)
                .stroke(borderColor, lineWidth: target == nil ? 1 : 2)
                .animation(quickAnimation, value: target?.isValid)
        )
        .widgetAppearance(design.appearance)
        .sensoryFeedback(.selection, trigger: target?.tile)
    }

    /// Where a dragged tile would land. It snaps to the nearest free spot; with
    /// none in reach it shows the blocked cell it hovers over. Resizing
    /// previews in place instead.
    private var dropTarget: (tile: WidgetTile, isValid: Bool)? {
        guard let drag, drag.kind == .move, let tile = design.tiles.first(where: { $0.id == drag.tileID }) else { return nil }
        if let snap = snapped(tile, by: drag.translation) { return (snap, true) }
        return (moved(tile, by: drag.translation), false)
    }

    /// Cells the selected tile could be placed on, not counting its own.
    private var availableCells: Set<Cell> {
        guard let tile = selectedTile else { return [] }
        var cells = Set<Cell>()
        for spot in design.placements(for: tile) {
            for column in spot.column..<spot.endColumn {
                for row in spot.row..<spot.endRow { cells.insert(Cell(column: column, row: row)) }
            }
        }
        for column in tile.column..<tile.endColumn {
            for row in tile.row..<tile.endRow { cells.remove(Cell(column: column, row: row)) }
        }
        return cells
    }

    /// Reach of the snap in points: a bit under a column, so a blocked cell
    /// hands the tile to the next free one instead of refusing the drop.
    private var snapDistance: CGFloat {
        let pitch = TileGridGeometry(size: gridSize, canvas: design.canvas, spacing: WidgetCanvas.tileSpacing * scale).pitch
        return 0.9 * max(pitch.width, pitch.height)
    }

    /// The free spot nearest to where a drag has carried the tile.
    private func snapped(_ tile: WidgetTile, by translation: CGSize) -> WidgetTile? {
        let pitch = TileGridGeometry(size: gridSize, canvas: design.canvas, spacing: WidgetCanvas.tileSpacing * scale).pitch
        return design.nearestPlacement(
            of: tile,
            column: CGFloat(tile.column) + translation.width / pitch.width,
            row: CGFloat(tile.row) + translation.height / pitch.height,
            pitch: pitch,
            maxDistance: snapDistance
        )
    }

    /// Puts the selected tile on the free spot around a tap, centered on it.
    private func place(atCanvasPoint point: CGPoint, grid: TileGridGeometry, available: Set<Cell>) {
        let pitch = grid.pitch
        let tappedCell = Cell(
            column: min(max(0, Int(point.x / pitch.width)), design.canvas.columns - 1),
            row: min(max(0, Int(point.y / pitch.height)), design.canvas.rows - 1)
        )
        guard let tile = selectedTile, available.contains(tappedCell) else {
            selectedID = nil
            return
        }
        let size = grid.frame(of: tile).size
        guard let target = design.nearestPlacement(
            of: tile,
            column: (point.x - size.width / 2) / pitch.width,
            row: (point.y - size.height / 2) / pitch.height,
            pitch: pitch,
            maxDistance: .infinity
        ) else { return }
        withAnimation(snapAnimation) {
            design.update(target)
            showMessage(nil)
        }
        if target != tile { announce("Moved to \(position(of: target))") }
    }

    /// Read aloud by VoiceOver, which cannot see the move happen.
    private func announce(_ text: String) {
        AccessibilityNotification.Announcement(text).post()
    }

    private func position(of tile: WidgetTile) -> String {
        "column \(tile.column + 1), row \(tile.row + 1)"
    }

    /// Moves a tile one step in a direction, jumping over tiles in the way.
    /// This is how VoiceOver and Switch Control users reposition tiles.
    private func nudge(_ tile: WidgetTile, columns: Int, rows: Int, direction: String) {
        guard let target = design.placement(of: tile, stepping: columns, rows) else {
            showMessage("Cannot move \(direction). No free space that way.")
            return
        }
        withAnimation(snapAnimation) {
            design.update(target)
            showMessage(nil)
        }
        announce("Moved \(direction) to \(position(of: target))")
    }

    private func editableTile(_ tile: WidgetTile, grid: TileGridGeometry) -> some View {
        let isSelected = tile.id == selectedID
        return tileBody(tile, grid: grid, isSelected: isSelected)
            .onTapGesture { selectedID = tile.id }
            .gesture(moveGesture(tile))
            .modifier(TileAccessibility(
                value: "\(position(of: tile)), \(tile.width) by \(tile.height)",
                isSelected: isSelected,
                move: { columns, rows, direction in nudge(tile, columns: columns, rows: rows, direction: direction) }
            ))
    }

    private func tileBody(_ tile: WidgetTile, grid: TileGridGeometry, isSelected: Bool) -> some View {
        let isMoving = drag?.kind == .move && drag?.tileID == tile.id
        let isResizing = drag?.kind == .resize && drag?.tileID == tile.id
        let shown = isResizing ? resized(tile, by: drag?.translation ?? .zero) : tile
        let frame = grid.frame(of: shown)
        let moveOffset: CGSize = isMoving ? (drag?.translation ?? .zero) : .zero
        let blocked = isResizing && !design.canPlace(shown)
        let fill: Color = isSelected ? Color.accentColor.opacity(0.12) : Color.secondary.opacity(0.08)
        let outline: Color = blocked ? .red : isSelected ? .accentColor : .clear
        let lift: CGFloat = isMoving ? 1.04 : 1
        let shadow: Double = isMoving ? 0.25 : 0
        let layer: Double = isMoving ? 2 : isSelected ? 1 : 0
        return TileView(
            tile: shown,
            metric: shownMetrics.first { $0.id == tile.metricID },
            context: context,
            size: frame.size,
            isTrailing: shown.endColumn == design.canvas.columns
        )
        .padding(.horizontal, 3 * scale)
        .frame(width: frame.width, height: frame.height)
        .background(RoundedRectangle(cornerRadius: 6 * scale).fill(fill))
        .overlay(RoundedRectangle(cornerRadius: 6 * scale).stroke(outline, lineWidth: 2))
        .contentShape(Rectangle())
        // Outside the content shape so the handle stays tappable past the tile's edge.
        .overlay(alignment: .bottomTrailing) {
            if isSelected { resizeHandle(tile, grid: grid) }
        }
        // A picked-up tile rises above the others while the finger carries it.
        .scaleEffect(lift)
        .shadow(color: .black.opacity(shadow), radius: 6 * scale, y: 3 * scale)
        .animation(quickAnimation, value: isMoving)
        .offset(x: frame.minX + moveOffset.width, y: frame.minY + moveOffset.height)
        .zIndex(layer)
    }

    private func resizeHandle(_ tile: WidgetTile, grid: TileGridGeometry) -> some View {
        Image(systemName: "arrow.up.left.and.arrow.down.right")
            .font(.system(size: 9, weight: .bold))
            .foregroundStyle(.white)
            .frame(width: 20, height: 20)
            .background(Color.accentColor, in: Circle())
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
            .offset(x: 18, y: 18)
            .highPriorityGesture(
                DragGesture(minimumDistance: 1, coordinateSpace: .named("canvas"))
                    .onChanged { value in
                        drag = TileDrag(kind: .resize, tileID: tile.id, translation: value.translation)
                    }
                    .onEnded { value in
                        let target = resized(tile, by: value.translation)
                        if target.width != tile.width || target.height != tile.height {
                            let applied = design.resizeTile(id: tile.id, width: target.width, height: target.height)
                            showMessage(applied.map { $0.width == target.width && $0.height == target.height } ?? false
                                ? nil : "Resized as far as the free space allows.")
                        }
                        drag = nil
                    }
            )
            .accessibilityHidden(true)
    }

    /// Any tile can be picked up and dragged; it lands on the nearest free spot
    /// and goes back where it was when none is in reach.
    private func moveGesture(_ tile: WidgetTile) -> some Gesture {
        DragGesture(minimumDistance: 3, coordinateSpace: .named("canvas"))
            .onChanged { value in
                if selectedID != tile.id { selectedID = tile.id }
                drag = TileDrag(kind: .move, tileID: tile.id, translation: value.translation)
            }
            .onEnded { value in
                let target = snapped(tile, by: value.translation)
                withAnimation(snapAnimation) {
                    if let target {
                        design.update(target)
                        showMessage(nil)
                    } else {
                        showMessage("No free space there. Drop it on a green spot with a check, or shrink or remove a tile first.")
                    }
                    drag = nil
                }
            }
    }

    private func moved(_ tile: WidgetTile, by translation: CGSize) -> WidgetTile {
        let cells = TileGridGeometry(size: gridSize, canvas: design.canvas, spacing: WidgetCanvas.tileSpacing * scale).cells(for: translation)
        var target = tile
        target.column = min(max(0, tile.column + cells.columns), design.canvas.columns - tile.width)
        target.row = min(max(0, tile.row + cells.rows), design.canvas.rows - tile.height)
        return target
    }

    private func resized(_ tile: WidgetTile, by translation: CGSize) -> WidgetTile {
        let cells = TileGridGeometry(size: gridSize, canvas: design.canvas, spacing: WidgetCanvas.tileSpacing * scale).cells(for: translation)
        var target = tile
        target.width = min(max(1, tile.width + cells.columns), design.canvas.columns - tile.column)
        target.height = min(max(1, tile.height + cells.rows), design.canvas.rows - tile.row)
        return target
    }

    private var gridSize: CGSize {
        let size = design.canvas.previewSize
        let margin = WidgetCanvas.contentMargin * scale
        return CGSize(width: size.width * scale - 2 * margin, height: size.height * scale - 2 * margin)
    }

    // MARK: Controls

    @ViewBuilder
    private func selectedTileControls(_ tile: WidgetTile) -> some View {
        Section(name(of: tile)) {
            Text(availableCells.isEmpty
                ? "No free spot for this tile. Shrink or remove another tile first."
                : "Tap a green spot or drag the tile onto one to move it.")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Stepper(
                "Width: \(tile.width)",
                onIncrement: { resize(tile, width: tile.width + 1, height: tile.height) },
                onDecrement: { resize(tile, width: tile.width - 1, height: tile.height) }
            )
            Stepper(
                "Height: \(tile.height)",
                onIncrement: { resize(tile, width: tile.width, height: tile.height + 1) },
                onDecrement: { resize(tile, width: tile.width, height: tile.height - 1) }
            )
            Picker("Show", selection: styleBinding(for: tile)) {
                Text(tile.isSync ? "Icon and minutes" : "Icon and value").tag(TileStyle.standard)
                Text(tile.isSync ? "Icon only" : "Value only").tag(TileStyle.valueOnly)
            }
            Button("Remove from layout", role: .destructive) {
                design.removeTile(id: tile.id)
                selectedID = nil
            }
        }
    }

    private func resize(_ tile: WidgetTile, width: Int, height: Int) {
        guard width >= 1, height >= 1 else { return }
        var target = tile
        target.width = width
        target.height = height
        showMessage(design.update(target) ? nil : "No free space in that direction.")
    }

    private func styleBinding(for tile: WidgetTile) -> Binding<TileStyle> {
        Binding(
            get: { design.tiles.first { $0.id == tile.id }?.style ?? .standard },
            set: { style in
                var updated = tile
                updated.style = style
                design.update(updated)
            }
        )
    }

    private func add(_ metricID: String) {
        if let tile = design.addTile(metricID: metricID) {
            selectedID = tile.id
            showMessage(nil)
        } else {
            showMessage("No free space. Shrink or remove a tile first.")
        }
    }

    private func name(of tile: WidgetTile) -> String {
        tile.isSync ? Self.syncDescriptor.name : MetricCatalog.descriptor(for: tile.metricID)?.name ?? tile.metricID
    }

    private func showMessage(_ text: String?) {
        message = text
        if let text { announce(text) }
    }
}

/// What VoiceOver says about an editor tile, and how it moves one: the
/// canvas drag has no equivalent for people who cannot use it.
private struct TileAccessibility: ViewModifier {
    let value: String
    let isSelected: Bool
    let move: (_ columns: Int, _ rows: Int, _ direction: String) -> Void

    func body(content: Content) -> some View {
        let traits: AccessibilityTraits = isSelected ? [.isSelected, .isButton] : .isButton
        content
            .accessibilityElement(children: .combine)
            .accessibilityValue(value)
            .accessibilityHint("Select, then use the actions to move it, or the controls below to resize or remove it")
            .accessibilityAddTraits(traits)
            .accessibilityAction(named: "Move left") { move(-1, 0, "left") }
            .accessibilityAction(named: "Move right") { move(1, 0, "right") }
            .accessibilityAction(named: "Move up") { move(0, -1, "up") }
            .accessibilityAction(named: "Move down") { move(0, 1, "down") }
    }
}

/// A small sample of a widget style for the style picker.
private struct AppearanceSwatch: View {
    let appearance: WidgetAppearance
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(.clear)
                    .background { appearance.previewBackground.clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous)) }
                    .overlay(Text("Aa").font(.headline).widgetAppearance(appearance))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .strokeBorder(isSelected ? Color.accentColor : Color.secondary.opacity(0.3), lineWidth: isSelected ? 3 : 1)
                    )
                    .frame(width: 56, height: 56)
                Text(appearance.displayName)
                    .font(.caption)
                    .foregroundStyle(isSelected ? Color.accentColor : Color.secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(appearance.displayName)
        .accessibilityAddTraits(isSelected ? [.isSelected, .isButton] : .isButton)
    }
}

private struct AvailableWidthKey: PreferenceKey {
    static var defaultValue: CGFloat = 343
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}
