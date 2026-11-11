import Foundation
import HealthKit

final class HealthKitConnector: DashboardConnector {
    let source: DataSource = .appleHealth

    private let healthStore: HKHealthStore

    init(healthStore: HKHealthStore = HKHealthStore()) {
        self.healthStore = healthStore
    }

    func authorize() async throws {
        guard HKHealthStore.isHealthDataAvailable() else {
            throw ConnectorError.unavailable("Apple Health is not available on this device.")
        }
        try await healthStore.requestAuthorization(toShare: [], read: Self.readTypes)
    }

    func refresh() async throws -> [DashboardMetric] {
        guard HKHealthStore.isHealthDataAvailable() else {
            throw ConnectorError.unavailable("Apple Health is not available on this device.")
        }

        async let steps = todaySteps()
        async let heart = heartMetrics()
        async let totals = dailyTotals()
        async let workout = latestWorkout()
        let results = try await (steps, heart, totals, workout)
        return [results.0] + results.1 + results.2 + [results.3]
    }

    func disconnect() async -> DisconnectResult {
        // iOS owns HealthKit grants. Users revoke them in Settings.
        return .localOnly
    }

    /// True when a type added in a newer version has not been offered to the
    /// user yet. Only the app can show the permission sheet.
    func needsAuthorizationRequest() async -> Bool {
        guard HKHealthStore.isHealthDataAvailable() else { return false }
        let status = try? await healthStore.statusForAuthorizationRequest(toShare: [], read: Self.readTypes)
        return status == .shouldRequest
    }

    private static var readTypes: Set<HKObjectType> {
        var types = Set<HKObjectType>()
        if let steps = HKObjectType.quantityType(forIdentifier: .stepCount) {
            types.insert(steps)
        }
        for heart in HeartMetric.all {
            if let type = HKObjectType.quantityType(forIdentifier: heart.identifier) {
                types.insert(type)
            }
        }
        for total in DailyTotal.all {
            if let type = HKObjectType.quantityType(forIdentifier: total.identifier) {
                types.insert(type)
            }
        }
        types.insert(HKObjectType.workoutType())
        return types
    }

    /// Activity summed since midnight. Denied or unrecorded types show "—".
    private struct DailyTotal {
        let id: String
        let title: String
        let identifier: HKQuantityTypeIdentifier
        let unit: HKUnit
        let symbol: String
        let tint: String
        let target: Double?
        let emptyDetail: String
        let value: (Double) -> String
        let detail: String

        static let all: [DailyTotal] = [
            .init(id: "activeEnergy", title: "Active energy", identifier: .activeEnergyBurned, unit: .kilocalorie(),
                  symbol: "flame.fill", tint: "red", target: nil, emptyDetail: "No active energy recorded today",
                  value: { $0.formatted(.number.precision(.fractionLength(0))) }, detail: "kcal today"),
            .init(id: "exercise", title: "Exercise", identifier: .appleExerciseTime, unit: .minute(),
                  symbol: "figure.run", tint: "green", target: 30, emptyDetail: "No exercise minutes recorded today",
                  value: { $0.formatted(.number.precision(.fractionLength(0))) }, detail: "min today · goal 30"),
            .init(id: "distance", title: "Distance", identifier: .distanceWalkingRunning, unit: .meter(),
                  symbol: "figure.walk.motion", tint: "mint", target: nil, emptyDetail: "No distance recorded today",
                  value: { HealthFormat.distance(meters: $0) }, detail: "Walking and running today")
        ]
    }

    private func dailyTotals() async throws -> [DashboardMetric] {
        try await withThrowingTaskGroup(of: (Int, DashboardMetric).self) { group in
            for (index, total) in DailyTotal.all.enumerated() {
                guard let type = HKQuantityType.quantityType(forIdentifier: total.identifier) else { continue }
                group.addTask { (index, try await self.dailyTotal(total, type: type)) }
            }
            var metrics: [(Int, DashboardMetric)] = []
            for try await metric in group { metrics.append(metric) }
            return metrics.sorted { $0.0 < $1.0 }.map(\.1)
        }
    }

