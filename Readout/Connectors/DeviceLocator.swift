import CoreLocation
import Foundation

/// One-shot, approximate device location for the weather forecast.
@MainActor
final class DeviceLocator: NSObject, DeviceLocating, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var authorizationContinuation: CheckedContinuation<CLAuthorizationStatus, Never>?
    private var locationContinuation: CheckedContinuation<CLLocation, Error>?
    private var requestID = UUID()

    /// A fix this recent is reused, so refreshes every few minutes don't wake
    /// location services. The forecast grid is coarser than a 15-minute move.
    private static let maximumFixAge: TimeInterval = 15 * 60
    /// Background refresh has about 30 seconds; give up early and forecast
    /// for the last stored coordinate instead.
    private static let fixTimeout: Duration = .seconds(15)

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyKilometer
    }

    func currentCoordinate() async throws -> DeviceCoordinate {
        let status = await authorizationStatus()
        guard status == .authorizedWhenInUse || status == .authorizedAlways else {
            throw ConnectorError.authorizationDenied("Allow location access in Settings, or choose a city for weather.")
        }
        if let cached = manager.location, Self.isRecentCoarseFix(cached) {
            return DeviceCoordinate(latitude: cached.coordinate.latitude, longitude: cached.coordinate.longitude)
        }
        // A request already waiting for a fix is superseded by this one.
        locationContinuation?.resume(throwing: CancellationError())
        let id = UUID()
        requestID = id
        let location = try await withCheckedThrowingContinuation { continuation in
            locationContinuation = continuation
            manager.requestLocation()
            Task { @MainActor [weak self] in
                try? await Task.sleep(for: Self.fixTimeout)
                guard let self, self.requestID == id, let pending = self.locationContinuation else { return }
                self.locationContinuation = nil
                self.manager.stopUpdatingLocation()
                pending.resume(throwing: CLError(.locationUnknown))
            }
        }
        return DeviceCoordinate(latitude: location.coordinate.latitude, longitude: location.coordinate.longitude)
    }

    private static func isRecentCoarseFix(_ location: CLLocation, now: Date = .now) -> Bool {
        location.horizontalAccuracy >= 0 && location.horizontalAccuracy <= 3_000 &&
            now.timeIntervalSince(location.timestamp) < maximumFixAge
    }

    private func authorizationStatus() async -> CLAuthorizationStatus {
        let status = manager.authorizationStatus
        guard status == .notDetermined else { return status }
        return await withCheckedContinuation { continuation in
            authorizationContinuation = continuation
            manager.requestWhenInUseAuthorization()
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            guard status != .notDetermined, let continuation = self.authorizationContinuation else { return }
            self.authorizationContinuation = nil
            continuation.resume(returning: status)
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        Task { @MainActor in
            self.locationContinuation?.resume(returning: location)
            self.locationContinuation = nil
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in
            self.locationContinuation?.resume(throwing: error)
            self.locationContinuation = nil
        }
    }
}
