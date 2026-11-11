import Foundation

/// Age of the oldest displayed data in whole minutes. Widgets step this label
/// with one timeline entry per minute instead of a per-second relative clock.
enum UpdatedLabel {
    static func minutes(since date: Date, now: Date) -> Int {
        max(0, Int(now.timeIntervalSince(date) / 60))
    }

    /// "0m", "12m", "3h", "2d".
    static func short(since date: Date, now: Date) -> String {
        let elapsed = Self.minutes(since: date, now: now)
        if elapsed < 60 { return "\(elapsed)m" }
        if elapsed < 24 * 60 { return "\(elapsed / 60)h" }
        return "\(elapsed / (24 * 60))d"
    }

    /// "Updated 0 min ago", "Updated 3 h ago".
    static func long(since date: Date, now: Date) -> String {
        let elapsed = Self.minutes(since: date, now: now)
        if elapsed < 60 { return "Updated \(elapsed) min ago" }
        if elapsed < 24 * 60 { return "Updated \(elapsed / 60) h ago" }
        return "Updated \(elapsed / (24 * 60)) d ago"
    }
}

/// When an event happens, computed at display time so it never goes stale.
enum EventTiming {
    /// "14:30" today, "Tomorrow 09:00", or "Thu 09:00" later in the week.
    static func startText(_ start: Date, now: Date, calendar: Calendar = .current) -> String {
        let time = start.formatted(date: .omitted, time: .shortened)
        if calendar.isDate(start, inSameDayAs: now) { return time }
        if let tomorrow = calendar.date(byAdding: .day, value: 1, to: now),
           calendar.isDate(start, inSameDayAs: tomorrow) {
            return "Tomorrow \(time)"
        }
        return "\(start.formatted(.dateTime.weekday(.abbreviated))) \(time)"
    }

    static func isOngoing(start: Date, end: Date?, now: Date) -> Bool {
        start <= now && (end.map { now < $0 } ?? false)
    }

    /// "in 25m", "in 3h 5m", "in 2d", or "until 15:00" for an event in progress.
    static func relativeText(start: Date, end: Date?, now: Date) -> String {
        if isOngoing(start: start, end: end, now: now), let end {
            return "until \(end.formatted(date: .omitted, time: .shortened))"
        }
        let minutes = Int((start.timeIntervalSince(now) / 60).rounded(.up))
        if minutes <= 0 { return "now" }
        if minutes < 60 { return "in \(minutes)m" }
        if minutes < 24 * 60 {
            let remainder = minutes % 60
            return remainder == 0 ? "in \(minutes / 60)h" : "in \(minutes / 60)h \(remainder)m"
        }
        return "in \(minutes / (24 * 60))d"
    }

    /// The single label for a narrow tile: a countdown within the hour,
    /// otherwise the start time.
    static func compactText(start: Date, end: Date?, now: Date) -> String {
        if isOngoing(start: start, end: end, now: now) { return "Now" }
        let minutes = Int((start.timeIntervalSince(now) / 60).rounded(.up))
        if minutes > 0 && minutes < 60 { return "in \(minutes)m" }
        return startText(start, now: now)
    }

    /// The time column of an agenda line: "All day", "Now", "in 25m", "14:30".
    static func agendaText(_ item: AgendaItem, now: Date) -> String {
        item.isAllDay ? "All day" : compactText(start: item.startsAt, end: item.endsAt, now: now)
    }
}

/// Where a tapped widget tile leads. The widget link carries only the metric
/// id; the app turns it into the app that owns the data.
enum TileLink {
    static let scheme = "com.jonisuominen.readout"
    static let host = "open"

    static func url(for metricID: String) -> URL? {
        guard MetricCatalog.descriptor(for: metricID) != nil else { return nil }
        return URL(string: "\(scheme)://\(host)/\(metricID)")
    }

    /// The metric a widget link names, or nil for any other URL.
    static func metricID(from url: URL) -> String? {
        guard url.scheme == scheme, url.host == host else { return nil }
        let parts = url.pathComponents.filter { $0 != "/" }
        guard parts.count == 1, MetricCatalog.descriptor(for: parts[0]) != nil else { return nil }
        return parts[0]
    }