    private func dailyTotal(_ total: DailyTotal, type: HKQuantityType) async throws -> DashboardMetric {
        let start = Calendar.current.startOfDay(for: .now)
        let predicate = HKQuery.predicateForSamples(withStart: start, end: .now)
        let statistics = try await statistics(for: type, predicate: predicate, options: [.cumulativeSum, .separateBySource])
        let sum = statistics?.sumQuantity()?.doubleValue(for: total.unit)
        return DashboardMetric(
            id: total.id,
            source: .appleHealth,
            title: total.title,
            value: sum.map(total.value) ?? "—",
            detail: sum == nil ? total.emptyDetail : total.detail,
            symbol: total.symbol,
            tint: total.tint,
            numericValue: sum,
            targetValue: total.target,
            observedAt: sum == nil ? nil : .now,
            // Today's total must not be shown as tomorrow's.
            expiresAt: Calendar.current.date(byAdding: .day, value: 1, to: start),
            origin: Self.originNames(statistics?.sources)
        )
    }

    private func latestWorkout() async throws -> DashboardMetric {
        let workout = try await latestSample(for: HKObjectType.workoutType()) as? HKWorkout
        return DashboardMetric(
            id: "workout",
            source: .appleHealth,
            title: "Last workout",
            value: workout.map { HealthFormat.duration(seconds: $0.duration) } ?? "—",
            detail: workout.map { "\(HealthFormat.workoutName($0.workoutActivityType)) · \(Self.sampleTime($0.endDate))" }
                ?? "No workout found",
            symbol: "dumbbell.fill",
            tint: "green",
            numericValue: workout?.duration,
            observedAt: workout?.endDate,
            origin: workout?.sourceRevision.source.name
        )
    }

    /// Heart readings a tile can show; each is its own metric so every tile
    /// picks the one it wants. Denied types return no samples, not an error.
    private struct HeartMetric {
        let id: String
        let title: String
        let identifier: HKQuantityTypeIdentifier
        let unit: HKUnit
        let unitLabel: String

        static let all: [HeartMetric] = [
            .init(id: "heart", title: "Resting heart rate", identifier: .restingHeartRate,
                  unit: .count().unitDivided(by: .minute()), unitLabel: "bpm"),
            .init(id: "heartRate", title: "Heart rate", identifier: .heartRate,
                  unit: .count().unitDivided(by: .minute()), unitLabel: "bpm"),
            .init(id: "hrv", title: "HRV", identifier: .heartRateVariabilitySDNN,
                  unit: .secondUnit(with: .milli), unitLabel: "ms"),
            .init(id: "walkingHeartRate", title: "Walking heart rate", identifier: .walkingHeartRateAverage,
                  unit: .count().unitDivided(by: .minute()), unitLabel: "bpm")
        ]
    }

    /// The queries run side by side; widget refreshes have little time.
    private func heartMetrics() async throws -> [DashboardMetric] {
        try await withThrowingTaskGroup(of: (Int, DashboardMetric).self) { group in
            for (index, heart) in HeartMetric.all.enumerated() {
                guard let type = HKQuantityType.quantityType(forIdentifier: heart.identifier) else { continue }
                group.addTask { (index, try await self.heartMetric(heart, type: type)) }
            }
            var metrics: [(Int, DashboardMetric)] = []
            for try await metric in group { metrics.append(metric) }
            return metrics.sorted { $0.0 < $1.0 }.map(\.1)
        }
    }

    private func heartMetric(_ heart: HeartMetric, type: HKQuantityType) async throws -> DashboardMetric {
        let sample = try await latestQuantitySample(for: type)
        let value = sample?.quantity.doubleValue(for: heart.unit)
        return DashboardMetric(
            id: heart.id,
            source: .appleHealth,
            title: heart.title,
            value: value.map { $0.formatted(.number.precision(.fractionLength(0))) } ?? "—",
            detail: sample.map { "\(heart.unitLabel) · \(Self.sampleTime($0.endDate))" } ?? "No measurement found",
            symbol: "heart.fill",
            tint: "pink",
            numericValue: value,
            observedAt: sample?.endDate,
            origin: sample?.sourceRevision.source.name
        )
    }

    /// A clock time today, else a date. Unlike "5 min ago", it stays correct
    /// while the widget shows a cached value.
    private static func sampleTime(_ date: Date) -> String {
        Calendar.current.isDateInToday(date)
            ? date.formatted(date: .omitted, time: .shortened)
            : date.formatted(.dateTime.day().month(.abbreviated))
    }

