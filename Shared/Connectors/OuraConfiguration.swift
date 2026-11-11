import Foundation

struct OuraConfiguration {
    static let callbackURL = URL(string: "com.jonisuominen.readout://oauth/oura")!
    static let authorizationURL = URL(string: "https://cloud.ouraring.com/oauth/authorize")!
    static let officialAPIURL = URL(string: "https://api.ouraring.com/v2/usercollection")!

    let brokerURL: URL?
    let apiURL: URL

    init(brokerURL: URL?, apiURL: URL = Self.officialAPIURL) {
        self.brokerURL = Self.isSecureEndpoint(brokerURL) ? brokerURL : nil
        self.apiURL = apiURL
    }

    static var current: OuraConfiguration {
        let value = (Bundle.main.object(forInfoDictionaryKey: "OuraBrokerURL") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return OuraConfiguration(brokerURL: URL(string: value))
    }

    var isConfigured: Bool { brokerURL != nil && Self.isSecureEndpoint(apiURL) }
    static var isConfigured: Bool { current.isConfigured }

    static var integrationStatus: String {
        isConfigured
            ? "Connect your Oura account for readiness and sleep."
            : "Use Oura sleep shared with Apple Health."
    }

    static func isSecureEndpoint(_ url: URL?) -> Bool {
        guard let url, url.scheme?.lowercased() == "https",
              let host = url.host, !host.isEmpty,
              url.user == nil, url.password == nil,
              url.query == nil, url.fragment == nil else { return false }
        return !host.contains("$") && !host.contains("(") && !host.contains(")")
    }

    func brokerEndpoint(_ path: String) throws -> URL {
        guard isConfigured, let brokerURL else { throw OuraError.notConfigured }
        return path.split(separator: "/").reduce(brokerURL) {
            $0.appendingPathComponent(String($1))
        }
    }
}

enum OuraError: LocalizedError, Equatable {
    case notConfigured
    case notConnected
    case cancelled
    case authorizationDenied
    case invalidCallback
    case invalidResponse
    case secureStorage
    case permissionRequired
    case rateLimited
    case serviceUnavailable

    var errorDescription: String? {
        switch self {
        case .notConfigured:
            return "Direct Oura connection is unavailable. You can use Oura sleep through Apple Health."
        case .notConnected:
            return "Connect your Oura account to update readiness and sleep."
        case .cancelled:
            return "Oura connection was cancelled."
        case .authorizationDenied:
            return "Oura access was not granted. Connect again and allow daily data access."
        case .invalidCallback:
            return "Oura sign-in could not be verified. Please connect again."
        case .invalidResponse:
            return "Oura returned an unexpected response. Please try again."
        case .secureStorage:
            return "Your Oura connection could not be saved securely. Please try again."
        case .permissionRequired:
            return "Oura daily data permission is required. Reconnect Oura and allow daily data access."
        case .rateLimited:
            return "Oura is receiving too many requests. Please try again shortly."
        case .serviceUnavailable:
            return "Oura is temporarily unavailable. Please try again later."
        }
    }
}
