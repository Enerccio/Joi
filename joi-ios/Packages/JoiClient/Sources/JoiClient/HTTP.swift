import Foundation

/// One HTTP call, so that tests have none of the network.
public protocol HTTPTransport: Sendable {
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse)
    /// A call whose answer is written to a file (the caller owns the file) instead of held in memory: a model is hundreds of megabytes.
    func download(_ request: URLRequest) async throws -> (URL, HTTPURLResponse)
    /// A call whose body is read from a file (never held in memory whole): an image of the user.
    func upload(_ request: URLRequest, file: URL) async throws -> (Data, HTTPURLResponse)
}

extension HTTPTransport {
    /// For a transport that has no files (a fake): the answer, written to one.
    public func download(_ request: URLRequest) async throws -> (URL, HTTPURLResponse) {
        let (data, response) = try await send(request)
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".download")
        try data.write(to: file)
        return (file, response)
    }

    /// For a transport that has no files (a fake): the file as the body.
    public func upload(_ request: URLRequest, file: URL) async throws -> (Data, HTTPURLResponse) {
        var copy = request
        copy.httpBody = try Data(contentsOf: file)
        return try await send(copy)
    }
}

public struct URLSessionTransport: HTTPTransport {
    private let session: URLSession

    public init(session: URLSession = URLSession(configuration: .ephemeral)) {
        self.session = session
    }

    public func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        return (data, http)
    }

    public func upload(_ request: URLRequest, file: URL) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.upload(for: request, fromFile: file)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        return (data, http)
    }

    public func download(_ request: URLRequest) async throws -> (URL, HTTPURLResponse) {
        let (file, response) = try await session.download(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        return (file, http)
    }
}

/// The server answered with an error: `code` is the API's (`not_found`, `no_body`...) or OAuth's (`authorization_pending`...), else `http_<status>`.
public struct ServerError: Error, Equatable, CustomStringConvertible, Sendable {
    public let status: Int
    public let code: String
    public let message: String

    public init(status: Int, code: String, message: String) {
        self.status = status
        self.code = code
        self.message = message
    }

    public var description: String { "\(status) \(code): \(message)" }
}
