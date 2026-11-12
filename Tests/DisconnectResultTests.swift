import Foundation
import XCTest
@testable import Readout

@MainActor
final class DisconnectResultTests: XCTestCase {
    private let date = Date(timeIntervalSince1970: 1_791_590_400)

    func testSystemSourceDisconnectionExplainsRemainingIOSPermissions() {
        let result = DisconnectResult.localOnly
        XCTAssertTrue(result.localCredentialsCleared)
        XCTAssertEqual(result.remoteAuthorization, .notApplicable)
        XCTAssertFalse(result.requiresAttention)
        XCTAssertNil(result.warningMessage)
        XCTAssertTrue(result.statusMessage.contains("iOS Settings"))
        XCTAssertTrue(result.statusMessage.contains("remain unchanged"))
    }

    func testSuccessfulOuraRevocationConfirmsBothOperations() async {
        let store = tokenStore()
        let log = OuraRequestLog()
        let connector = makeConnector(store: store) { request in
            log.record(request)
            XCTAssertEqual(request.url?.path, "/oauth/revoke")
            return (204, "")
        }
        let result = await connector.disconnect()
        XCTAssertEqual(result, .revoked)
        XCTAssertNil(result.warningMessage)
        XCTAssertNil(store.tokens)
        XCTAssertEqual(store.deleteCount, 1)
        XCTAssertEqual(log.requests.count, 1)
    }

    func testKeychainDeletionFailureIsReportedEvenWhenRemoteAccessIsRevoked() async {
        let store = tokenStore()
        store.deleteError = OuraError.secureStorage
        let log = OuraRequestLog()
        let connector = makeConnector(store: store) { request in
            log.record(request)
            return (204, "")
        }
        let result = await connector.disconnect()
        XCTAssertFalse(result.localCredentialsCleared)
        XCTAssertEqual(result.remoteAuthorization, .revoked)
        XCTAssertTrue(result.requiresAttention)
        XCTAssertTrue(result.warningMessage?.contains("could not be removed") == true)
        XCTAssertNotNil(store.tokens, "Failed Keychain deletion must not be presented as success")

        do {
            _ = try await connector.refresh()
            XCTFail("The in-memory connection must stop even if Keychain deletion fails")
        } catch {
            XCTAssertEqual(error as? OuraError, .notConnected)
        }
        XCTAssertEqual(log.requests.count, 1, "Remaining stored credentials must not be reused for API requests")
    }

    func testDisconnectRetryRereadsCredentialsWithoutKeepingAnInMemoryRetryToken() async {
        let store = tokenStore()
        store.deleteError = OuraError.secureStorage
        let log = OuraRequestLog()
        let connector = makeConnector(store: store) { request in
            log.record(request)
            return (204, "")
        }
        let initial = await connector.disconnect()
        XCTAssertFalse(initial.localCredentialsCleared)
        store.deleteError = nil
        let retried = await connector.disconnect()
        XCTAssertEqual(retried, .revoked)
        XCTAssertNil(store.tokens)
        XCTAssertEqual(log.requests.count, 2)
    }

    func testKeychainReadFailureStillAttemptsDeletionAndReportsRemoteUnknown() async {
        let store = tokenStore()
        store.loadError = OuraError.secureStorage
        let connector = makeConnector(store: store) { _ in
            XCTFail("A token that could not be read must not be sent for revocation")
            return (204, "")
        }
        let result = await connector.disconnect()
        XCTAssertTrue(result.localCredentialsCleared)
        XCTAssertEqual(result.remoteAuthorization, .unconfirmed)
        XCTAssertNotNil(result.warningMessage)
        XCTAssertNil(store.tokens)
        XCTAssertEqual(store.deleteCount, 1)
    }

    func testKeychainReadAndDeletionFailuresRemainSeparateFromRemoteConfirmation() async {
        let store = tokenStore()
        store.loadError = OuraError.secureStorage
        store.deleteError = OuraError.secureStorage
        let connector = makeConnector(store: store) { _ in
            XCTFail("No readable token is available")
            return (204, "")
        }
        let result = await connector.disconnect()
        XCTAssertFalse(result.localCredentialsCleared)
        XCTAssertEqual(result.remoteAuthorization, .unconfirmed)
        XCTAssertTrue(result.requiresAttention)
        XCTAssertNotNil(store.tokens)
    }

    func testOfflineRevocationDoesNotKeepCredentialsAndReportsUnconfirmedAccess() async {
        let store = tokenStore()
        let connector = makeConnector(store: store) { _ in
            throw URLError(.notConnectedToInternet)
        }
        let result = await connector.disconnect()
        XCTAssertTrue(result.localCredentialsCleared)
        XCTAssertEqual(result.remoteAuthorization, .unconfirmed)
        XCTAssertNil(store.tokens)
        XCTAssertTrue(result.warningMessage?.contains("could not be confirmed") == true)
        XCTAssertFalse(result.statusMessage.contains("access-old"))
        XCTAssertFalse(result.statusMessage.contains("refresh-old"))
    }

