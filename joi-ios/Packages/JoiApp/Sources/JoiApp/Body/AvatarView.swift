#if canImport(UIKit)
import SwiftUI
import UniformTypeIdentifiers
import WebKit

/// Her body: the renderer page (three.js and three-vrm, `joi-node/src/main/renderer`) in a web view. There is no server: the page and her
/// files are read by a handler of the scheme `joi-body://app/` from the app's bundle and from the files the app unpacked, and the app
/// drives the page by calling `joiReceive` (what `RendererBridge` sends) while the page tells through `webkit.messageHandlers.joi`.
public struct AvatarView: UIViewRepresentable {
    let bridge: RendererBridge
    let page: URL

    public init(bridge: RendererBridge, page: URL) {
        self.bridge = bridge
        self.page = page
    }

    public func makeCoordinator() -> Coordinator {
        Coordinator(bridge: bridge, page: page)
    }

    public func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.setURLSchemeHandler(context.coordinator.handler, forURLScheme: SchemeHandler.scheme)
        configuration.userContentController.add(context.coordinator, name: "joi")
        configuration.allowsInlineMediaPlayback = true
        let web = WKWebView(frame: .zero, configuration: configuration)
        web.isOpaque = false
        web.backgroundColor = UIColor(red: 0.067, green: 0.067, blue: 0.078, alpha: 1)
        web.scrollView.isScrollEnabled = false
        web.scrollView.backgroundColor = web.backgroundColor
        web.isInspectable = true
        context.coordinator.attach(web)
        web.load(URLRequest(url: URL(string: "\(SchemeHandler.scheme)://app/index.html")!))
        return web
    }

    public func updateUIView(_ web: WKWebView, context: Context) {}

    public static func dismantleUIView(_ web: WKWebView, coordinator: Coordinator) {
        coordinator.detach(web)
    }

    @MainActor public final class Coordinator: NSObject, WKScriptMessageHandler {
        let bridge: RendererBridge
        let handler: SchemeHandler

        init(bridge: RendererBridge, page: URL) {
            self.bridge = bridge
            handler = SchemeHandler(page: page, files: bridge.files)
        }

        func attach(_ web: WKWebView) {
            bridge.send = { [weak web] message in
                guard let web, let data = try? JSONEncoder().encode(message), let json = String(data: data, encoding: .utf8) else { return }
                web.evaluateJavaScript("window.joiReceive && window.joiReceive(\(json))", completionHandler: nil)
            }
        }

        func detach(_ web: WKWebView) {
            bridge.send = nil
            web.configuration.userContentController.removeScriptMessageHandler(forName: "joi")
        }

        public func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            guard let body = message.body as? [String: Any], let type = body["type"] as? String else { return }
            switch type {
            case "ready":
                bridge.pageReady()
            case "loaded":
                bridge.modelLoaded()
            case "ended":
                if let id = body["id"] as? Int { bridge.ended(id) }
            case "error":
                NSLog("joi renderer: animation %@ could not play: %@", String(describing: body["id"]), String(describing: body["message"]))
                if let id = body["id"] as? Int { bridge.ended(id) }
            case "log":
                NSLog("joi renderer: %@", String(describing: body["message"] ?? ""))
            default:
                break
            }
        }
    }
}

/// Serves `joi-body://app/...`: the renderer page from the bundle, and her files (`body/model.vrm`, `body/clip/<uuid>.vrma`) from the disk, in
/// blocks (a model is hundreds of megabytes), as long as WebKit wants them.
@MainActor final class SchemeHandler: NSObject, WKURLSchemeHandler {
    static let scheme = "joi-body"
    private static let block = 1024 * 1024

    private let page: URL
    private let files: BodyFilesBox
    /// The tasks WebKit has not stopped: answering one that was stopped raises an exception.
    private var live = Set<ObjectIdentifier>()

    init(page: URL, files: BodyFilesBox) {
        self.page = page
        self.files = files
    }

    func webView(_ webView: WKWebView, start task: any WKURLSchemeTask) {
        guard let url = task.request.url else {
            task.didFailWithError(URLError(.badURL))
            return
        }
        var path = url.path
        while path.hasPrefix("/") { path.removeFirst() }
        if path.isEmpty { path = "index.html" }
        let file: URL?
        if path.hasPrefix("body/") {
            file = files.file(path)
        } else if !path.contains("..") {
            file = page.appendingPathComponent(path)
        } else {
            file = nil
        }
        guard let file, let size = (try? FileManager.default.attributesOfItem(atPath: file.path))?[.size] as? Int,
              let handle = try? FileHandle(forReadingFrom: file) else {
            task.didReceive(HTTPURLResponse(url: url, statusCode: 404, httpVersion: "HTTP/1.1", headerFields: ["Content-Length": "0"])!)
            task.didFinish()
            return
        }
        let id = ObjectIdentifier(task)
        live.insert(id)
        let type = UTType(filenameExtension: file.pathExtension)?.preferredMIMEType ?? Self.type(of: path)
        task.didReceive(HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: [
            "Content-Type": type, "Content-Length": String(size), "Access-Control-Allow-Origin": "*", "Cache-Control": "no-cache",
        ])!)
        Task { @MainActor in
            defer { try? handle.close() }
            while live.contains(id) {
                guard let data = try? handle.read(upToCount: Self.block), !data.isEmpty else { break }
                task.didReceive(data)
                await Task.yield()
            }
            if live.remove(id) != nil {
                task.didFinish()
            }
        }
    }

    func webView(_ webView: WKWebView, stop task: any WKURLSchemeTask) {
        live.remove(ObjectIdentifier(task))
    }

    private static func type(of path: String) -> String {
        switch (path as NSString).pathExtension {
        case "html": "text/html; charset=utf-8"
        case "js", "mjs": "text/javascript; charset=utf-8"
        case "css": "text/css; charset=utf-8"
        case "json": "application/json"
        case "vrm", "vrma", "glb": "model/gltf-binary"
        default: "application/octet-stream"
        }
    }
}
#endif
