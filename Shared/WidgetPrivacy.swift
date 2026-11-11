import Foundation

enum WidgetPrivacyMode: String, CaseIterable, Identifiable {
    /// Always conceal health measurements and event titles, even when unlocked.
    case discreet
    /// Show authorized values with SwiftUI privacy sensitivity for iOS redaction.
    case showValues

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .discreet: return "Discreet"
        case .showValues: return "Show values"
        }
    }
}

/// Project source data into display-safe metrics before creating a timeline or
/// drawing a preview. Concealed measurements must never survive in a gauge,
/// score description, tint, accessibility label, or hidden private title.
/// Weather is public forecast data for a coarse location and is never concealed.
enum WidgetPrivacy {
    static func render(_ metrics: [DashboardMetric], mode: WidgetPrivacyMode) -> [DashboardMetric] {
        metrics.map { render($0, mode: mode) }
    }

    private static func render(_ metric: DashboardMetric, mode: WidgetPrivacyMode) -> DashboardMetric {
        if metric.source == .calendar {
            return metric.id == "agenda" ? renderAgenda(metric, mode: mode) : renderCalendar(metric, mode: mode)
        }
        if metric.source == .weather {
            return DashboardMetric(
                id: metric.id,
                source: metric.source,
                title: metric.title,
                value: metric.value,
                detail: metric.detail,
                symbol: metric.symbol,
                tint: metric.tint,
                numericValue: metric.numericValue,
                targetValue: metric.targetValue,
                observedAt: metric.observedAt,
                expiresAt: metric.expiresAt
            )
        }

        if mode == .discreet {
            return DashboardMetric(
                id: metric.id,
                source: metric.source,
                title: healthTitle(for: metric.id),
                value: "Hidden",
                detail: "Open app for value",
                symbol: "lock.fill",
                tint: "gray",
                expiresAt: metric.expiresAt
            )
        }

        return DashboardMetric(
            id: metric.id,
            source: metric.source,
            title: metric.title,
            value: metric.value,
            detail: metric.detail,
            symbol: metric.symbol,
            tint: metric.tint,
            numericValue: metric.numericValue,
            targetValue: metric.targetValue,
            observedAt: metric.observedAt,
            expiresAt: metric.expiresAt
        )
    }

    private static func renderCalendar(_ metric: DashboardMetric, mode: WidgetPrivacyMode) -> DashboardMetric {
        let hasEvent = metric.value != "No upcoming events"
        let title = metric.privateValue?.trimmingCharacters(in: .whitespacesAndNewlines)
        // Older app versions stored raw titles in value. Accept only the
        // current public sentinel; never trust a legacy value as a title grant.
        let value: String
        if mode == .showValues, hasEvent, let title, !title.isEmpty {
            value = title
        } else {
            value = hasEvent ? "Busy" : "No upcoming events"
        }
        let detail: String
        if !hasEvent {
            detail = "Selected calendars"
        } else if let startsAt = metric.observedAt {
            detail = EventTiming.startText(startsAt, now: .now)
        } else {
            detail = "Open app for schedule"
        }
        return DashboardMetric(
            id: metric.id,
            source: metric.source,
            title: "Up next",
            value: value,
            detail: detail,
            symbol: "calendar",
            tint: "blue",
            observedAt: hasEvent ? metric.observedAt : nil,
            endsAt: hasEvent ? metric.endsAt : nil,
            expiresAt: metric.expiresAt
        )
    }

    /// Times stay; titles survive only in widgets set to show values.
    private static func renderAgenda(_ metric: DashboardMetric, mode: WidgetPrivacyMode) -> DashboardMetric {
        let items = (metric.items ?? []).map { item in
            AgendaItem(
                startsAt: item.startsAt,
                endsAt: item.endsAt,
                isAllDay: item.isAllDay,
                title: mode == .showValues ? item.title : nil
            )
        }
        return DashboardMetric(
            id: metric.id,
            source: metric.source,
            title: "Agenda",
            value: items.isEmpty ? "No upcoming events" : "Busy",
            detail: items.isEmpty ? "Selected calendars" : "",
            symbol: "list.bullet",
            tint: "blue",
            observedAt: items.first { !$0.isAllDay }?.startsAt,
            expiresAt: metric.expiresAt,
            items: items
        )
    }

    private static func healthTitle(for id: String) -> String {
        switch id {
        case "readiness": return "Readiness"
        case "sleep": return "Sleep"
        case "steps": return "Steps"
        case "heart": return "Resting heart rate"
        case "heartRate": return "Heart rate"
        case "hrv": return "HRV"
        case "walkingHeartRate": return "Walking heart rate"
        case "activeEnergy": return "Active energy"
        case "exercise": return "Exercise minutes"
        case "distance": return "Distance"
        case "workout": return "Last workout"
        default: return "Health"
        }
    }
}
