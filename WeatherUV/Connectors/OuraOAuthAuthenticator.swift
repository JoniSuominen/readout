import Foundation
#if canImport(AuthenticationServices)
import AuthenticationServices
import CryptoKit
import Security
import UIKit
#endif

struct OuraAuthorizationRequest {
    let state: String
    let codeVerifier: String
    let codeChallenge: String

    func authorizationURL(clientID: String) throws -> URL {
        var components = URLComponents(url: OuraConfiguration.authorizationURL, resolvingAgainstBaseURL: false)!
        components.queryItems = [
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "client_id", value: clientID),
            URLQueryItem(name: "redirect_uri", value: OuraConfiguration.callbackURL.absoluteString),
            URLQueryItem(name: "scope", value: "daily"),
            URLQueryItem(name: "state", value: state),
            URLQueryItem(name: "code_challenge", value: codeChallenge),
            URLQueryItem(name: "code_challenge_method", value: "S256")
        ]
        guard let url = components.url else { throw OuraError.invalidResponse }
        return url
    }

    func validateCallback(_ callback: URL) throws -> OuraAuthorizationGrant {
        guard let components = URLComponents(url: callback, resolvingAgainstBaseURL: false),
              components.scheme == OuraConfiguration.callbackURL.scheme,
              components.host == OuraConfiguration.callbackURL.host,
              components.path == OuraConfiguration.callbackURL.path,
              components.user == nil, components.password == nil, components.port == nil,
              components.fragment == nil else { throw OuraError.invalidCallback }
        let items = components.queryItems ?? []
        let states = items.filter { $0.name == "state" }
        guard states.count == 1, states.first?.value == state else { throw OuraError.invalidCallback }
        let errors = items.filter { $0.name == "error" }
        if errors.count == 1 { throw OuraError.authorizationDenied }
        let codes = items.filter { $0.name == "code" }
        guard errors.isEmpty, codes.count == 1, let code = codes.first?.value,
              !code.isEmpty, code.count <= 4096,
              !code.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
            throw OuraError.invalidCallback
        }
        return OuraAuthorizationGrant(code: code, codeVerifier: codeVerifier)
    }

#if canImport(AuthenticationServices)
    static func generate() throws -> OuraAuthorizationRequest {
        let verifier = try secureRandom()
        let challenge = base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
        return try OuraAuthorizationRequest(state: secureRandom(), codeVerifier: verifier, codeChallenge: challenge)
    }

    private static func secureRandom() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw OuraError.invalidResponse
        }
        return base64URL(Data(bytes))
    }

    private static func base64URL(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
#endif
}

#if canImport(AuthenticationServices)
@MainActor
final class OuraOAuthAuthenticator: NSObject, OuraAuthenticating, ASWebAuthenticationPresentationContextProviding {
    private var session: ASWebAuthenticationSession?
    private var continuation: CheckedContinuation<OuraAuthorizationGrant, Error>?
    private var sessionID: UUID?

    func authenticate(configuration: OuraPublicConfiguration) async throws -> OuraAuthorizationGrant {
        guard session == nil else { throw OuraError.cancelled }
        let configuration = try configuration.validated()
        let request = try OuraAuthorizationRequest.generate()
        let url = try request.authorizationURL(clientID: configuration.clientID)
        let sessionID = UUID()
        try Task.checkCancellation()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                self.continuation = continuation
                self.sessionID = sessionID
                let session = ASWebAuthenticationSession(
                    url: url,
                    callbackURLScheme: OuraConfiguration.callbackURL.scheme
                ) { [weak self] callback, error in
                    Task { @MainActor in
                        guard let self else { return }
                        if let error {
                            let nsError = error as NSError
                            let cancelled = nsError.domain == ASWebAuthenticationSessionError.errorDomain &&
                                nsError.code == ASWebAuthenticationSessionError.canceledLogin.rawValue
                            self.finish(.failure(cancelled ? OuraError.cancelled : OuraError.authorizationDenied), sessionID: sessionID)
                        } else if let callback {
                            self.finish(Result { try request.validateCallback(callback) }, sessionID: sessionID)
                        } else {
                            self.finish(.failure(OuraError.invalidCallback), sessionID: sessionID)
                        }
                    }
                }
                session.presentationContextProvider = self
                session.prefersEphemeralWebBrowserSession = true
                self.session = session
                if !session.start() { finish(.failure(OuraError.authorizationDenied), sessionID: sessionID) }
            }
        } onCancel: {
            Task { @MainActor [weak self] in self?.cancelSession(sessionID: sessionID) }
        }
    }

    func cancel() async { cancelSession() }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows).first(where: \.isKeyWindow) ?? ASPresentationAnchor()
    }

    private func cancelSession(sessionID expected: UUID? = nil) {
        guard let sessionID, expected == nil || expected == sessionID else { return }
        let current = session
        finish(.failure(OuraError.cancelled), sessionID: sessionID)
        current?.cancel()
    }

    private func finish(_ result: Result<OuraAuthorizationGrant, Error>, sessionID: UUID) {
        guard self.sessionID == sessionID, let continuation else { return }
        self.continuation = nil
        self.sessionID = nil
        session = nil
        continuation.resume(with: result)
    }
}
#else
@MainActor
final class OuraOAuthAuthenticator: OuraAuthenticating {
    func authenticate(configuration: OuraPublicConfiguration) async throws -> OuraAuthorizationGrant {
        throw OuraError.authorizationDenied
    }
    func cancel() async {}
}
#endif
