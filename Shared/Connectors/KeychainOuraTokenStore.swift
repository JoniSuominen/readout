import Foundation
#if canImport(Security)
import Security
#endif

struct OuraTokens: Codable, Equatable {
    let accessToken: String
    let refreshToken: String
    let expiresAt: Date
    let scope: String?
}

protocol OuraTokenStoring {
    func load() throws -> OuraTokens?
    func save(_ tokens: OuraTokens) throws
    func delete() throws
}

/// Tokens live in the Keychain group the app shares with its widget, so the
/// widget can read Oura with the saved access token. Only the app writes or
/// deletes them. A build without the shared group falls back to the app's own
/// Keychain, and Oura then refreshes only in the app.
struct OuraKeychainTokenStore: OuraTokenStoring {
    private let service: String
    private let accessGroup: String?
    private let account = "oura.oauth.tokens"

    /// The service is fixed: the widget's bundle identifier differs from the app's.
    init(service: String = "com.jonisuominen.readout", accessGroup: String? = DashboardStore.appGroupID) {
        self.service = service
        self.accessGroup = accessGroup
    }

    func load() throws -> OuraTokens? {
#if canImport(Security)
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data,
              let tokens = try? JSONDecoder().decode(OuraTokens.self, from: data) else {
            throw OuraError.secureStorage
        }
        return tokens
#else
        throw OuraError.secureStorage
#endif
    }

    func save(_ tokens: OuraTokens) throws {
#if canImport(Security)
        let data = try JSONEncoder().encode(tokens)
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        // Without an access group the query matches the item wherever it is.
        let status = SecItemUpdate(baseQuery as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var item = baseQuery
            attributes.forEach { item[$0.key] = $0.value }
            if let accessGroup {
                var shared = item
                shared[kSecAttrAccessGroup as String] = accessGroup
                if SecItemAdd(shared as CFDictionary, nil) == errSecSuccess { return }
            }
            guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else {
                throw OuraError.secureStorage
            }
        } else if status != errSecSuccess {
            throw OuraError.secureStorage
        }
#else
        throw OuraError.secureStorage
#endif
    }

    func delete() throws {
#if canImport(Security)
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw OuraError.secureStorage
        }
#else
        throw OuraError.secureStorage
#endif
    }

#if canImport(Security)
    private var baseQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account,
         kSecAttrSynchronizable as String: false]
    }
#endif
}
