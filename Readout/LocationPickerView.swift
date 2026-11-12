import SwiftUI

/// Chooses the weather location: the device's approximate location, or a city.
struct LocationPickerView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var results: [WeatherLocation] = []
    @State private var isSearching = false
    @State private var errorMessage: String?
    let onPick: (WeatherLocation?) -> Void

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        onPick(nil)
                    } label: {
                        Label("Use my location", systemImage: "location.fill")
                    }
                }
                Section {
                    // Open-Meteo data is licensed CC BY 4.0 and requires this credit.
                    Link("Weather and city search by Open-Meteo.com", destination: URL(string: "https://open-meteo.com/")!)
                        .font(.footnote)
                }
                Section("Cities") {
                    if isSearching {
                        ProgressView()
                    }
                    if let errorMessage {
                        Text(errorMessage).foregroundStyle(.orange)
                    }
                    ForEach(results) { place in
                        Button {
                            onPick(place)
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(place.name).foregroundStyle(.primary)
                                if let detail = place.detail {
                                    Text(detail).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                    if !isSearching && results.isEmpty && errorMessage == nil {
                        Text(query.trimmingCharacters(in: .whitespaces).count >= 2 ? "No matches" : "Type at least two letters")
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search cities")
            .task(id: query) {
                // Wait for typing to pause before searching.
                try? await Task.sleep(for: .milliseconds(350))
                guard !Task.isCancelled else { return }
                await search(query)
            }
            .navigationTitle("Weather location")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private func search(_ text: String) async {
        guard text.trimmingCharacters(in: .whitespaces).count >= 2 else {
            results = []
            errorMessage = nil
            return
        }
        isSearching = true
        defer { isSearching = false }
        do {
            results = try await OpenMeteoClient().searchPlaces(text)
            errorMessage = nil
        } catch let error as URLError where error.code == .cancelled {
            // A newer search replaced this one.
        } catch is CancellationError {
            // A newer search replaced this one.
        } catch {
            errorMessage = "City search is unavailable. Check your connection."
        }
    }
}