    private func todaySteps() async throws -> DashboardMetric {
        guard let type = HKQuantityType.quantityType(forIdentifier: .stepCount) else {
            throw ConnectorError.unavailable("Step count is not supported on this device.")
        }
        let start = Calendar.current.startOfDay(for: .now)
        let predicate = HKQuery.predicateForSamples(withStart: start, end: .now)
        let statistics = try await statistics(for: type, predicate: predicate, options: [.cumulativeSum, .separateBySource])
        let count = statistics?.sumQuantity()?.doubleValue(for: .count())
        let value = count?.formatted(.number.precision(.fractionLength(0))) ?? "—"
        return DashboardMetric(
            id: "steps",
            source: .appleHealth,
            title: "Steps",
            value: value,
            detail: count == nil ? "No step data available" : "Today · goal 10,000",
            symbol: "figure.walk",
            tint: "mint",
            numericValue: count,
            targetValue: 10000,
            observedAt: count == nil ? nil : .now,
            // Today's total must not be shown as tomorrow's.
            expiresAt: Calendar.current.date(byAdding: .day, value: 1, to: start),
            origin: Self.originNames(statistics?.sources)
        )
    }

    /// Health merges overlapping step samples; name every app that counted.
    private static func originNames(_ sources: [HKSource]?) -> String? {
        let names = Array(Set((sources ?? []).map(\.name))).sorted()
        return names.isEmpty ? nil : names.formatted(.list(type: .and))
    }

    private func statistics(
        for type: HKQuantityType,
        predicate: NSPredicate?,
        options: HKStatisticsOptions
    ) async throws -> HKStatistics? {
        try await withCheckedThrowingContinuation { continuation in
            let query = HKStatisticsQuery(
                quantityType: type,
                quantitySamplePredicate: predicate,
                options: options
            ) { _, result, error in
                if let error {
                    // Nothing recorded today is an empty total, not a failure.
                    if (error as? HKError)?.code == .errorNoData {
                        continuation.resume(returning: nil)
                    } else {
                        continuation.resume(throwing: error)
                    }
                } else {
                    continuation.resume(returning: result)
                }
            }
            healthStore.execute(query)
        }
    }

    private func latestQuantitySample(for type: HKQuantityType) async throws -> HKQuantitySample? {
        try await latestSample(for: type) as? HKQuantitySample
    }

    private func latestSample(for type: HKSampleType) async throws -> HKSample? {
        try await withCheckedThrowingContinuation { continuation in
            let query = HKSampleQuery(
                sampleType: type,
                predicate: nil,
                limit: 1,
                sortDescriptors: [NSSortDescriptor(key: HKSampleSortIdentifierEndDate, ascending: false)]
            ) { _, samples, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: samples?.first)
                }
            }
            healthStore.execute(query)
        }
    }
}

enum HealthFormat {
    /// "5.2 km" or "3.2 mi", following the iPhone's region.
    static func distance(meters: Double, locale: Locale = .current) -> String {
        Measurement(value: max(0, meters), unit: UnitLength.meters).formatted(
            .measurement(width: .abbreviated, usage: .road, numberFormatStyle: .number.precision(.fractionLength(1)))
                .locale(locale)
        )
    }

    /// "45m" or "1h 5m".
    static func duration(seconds: TimeInterval) -> String {
        let minutes = Int((max(0, seconds) / 60).rounded())
        return minutes < 60 ? "\(minutes)m" : "\(minutes / 60)h \(minutes % 60)m"
    }

    static func workoutName(_ type: HKWorkoutActivityType) -> String {
        switch type {
        case .running: return "Running"
        case .walking: return "Walking"
        case .hiking: return "Hiking"
        case .cycling: return "Cycling"
        case .swimming: return "Swimming"
        case .yoga: return "Yoga"
        case .traditionalStrengthTraining, .functionalStrengthTraining: return "Strength"
        case .highIntensityIntervalTraining: return "HIIT"
        case .rowing: return "Rowing"
        case .elliptical: return "Elliptical"
        case .crossCountrySkiing: return "Skiing"
        case .downhillSkiing: return "Downhill skiing"
        case .pilates: return "Pilates"
        case .cardioDance, .socialDance: return "Dance"
        case .coreTraining: return "Core"
        case .soccer: return "Football"
        case .tennis: return "Tennis"
        case .golf: return "Golf"
        default: return "Workout"
        }
    }
}
