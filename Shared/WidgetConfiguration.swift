import AppIntents

extension WidgetMetricChoice: AppEnum {
    static var typeDisplayRepresentation: TypeDisplayRepresentation { "Metric" }
    // App Intents metadata extraction requires a dictionary literal here.
    static var caseDisplayRepresentations: [WidgetMetricChoice: DisplayRepresentation] {
        [
            .automatic: "Automatic",
            .uv: "UV index",
            .temperature: "Temperature",
            .rain: "Rain today",
            .tomorrow: "Tomorrow",
            .feelsLike: "Feels like",
            .wind: "Wind",
            .humidity: "Humidity",
            .sun: "Sunrise and sunset",
            .airQuality: "Air quality",
            .readiness: "Readiness",
            .sleep: "Sleep",
            .steps: "Steps",
            .heart: "Resting heart rate",
            .heartRate: "Heart rate",
            .hrv: "HRV",
            .walkingHeartRate: "Walking heart rate",
            .activeEnergy: "Active energy",
            .exercise: "Exercise minutes",
            .distance: "Distance",
            .workout: "Last workout",
            .nextEvent: "Next event"
        ]
    }
}

extension WidgetPrivacyMode: AppEnum {
    static var typeDisplayRepresentation: TypeDisplayRepresentation { "Privacy" }
    static var caseDisplayRepresentations: [WidgetPrivacyMode: DisplayRepresentation] {
        [.discreet: "Discreet — hide health values and event titles", .showValues: "Show values — allow private values"]
    }
}

/// A tile layout designed in the app, offered in Edit Widget.
struct WidgetDesignEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation { "Layout" }
    static var defaultQuery = WidgetDesignQuery()

    let id: String
    let name: String
    let canvas: WidgetCanvas

    init(_ design: WidgetDesign) {
        id = design.id
        name = design.name
        canvas = design.canvas
    }

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)", subtitle: "\(canvas.displayName)")
    }
}

struct WidgetDesignQuery: EntityQuery {
    func entities(for identifiers: [String]) async throws -> [WidgetDesignEntity] {
        WidgetDesignStore.designs.filter { identifiers.contains($0.id) }.map(WidgetDesignEntity.init)
    }

    func suggestedEntities() async throws -> [WidgetDesignEntity] {
        WidgetDesignStore.designs.map(WidgetDesignEntity.init)
    }
}

protocol DashboardWidgetIntent: WidgetConfigurationIntent {
    var instanceConfiguration: WidgetInstanceConfiguration { get }
    static var defaultConfiguration: WidgetInstanceConfiguration { get }
}

struct HomeWidgetIntent: DashboardWidgetIntent {
    static var title: LocalizedStringResource { "Home Screen widget" }
    static var description: IntentDescription {
        IntentDescription("Choose a layout designed in the app, and whether private values show. Event titles also require permission in Calendar settings.")
    }

    @Parameter(title: "Layout")
    var design: WidgetDesignEntity?

    @Parameter(title: "Privacy", default: .showValues)
    var privacyMode: WidgetPrivacyMode

    static var defaultConfiguration: WidgetInstanceConfiguration { .homeDefault }

    var instanceConfiguration: WidgetInstanceConfiguration {
        WidgetInstanceConfiguration(designID: design?.id, privacyMode: privacyMode)
    }
}

struct LockWidgetIntent: DashboardWidgetIntent {
    static var title: LocalizedStringResource { "Lock Screen widget" }
    static var description: IntentDescription {
        IntentDescription("Discreet hides health values and event titles even while unlocked. Select Show values to opt in for this Lock Screen widget. Event titles also require permission in Calendar settings.")
    }

    @Parameter(title: "Metric", default: .automatic)
    var primaryMetric: WidgetMetricChoice

    @Parameter(title: "Privacy", default: .discreet)
    var privacyMode: WidgetPrivacyMode

    static var defaultConfiguration: WidgetInstanceConfiguration { .lockDefault }

    var instanceConfiguration: WidgetInstanceConfiguration {
        WidgetInstanceConfiguration(primaryMetricID: primaryMetric.metricID, privacyMode: privacyMode)
    }
}

/// The widget's sync button. WidgetKit reloads the widget after it finishes.
struct RefreshDataIntent: AppIntent {
    static var title: LocalizedStringResource { "Refresh data" }
    static var isDiscoverable: Bool { false }

    init() {}

    func perform() async throws -> some IntentResult {
        await WidgetDataRefresher.refresh(userInitiated: true)
        return .result()
    }
}