    /// Calendar opens on the event's day and Apple Health opens for health
    /// tiles. Nil keeps the person in Readout.
    static func externalURL(
        for metricID: String,
        metrics: [DashboardMetric],
        ouraMethod: OuraConnectionMethod,
        now: Date = .now
    ) -> URL? {
        guard let descriptor = MetricCatalog.descriptor(for: metricID) else { return nil }
        switch descriptor.source {
        case .calendar:
            let day = metrics.first { $0.id == metricID }?.observedAt ?? now
            return URL(string: "calshow:\(Int(day.timeIntervalSinceReferenceDate))")
        case .appleHealth:
            return URL(string: "x-apple-health://")
        case .oura:
            return ouraMethod == .appleHealth ? URL(string: "x-apple-health://") : nil
        case .weather:
            return nil
        }
    }
}

/// WHO UV index categories.
enum UVLevel: String {
    case low
    case moderate
    case high
    case veryHigh
    case extreme

    init(index: Double) {
        switch index {
        case ..<2.5: self = .low
        case ..<5.5: self = .moderate
        case ..<7.5: self = .high
        case ..<10.5: self = .veryHigh
        default: self = .extreme
        }
    }

    var label: String {
        switch self {
        case .low: return "Low"
        case .moderate: return "Moderate"
        case .high: return "High"
        case .veryHigh: return "Very high"
        case .extreme: return "Extreme"
        }
    }

    var tint: String {
        switch self {
        case .low: return "green"
        case .moderate: return "yellow"
        case .high: return "orange"
        case .veryHigh: return "red"
        case .extreme: return "violet"
        }
    }
}

/// WMO weather interpretation codes used by Open-Meteo.
enum WeatherCode {
    static func symbol(for code: Int, isDay: Bool = true) -> String {
        switch code {
        case 0, 1: return isDay ? "sun.max.fill" : "moon.stars.fill"
        case 2: return isDay ? "cloud.sun.fill" : "cloud.moon.fill"
        case 3: return "cloud.fill"
        case 45, 48: return "cloud.fog.fill"
        case 51, 53, 55: return "cloud.drizzle.fill"
        case 56, 57, 66, 67: return "cloud.sleet.fill"
        case 61, 63, 65: return "cloud.rain.fill"
        case 71, 73, 75, 77, 85, 86: return "cloud.snow.fill"
        case 80, 81, 82: return "cloud.heavyrain.fill"
        case 95, 96, 99: return "cloud.bolt.rain.fill"
        default: return "cloud.fill"
        }
    }

    static func text(for code: Int) -> String {
        switch code {
        case 0: return "Clear"
        case 1: return "Mostly clear"
        case 2: return "Partly cloudy"
        case 3: return "Cloudy"
        case 45, 48: return "Fog"
        case 51, 53, 55: return "Drizzle"
        case 56, 57: return "Freezing drizzle"
        case 61, 63, 65: return "Rain"
        case 66, 67: return "Freezing rain"
        case 71, 73, 75, 77: return "Snow"
        case 80, 81, 82: return "Showers"
        case 85, 86: return "Snow showers"
        case 95, 96, 99: return "Thunderstorm"
        default: return "Unknown"
        }
    }
}

enum TemperatureUnit: String, CaseIterable, Identifiable {
    case automatic, celsius, fahrenheit

    var id: String { rawValue }
    var displayName: String {
        switch self {
        case .automatic: return "Automatic"
        case .celsius: return "°C"
        case .fahrenheit: return "°F"
        }
    }
}

enum RainUnit: String, CaseIterable, Identifiable {
    case automatic, millimeters, inches

    var id: String { rawValue }
    var displayName: String {
        switch self {
        case .automatic: return "Automatic"
        case .millimeters: return "mm"
        case .inches: return "in"
        }
    }
}

enum WindUnit: String, CaseIterable, Identifiable {
    case automatic, metersPerSecond, kilometersPerHour, milesPerHour

    var id: String { rawValue }
    var displayName: String {
        switch self {
        case .automatic: return "Automatic"
        case .metersPerSecond: return "m/s"
        case .kilometersPerHour: return "km/h"
        case .milesPerHour: return "mph"
        }
    }

    /// Regions whose forecasts give wind in m/s; elsewhere it is km/h, or mph
    /// where miles are used.
    static let metersPerSecondRegions: Set<String> = ["CN", "DK", "FI", "JP", "KR", "NO", "PL", "RU", "SE"]

    func resolved(for locale: Locale) -> WindUnit {
        guard self == .automatic else { return self }
        if locale.measurementSystem == .us || locale.measurementSystem == .uk { return .milesPerHour }
        if let region = locale.region?.identifier, Self.metersPerSecondRegions.contains(region) {
            return .metersPerSecond
        }
        return .kilometersPerHour
    }
}

