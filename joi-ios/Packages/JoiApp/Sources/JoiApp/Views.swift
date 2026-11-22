import CoreImage.CIFilterBuiltins
import SwiftUI
import JoiProtocol

/// The app's only view: the Xcode project's `App` shows it.
public struct RootView: View {
    @State private var model = AppModel()

    public init() {}

    public var body: some View {
        Group {
            switch model.phase {
            case .setup:
                SetupView(model: model)
            case .working(let what):
                VStack(spacing: 16) {
                    ProgressView()
                    Text(what).foregroundStyle(.secondary)
                    Button("Cancel") { model.cancel() }
                }
            case .pairing(let grant):
                PairingView(grant: grant, model: model)
            case .ready(let chat, let avatar):
                ChatView(chat: chat, avatar: avatar, model: model)
            }
        }
        .task { model.launch() }
        .onOpenURL { model.open($0) }
    }
}

struct SetupView: View {
    @Bindable var model: AppModel

    var body: some View {
        Form {
            Section {
                TextField("joi.example.com", text: $model.serverText)
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                    #endif
                    .autocorrectionDisabled()
                    .onSubmit { model.connect() }
            } header: {
                Text("Your Joi server")
            } footer: {
                if let error = model.error { Text(error).foregroundStyle(.red) }
            }
            Button("Connect") { model.connect() }
                .disabled(model.serverText.trimmingCharacters(in: .whitespaces).isEmpty)
        }
    }
}

struct PairingView: View {
    let grant: DeviceAuthorization
    let model: AppModel

    var body: some View {
        VStack(spacing: 20) {
            Text("Approve this phone").font(.title2.bold())
            Text("Open the Joi server, User Settings → Access → Connect a device, and enter the code:")
                .multilineTextAlignment(.center).foregroundStyle(.secondary)
            Text(grant.userCode).font(.system(.largeTitle, design: .monospaced).bold()).textSelection(.enabled)
            if let image = QRCode.image(grant.verificationUriComplete) {
                image.interpolation(.none).resizable().scaledToFit().frame(width: 180, height: 180)
            }
            if let link = URL(string: grant.verificationUriComplete) {
                Link("Or open the link", destination: link)
            }
            ProgressView("Waiting for the approval")
            Button("Cancel") { model.cancel() }
        }
        .padding()
    }
}

enum QRCode {
    static func image(_ text: String) -> Image? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return Image(decorative: cg, scale: 1)
    }
}

/// Her body above, the chat below (side by side when the phone lies down). The body takes half of the height, less while the keyboard is up.
struct ChatView: View {
    let chat: ChatModel
    let avatar: AvatarModel
    let model: AppModel
    @FocusState private var focused: Bool
    @State private var showingParts = false

    var body: some View {
        GeometryReader { geometry in
            if geometry.size.width > geometry.size.height {
                HStack(spacing: 0) {
                    AvatarPane(avatar: avatar).frame(width: geometry.size.width * 0.45).overlay(alignment: .topTrailing) { controls }
                    ChatColumn(chat: chat, model: model, focused: $focused)
                }
            } else {
                VStack(spacing: 0) {
                    AvatarPane(avatar: avatar).frame(height: geometry.size.height * (focused ? 0.25 : 0.5)).overlay(alignment: .topTrailing) { controls }
                    ChatColumn(chat: chat, model: model, focused: $focused)
                }
                .animation(.easeInOut(duration: 0.25), value: focused)
            }
        }
        .sheet(isPresented: $showingParts) { PartsView(chat: chat, avatar: avatar) }
    }

    /// Over her body: what she wears (when she has parts to switch) and the menu.
    private var controls: some View {
        HStack(spacing: 14) {
            if !avatar.parts.isEmpty {
                Button { showingParts = true } label: { Image(systemName: "tshirt") }
                    .accessibilityLabel("Her look")
            }
            Menu {
                Button("Sign out", role: .destructive) { model.signOut() }
            } label: { Image(systemName: "ellipsis.circle") }
        }
        .font(.title3)
        .foregroundStyle(.white.opacity(0.8))
        .padding(12)
    }
}

