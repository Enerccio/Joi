import Foundation
@testable import JoiClient

/// A server that answers by a closure and remembers what it was asked.
final class FakeHTTP: HTTPTransport, @unchecked Sendable {
    struct Call: Sendable {
        let method: String
        let path: String
        let authorization: String?
        let contentType: String?
        let body: String
    }

    private let lock = NSLock()
    private var calls: [Call] = []
    private let handler: @Sendable (Call) -> (Int, String)

    init(_ handler: @escaping @Sendable (Call) -> (Int, String)) {
        self.handler = handler
    }

    var recorded: [Call] { lock.withLock { calls } }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let url = request.url!
        let parts = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        let call = Call(method: request.httpMethod ?? "GET", path: parts.percentEncodedPath + (parts.percentEncodedQuery.map { "?" + $0 } ?? ""),
                        authorization: request.value(forHTTPHeaderField: "Authorization"),
                        contentType: request.value(forHTTPHeaderField: "Content-Type"),
                        body: String(decoding: request.httpBody ?? Data(), as: UTF8.self))
        lock.withLock { calls.append(call) }
        let (status, body) = handler(call)
        // a call takes a moment, so that calls made together do overlap
        try await Task.sleep(for: .milliseconds(20))
        return (Data(body.utf8), HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: nil)!)
    }
}