/// Unit choices live in the shared app group: widgets format weather too.
/// Automatic follows the iPhone's region settings.
enum UnitPreferences {
    static let temperatureKey = "units.temperature"
    static let rainKey = "units.rain"
    static let windKey = "units.wind"

    static var defaults: UserDefaults {
        UserDefaults(suiteName: DashboardStore.appGroupID) ?? .standard
    }

    static var temperature: TemperatureUnit {
        TemperatureUnit(rawValue: defaults.string(forKey: temperatureKey) ?? "") ?? .automatic
    }

    static var rain: RainUnit {
        RainUnit(rawValue: defaults.string(forKey: rainKey) ?? "") ?? .automatic
    }

    static var wind: WindUnit {
        WindUnit(rawValue: defaults.string(forKey: windKey) ?? "") ?? .automatic
    }

    static func reset() {
        [temperatureKey, rainKey, windKey].forEach { defaults.removeObject(forKey: $0) }
    }
}

/// Air quality index bands: the European index, or the US index where the
/// US measurement system is used.
struct AirQualityLevel: Equatable {
    let label: String
    let tint: String

    static func european(_ index: Double) -> AirQualityLevel {
        switch index {
        case ..<20: return .init(label: "Good", tint: "green")
        case ..<40: return .init(label: "Fair", tint: "mint")
        case ..<60: return .init(label: "Moderate", tint: "yellow")
        case ..<80: return .init(label: "Poor", tint: "orange")
        case ..<100: return .init(label: "Very poor", tint: "red")
        default: return .init(label: "Extremely poor", tint: "violet")
        }
    }

    static func us(_ index: Double) -> AirQualityLevel {
        switch index {
        case ...50: return .init(label: "Good", tint: "green")
        case ...100: return .init(label: "Moderate", tint: "yellow")
        case ...150: return .init(label: "Unhealthy for sensitive groups", tint: "orange")
        case ...200: return .init(label: "Unhealthy", tint: "red")
        case ...300: return .init(label: "Very unhealthy", tint: "violet")
        default: return .init(label: "Hazardous", tint: "violet")
        }
    }
}

enum WeatherFormat {
    private static let temperatureFormatter: MeasurementFormatter = {
        let formatter = MeasurementFormatter()
        formatter.unitStyle = .short
        // Converts to the person's preferred scale but prints only "14°".
        formatter.unitOptions = .temperatureWithoutUnit
        formatter.numberFormatter.maximumFractionDigits = 0
        return formatter
    }()

    static func temperature(_ celsius: Double, unit: TemperatureUnit = UnitPreferences.temperature) -> String {
        // Avoid "-0°" for values that round to zero.
        let value = abs(celsius) < 0.5 ? 0 : celsius
        switch unit {
        case .automatic:
            return temperatureFormatter.string(from: Measurement(value: value, unit: UnitTemperature.celsius))
        case .celsius:
            return degrees(value)
        case .fahrenheit:
            return degrees(value * 9 / 5 + 32)
        }
    }

    private static func degrees(_ value: Double) -> String {
        let rounded = Int(value.rounded())
        return "\(rounded)°"
    }

    /// "5 m/s", "18 km/h", or "11 mph".
    static func wind(_ metersPerSecond: Double, unit: WindUnit = UnitPreferences.wind, locale: Locale = .current) -> String {
        let speed = max(0, metersPerSecond)
        switch unit.resolved(for: locale) {
        case .kilometersPerHour: return "\(Int((speed * 3.6).rounded())) km/h"
        case .milesPerHour: return "\(Int((speed * 2.23694).rounded())) mph"
        default: return "\(Int(speed.rounded())) m/s"
        }
    }

    /// "N", "NE", … for the direction the wind blows from.
    static func compass(_ degrees: Double) -> String {
        let points = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"]
        let normalized = (degrees.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)
        return points[Int((normalized / 45).rounded()) % points.count]
    }

    static func precipitation(_ millimeters: Double, locale: Locale = .current, unit: RainUnit = UnitPreferences.rain) -> String {
        if unit == .inches || (unit == .automatic && locale.measurementSystem == .us) {
            let inches = millimeters / 25.4
            return "\(inches.formatted(.number.precision(.fractionLength(inches < 1 ? 2 : 1)))) in"
        }
        let digits = millimeters < 10 ? 1 : 0
        return "\(millimeters.formatted(.number.precision(.fractionLength(digits)))) mm"
    }
}