/// The parts of her body the user can put in another state (take her hat off): the server keeps it, she may react, and may refuse in character.
struct PartsView: View {
    let chat: ChatModel
    let avatar: AvatarModel
    @State private var note: String?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if let note {
                    Text(note).font(.footnote).foregroundStyle(.secondary)
                }
                ForEach(avatar.parts, id: \.id) { part in
                    if let states = part.states, states.count > 1 {
                        Picker(part.name, selection: Binding(get: { part.state ?? "" }, set: { choose(part, $0) })) {
                            ForEach(states, id: \.id) { Text($0.name).tag($0.id) }
                        }
                        .disabled(chat.switchingPart != nil)
                    } else {
                        LabeledContent(part.name, value: part.stateName ?? "")
                    }
                }
            }
            .navigationTitle("Her look")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .overlay { if chat.switchingPart != nil { ProgressView() } }
        }
        .presentationDetents([.medium, .large])
    }

    private func choose(_ part: BodyPart, _ state: String) {
        guard state != part.state else { return }
        note = nil
        Task {
            switch await chat.switchPart(part.id, to: state) {
            case .kept: break // her body is read again, and the list shows what it is now
            case .refused(let reason): note = reason.map { "She did not allow it: \($0)" } ?? "She did not allow it."
            case .failed(let why): note = "Not changed: " + why
            }
        }
    }
}

/// Her body, or what is going on while it is not there.
struct AvatarPane: View {
    let avatar: AvatarModel

    var body: some View {
        ZStack {
            Color(red: 0.067, green: 0.067, blue: 0.078)
            #if canImport(UIKit)
            // the page stays while her body is loaded again
            if let page = avatar.page {
                AvatarView(bridge: avatar.bridge, page: page)
            }
            #endif
            switch avatar.status {
            case .shown:
                if !avatar.drawn {
                    VStack(spacing: 8) {
                        ProgressView().tint(.white)
                        Text("Drawing her").font(.footnote).foregroundStyle(.white.opacity(0.7))
                    }
                }
            case .loading(let what):
                VStack(spacing: 8) {
                    ProgressView().tint(.white)
                    Text(what).font(.footnote).foregroundStyle(.white.opacity(0.7))
                }
            case .none:
                Text("She has no body yet.").font(.footnote).foregroundStyle(.white.opacity(0.7))
            case .failed(let why):
                Text("Her body could not be loaded: \(why)").font(.footnote).foregroundStyle(.red).multilineTextAlignment(.center).padding()
            }
        }
        .clipped()
    }
}

struct ChatColumn: View {
    let chat: ChatModel
    let model: AppModel
    @FocusState.Binding var focused: Bool
    @State private var input = ""

    var body: some View {
        VStack(spacing: 0) {
            StatusBanner(status: chat.status) { Task { await chat.start() } }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        ForEach(chat.messages) { MessageRow(message: $0).id($0.id) }
                    }
                    .padding()
                }
                // stays at the end while her words grow, and when the keyboard comes
                .defaultScrollAnchor(.bottom)
                .scrollDismissesKeyboard(.interactively)
                .onChange(of: chat.messages) {
                    if let last = chat.messages.last { proxy.scrollTo(last.id, anchor: .bottom) }
                }
            }
            HStack {
                TextField("Say something to \(chat.joiName)", text: $input, axis: .vertical)
                    .lineLimit(1...4)
                    .textFieldStyle(.roundedBorder)
                    .focused($focused)
                    .onSubmit(send)
                Button("Send", action: send)
                    .disabled(input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding()
        }
    }

    private func send() {
        let text = input
        input = ""
        Task { await chat.send(text) }
    }
}

struct StatusBanner: View {
    let status: ChatModel.Status
    let retry: () -> Void

    var body: some View {
        switch status {
        case .connected:
            EmptyView()
        case .connecting:
            banner("Connecting…", color: .gray)
        case .reconnecting(let seconds):
            banner("Connection lost, trying again in \(seconds) s", color: .orange)
        case .offline(let why):
            HStack {
                Text(why).font(.footnote)
                Spacer()
                Button("Try again", action: retry).font(.footnote.bold())
            }
            .padding(8).background(Color.red.opacity(0.2))
        }
    }

    private func banner(_ text: String, color: Color) -> some View {
        Text(text).font(.footnote).frame(maxWidth: .infinity).padding(6).background(color.opacity(0.2))
    }
}

struct MessageRow: View {
    let message: ChatMessage

    var body: some View {
        switch message.role {
        case .user:
            bubble(message.text, color: .accentColor.opacity(0.25), alignment: .trailing)
        case .joi:
            bubble(message.text.isEmpty && message.streaming ? "…" : message.text, color: .gray.opacity(0.2), alignment: .leading)
        case .note:
            Text(message.text).font(.footnote).foregroundStyle(.secondary).frame(maxWidth: .infinity)
        case .error:
            Text(message.text).font(.footnote).foregroundStyle(.red).frame(maxWidth: .infinity)
        }
    }

    private func bubble(_ text: String, color: Color, alignment: HorizontalAlignment) -> some View {
        Text(text)
            .textSelection(.enabled)
            .padding(10)
            .background(color, in: RoundedRectangle(cornerRadius: 14))
            .frame(maxWidth: .infinity, alignment: alignment == .trailing ? .trailing : .leading)
    }
}
