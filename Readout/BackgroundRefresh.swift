import BackgroundTasks
import Foundation

enum ReadoutBackgroundRefresh {
    static let identifier = "com.jonisuominen.readout.refresh"

    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            guard let refreshTask = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            let operation = Task { @MainActor in
                schedule()
                let manager = ConnectorManager.shared
                await manager.refreshStaleSources(olderThan: WidgetDataRefresher.passiveMinimumAge)
                let succeeded = !Task.isCancelled && manager.connectedSources.allSatisfy {
                    if case .failed = manager.activity(for: $0) { return false }
                    return true
                }
                refreshTask.setTaskCompleted(success: succeeded)
            }
            refreshTask.expirationHandler = { operation.cancel() }
        }
    }

    static func schedule() {
        guard !DashboardStore.connectedSources.isEmpty else { return }
        // iOS chooses the execution time; this requests an opportunity, not a timer.
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier)
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date.now.addingTimeInterval(30 * 60)
        do { try BGTaskScheduler.shared.submit(request) }
        catch { /* Foreground/manual refresh still works when scheduling is unavailable. */ }
    }

    static func cancel() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier)
    }
}
