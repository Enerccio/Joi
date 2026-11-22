import Foundation
import Security

/// What the app has to call the server: a token the user made, or the OAuth pair of the device grant.
public struct Credentials: Codable, Hashable, Sendable {
    /// A token the user made in the server's UI (`joi_...`): used as it is.
    public var token: String?
    /// The OAuth access token (`joia_...`), short lived.
    public var accessToken: String?
    /// The OAuth refresh token (`joir_...`); each use gives a new pair.
    public var refreshToken: String?
    /// When the access token ends.
    public var accessExpires: Date?

    public init(token: String? = nil, accessToken: String? = nil, refreshToken: String? = nil, accessExpires: Date? = nil) {
        self.token = token
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.accessExpires = accessExpires
    }

    /// The `Authorization` header, nil for none.
    public var authorization: String? {
        if let token { return "Bearer " + token }
        if let accessToken { return "Bearer " + accessToken }
        return nil
    }
}

/// Where the credentials are kept: the Keychain on the phone, memory in tests.
public protocol CredentialStore: Sendable {
    func load() -> Credentials
    func save(_ credentials: Credentials)
}

public final class MemoryCredentialStore: CredentialStore, @unchecked Sendable {
    private let lock = NSLock()
    private var credentials: Credentials

    public init(_ credentials: Credentials = Credentials()) {
        self.credentials = credentials
    }

    public func load() -> Credentials {
        lock.withLock { credentials }
    }

    public func save(_ credentials: Credentials) {
        lock.withLock { self.credentials = credentials }
    }
}

/// The credentials as one generic password of the Keychain (`service`, `account`), JSON in it. Only on this device, and not
/// before its first unlock (`AfterFirstUnlockThisDeviceOnly`, so that a node that runs in the background can still sign in).
public final class KeychainCredentialStore: CredentialStore, @unchecked Sendable {
    private let service: String
    private let account: String

    public init(service: String = "joi", account: String = "credentials") {
        self.service = service
        self.account = account
    }

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    public func load() -> Credentials {
        var search = query
        search[kSecReturnData as String] = true
        search[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(search as CFDictionary, &item) == errSecSuccess, let data = item as? Data,
              let credentials = try? JSONDecoder().decode(Credentials.self, from: data) else {
            return Credentials()
        }
        return credentials
    }

    public func save(_ credentials: Credentials) {
        guard let data = try? JSONEncoder().encode(credentials) else { return }
        SecItemDelete(query as CFDictionary)
        var add = query
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }
}
