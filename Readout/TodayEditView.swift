import SwiftUI

/// Choose which tiles Today shows and arrange them: drag groups and tiles,
/// move a tile to another group, and create, rename, or delete groups.
struct TodayEditView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var todayLayout: TodayLayoutModel
    @EnvironmentObject private var connectorManager: ConnectorManager
    @State private var addingGroup = false
    @State private var newGroupTitle = ""
    @State private var renamingGroupID: String?
    @State private var renameText = ""
    @State private var confirmingReset = false

    private var groups: [TodayGroup] { todayLayout.layout.groups }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(groups) { group in
                        HStack {
                            Label(group.title, systemImage: group.symbol)
                            Spacer()
                            Text("\(group.metricIDs.filter { todayLayout.isShown($0) }.count) shown")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .onMove { offsets, destination in
                        todayLayout.update { $0.moveGroups(from: offsets, to: destination) }
                    }
                    .onDelete { offsets in
                        let ids = offsets.map { groups[$0].id }
                        todayLayout.update { layout in ids.forEach { layout.deleteGroup($0) } }
                    }
                    .deleteDisabled(groups.count <= 1)
                    Button {
                        newGroupTitle = ""
                        addingGroup = true
                    } label: {
                        Label("New group", systemImage: "plus.circle.fill")
                    }
                } header: {
                    Text("Groups")
                } footer: {
                    Text("Drag to reorder. Deleting a group moves its tiles back to their usual group.")
                }

                ForEach(groups) { group in
                    Section {
                        if group.metricIDs.isEmpty {
                            Text("Empty. Move tiles here with the arrow button.")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                        ForEach(group.metricIDs, id: \.self) { metricID in
                            metricRow(metricID, in: group)
                        }
                        .onMove { offsets, destination in
                            todayLayout.update { $0.moveMetrics(inGroup: group.id, from: offsets, to: destination) }
                        }
                    } header: {
                        HStack {
                            Text(group.title)
                            Spacer()
                            Button("Rename") {
                                renameText = group.title
                                renamingGroupID = group.id
                            }
                            .font(.caption)
                            .textCase(nil)
                            .buttonStyle(.borderless)
                        }
                    }
                }

                Section {
                    Button("Reset Today layout", role: .destructive) { confirmingReset = true }
                } footer: {
                    Text("Hidden tiles also stay off Lock Screen widgets set to Automatic. Home Screen widgets keep the tiles you placed in Widgets.")
                }
            }
            .environment(\.editMode, .constant(.active))
            .navigationTitle("Edit Today")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .alert("New group", isPresented: $addingGroup) {
                TextField("Name", text: $newGroupTitle)
                Button("Add") { todayLayout.update { _ = $0.addGroup(title: newGroupTitle) } }
                Button("Cancel", role: .cancel) {}
            }
            .alert("Rename group", isPresented: renamingBinding) {
                TextField("Name", text: $renameText)
                Button("Save") {
                    if let id = renamingGroupID { todayLayout.update { $0.renameGroup(id, to: renameText) } }
                }
                Button("Cancel", role: .cancel) {}
            }
            .confirmationDialog("Reset Today layout?", isPresented: $confirmingReset, titleVisibility: .visible) {
                Button("Reset", role: .destructive) { todayLayout.reset() }
            } message: {
                Text("Groups, order, and hidden tiles go back to the defaults.")
            }
        }
    }

    private var renamingBinding: Binding<Bool> {
        Binding(get: { renamingGroupID != nil }, set: { if !$0 { renamingGroupID = nil } })
    }

    private func metricRow(_ metricID: String, in group: TodayGroup) -> some View {
        let descriptor = MetricCatalog.descriptor(for: metricID)
        let shown = todayLayout.isShown(metricID)
        return HStack(spacing: 12) {
            Toggle(isOn: todayLayout.shownBinding(metricID)) {
                VStack(alignment: .leading, spacing: 2) {
                    Label(descriptor?.name ?? metricID, systemImage: descriptor?.symbol ?? "square")
                        .foregroundStyle(shown ? Color.primary : Color.secondary)
                    Text(caption(for: metricID, source: descriptor?.source))
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            if groups.count > 1 {
                Menu {
                    ForEach(groups.filter { $0.id != group.id }) { target in
                        Button(target.title) {
                            todayLayout.update { $0.moveMetric(metricID, toGroup: target.id) }
                        }
                    }
                } label: {
                    Image(systemName: "arrow.right.circle")
                        .imageScale(.large)
                        .accessibilityLabel("Move \(descriptor?.name ?? metricID) to another group")
                }
                .buttonStyle(.borderless)
            }
        }
    }

    /// "Oura · 86", "Oura · no data yet", or "Oura · not connected".
    private func caption(for metricID: String, source: DataSource?) -> String {
        guard let source else { return "" }
        if let metric = connectorManager.metrics.first(where: { $0.id == metricID }) {
            return "\(source.displayName) · \(metric.displayValue)"
        }
        return "\(source.displayName) · \(connectorManager.connectedSources.contains(source) ? "no data yet" : "not connected")"
    }
}

/// Every value one source can give, with a switch for whether Today shows it.
struct SourceMetricsView: View {
    @EnvironmentObject private var todayLayout: TodayLayoutModel
    @EnvironmentObject private var connectorManager: ConnectorManager
    let source: DataSource

    private var descriptors: [MetricDescriptor] {
        MetricCatalog.all.filter { $0.source == source }
    }

    var body: some View {
        List {
            Section {
                ForEach(descriptors) { descriptor in
                    Toggle(isOn: todayLayout.shownBinding(descriptor.id)) {
                        VStack(alignment: .leading, spacing: 2) {
                            Label(descriptor.name, systemImage: descriptor.symbol)
                            if let metric = connectorManager.metrics.first(where: { $0.id == descriptor.id }) {
                                Text(metric.displayValue).font(.caption).foregroundStyle(.secondary)
                            } else {
                                Text("No data yet").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            } footer: {
                Text("Hidden values still update, so widgets you placed keep working. Arrange groups and order with Edit on Today.")
            }
            Section {
                Button("Show all") { todayLayout.update { layout in descriptors.forEach { layout.setShown($0.id, true) } } }
                Button("Hide all") { todayLayout.update { layout in descriptors.forEach { layout.setShown($0.id, false) } } }
            }
        }
        .navigationTitle(source.displayName)
        .navigationBarTitleDisplayMode(.inline)
    }
}