    func testUnexpectedRevokeResponseDoesNotClaimConfirmedProviderRevocation() async {
        let store = tokenStore()
        let connector = makeConnector(store: store) { _ in
            (200, #"{"error":"service_unavailable"}"#)
        }
        let result = await connector.disconnect()
        XCTAssertTrue(result.localCredentialsCleared)
        XCTAssertEqual(result.remoteAuthorization, .unconfirmed)
        XCTAssertNil(store.tokens)
    }

    func testNoSavedCredentialsDoesNotClaimProviderAuthorizationWasRevoked() async {
        let store = OuraMemoryTokenStore()
        let connector = makeConnector(store: store) { _ in
            XCTFail("There are no credentials to revoke")
            return (204, "")
        }
        let result = await connector.disconnect()
        XCTAssertEqual(result, .noStoredCredentials)
        XCTAssertFalse(result.requiresAttention)
        XCTAssertTrue(result.statusMessage.contains("No saved Oura credentials"))
        XCTAssertFalse(result.statusMessage.contains("was revoked"))
        XCTAssertEqual(store.deleteCount, 1)
    }

    func testMissingBrokerClearsCredentialsWithoutClaimingRemoteRevocation() async {
        let store = tokenStore()
        let connector = OuraAPIConnector(configuration: OuraConfiguration(brokerURL: nil),
                                          tokenStorage: store, authenticator: OuraMockAuthenticator())
        let result = await connector.disconnect()
        XCTAssertTrue(result.localCredentialsCleared)
        XCTAssertEqual(result.remoteAuthorization, .unconfirmed)
        XCTAssertNil(store.tokens)
    }

    func testDisconnectAndTaskCancellationBlockLateAuthorizationTokenWrites() async {
        let started = expectation(description: "Authorization is suspended")
        let authenticator = DisconnectSuspendedAuthenticator(onStart: { started.fulfill() })
        let store = OuraMemoryTokenStore()
        let connector = makeConnector(store: store, authenticator: authenticator) { request in
            XCTAssertEqual(request.url?.path, "/config", "A cancelled grant must never reach token exchange")
            return (200, #"{"client_id":"public-client","redirect_uri":"com.jonisuominen.readout://oauth/oura"}"#)
        }
        let authorization = Task { try await connector.authorize() }
        await fulfillment(of: [started], timeout: 2)
        authorization.cancel()
        let result = await connector.disconnect()
        authenticator.complete()
        do {
            try await authorization.value
            XCTFail("Late authorization must not restore tokens after disconnect")
        } catch {
            XCTAssertTrue(error is CancellationError || error as? OuraError == .cancelled)
        }
        XCTAssertEqual(result, .noStoredCredentials)
        XCTAssertNil(store.tokens)
        XCTAssertEqual(store.saveCount, 0)
        XCTAssertEqual(authenticator.cancelCount, 1)
    }

    func testDisconnectCancelsPendingRefreshAndPreventsCredentialRestoration() async {
        let started = expectation(description: "Token refresh is pending")
        DisconnectPendingRefreshProtocol.onRefresh = { started.fulfill() }
        let sessionConfiguration = URLSessionConfiguration.ephemeral
        sessionConfiguration.protocolClasses = [DisconnectPendingRefreshProtocol.self]
        let store = tokenStore(expiresAt: date.addingTimeInterval(-1))
        let connector = OuraAPIConnector(
            configuration: OuraConfiguration(brokerURL: URL(string: "https://broker.example.com")),
            session: URLSession(configuration: sessionConfiguration), tokenStorage: store,
            authenticator: OuraMockAuthenticator(), now: { self.date }
        )
        let refresh = Task { try await connector.refresh() }
        await fulfillment(of: [started], timeout: 2)
        let result = await connector.disconnect()
        do {
            _ = try await refresh.value
            XCTFail("An interrupted token refresh must not restore credentials")
        } catch {
            XCTAssertNil(store.tokens)
        }
        XCTAssertEqual(result, .revoked)
        XCTAssertEqual(store.saveCount, 0)
        DisconnectPendingRefreshProtocol.onRefresh = nil
    }

    private func tokenStore(expiresAt: Date? = nil) -> OuraMemoryTokenStore {
        OuraMemoryTokenStore(tokens: OuraTokens(accessToken: "access-old", refreshToken: "refresh-old",
                                               expiresAt: expiresAt ?? date.addingTimeInterval(3_600), scope: "daily"))
    }

    private func makeConnector(store: OuraMemoryTokenStore,
                               authenticator: (any OuraAuthenticating)? = nil,
                               handler: @escaping (URLRequest) throws -> (Int, String)) -> OuraAPIConnector {
        OuraTestURLProtocol.setHandler(handler)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [OuraTestURLProtocol.self]
        return OuraAPIConnector(configuration: OuraConfiguration(brokerURL: URL(string: "https://broker.example.com")),
                                 session: URLSession(configuration: configuration), tokenStorage: store,
                                 authenticator: authenticator ?? OuraMockAuthenticator(), now: { self.date })
    }
}

@MainActor
private final class DisconnectSuspendedAuthenticator: OuraAuthenticating {
    private let onStart: () -> Void
    private var continuation: CheckedContinuation<OuraAuthorizationGrant, Error>?
    private(set) var cancelCount = 0

    init(onStart: @escaping () -> Void) { self.onStart = onStart }
    func authenticate(configuration: OuraPublicConfiguration) async throws -> OuraAuthorizationGrant {
        try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            onStart()
        }
    }
    // Simulate a late browser callback that arrives after cancellation.
    func cancel() async { cancelCount += 1 }
    func complete() {
        continuation?.resume(returning: OuraAuthorizationGrant(code: "late-code", codeVerifier: String(repeating: "a", count: 43)))
        continuation = nil
    }
}

private final class DisconnectPendingRefreshProtocol: URLProtocol {
    static var onRefresh: (() -> Void)?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        if request.url?.path == "/oauth/refresh" {
            Self.onRefresh?()
        } else if request.url?.path == "/oauth/revoke" {
            let response = HTTPURLResponse(url: request.url!, statusCode: 204, httpVersion: "HTTP/1.1", headerFields: nil)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocolDidFinishLoading(self)
        } else {
            client?.urlProtocol(self, didFailWithError: OuraError.invalidResponse)
        }
    }
    override func stopLoading() {}
}
