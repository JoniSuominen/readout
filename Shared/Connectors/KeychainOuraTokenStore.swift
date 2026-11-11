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

/// Tokens remain in the main app's Keychain; widgets receive only metric snapshots.
struct OuraKeychainTokenStore: OuraTokenStoring {
    private let service: String
    private let account = "oura.oauth.tokens"

    init(service: String = Bundle.main.bundleIdentifier ?? "com.jonisuominen.readout") {
        self.service = service
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
        let status = SecItemUpdate(baseQuery as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var item = baseQuery
            attributes.forEach { item[$0.key] = $0.value }
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
