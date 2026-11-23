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
    @State private var showingHandsFree = false
    @Environment(\.scenePhase) private var scenePhase

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
        .sheet(isPresented: $showingHandsFree) { HandsFreeView(chat: chat) }
        // a microphone that could not be opened in the background is tried again in front
        .onChange(of: scenePhase) {
            if scenePhase == .active { chat.voice?.retryNow() }
        }
    }

    /// Over her body: what she wears (when she has parts to switch) and the menu.
    private var controls: some View {
        HStack(spacing: 14) {
            if !avatar.parts.isEmpty || !avatar.outfits.isEmpty {
                Button { showingParts = true } label: { Image(systemName: "tshirt") }
                    .accessibilityLabel("Her look")
            }
            Menu {
                if chat.voice != nil {
                    Button { showingHandsFree = true } label: { Label("Hands-free", systemImage: "ear") }
                }
                Button("Sign out", role: .destructive) { model.signOut() }
            } label: { Image(systemName: "ellipsis.circle") }
        }
        .font(.title3)
        .foregroundStyle(.white.opacity(0.8))
        .padding(12)
    }
}

/// Talking to her without touching the phone: the wake word (the app's own, off until the user turns it on) and how to start her with Siri, a Vocal
/// Shortcut or the Action Button (set up by the user, in the system's own settings: an app cannot do it for them).
struct HandsFreeView: View {
    let chat: ChatModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if let wake = chat.wake {
                    Section {
                        Toggle("Listen for “\(wake.phrase)”", isOn: Binding(get: { wake.enabled }, set: { wake.setEnabled($0) }))
                        Toggle("Only while charging", isOn: Binding(get: { wake.onlyWhileCharging }, set: { wake.setOnlyWhileCharging($0) }))
                            .disabled(!wake.enabled)
                    } header: {
                        Text("Wake word")
                    } footer: {
                        Text("The microphone stays open (the orange dot is on) and the recognizer works on the phone, so the battery pays for it. What you say after \(wake.phrase) goes to \(chat.joiName) as words; nothing is recorded. It listens while the app is open or in the background, and stops while she talks.")
                    }
                }
                Section("With Siri") {
                    Text("Say “Hey Siri, \(chat.joiName)”. Siri opens the app and it listens.")
                }
                Section("With your own phrase (iOS 18)") {
                    Text("Settings → Accessibility → Vocal Shortcuts → Set Up Vocal Shortcut. Choose the shortcut *Talk to Joi* and say your phrase three times (“\(chat.wake?.phrase ?? "Hey \(chat.joiName)")” will do).")
                }
                Section("With a button") {
                    Text("Settings → Action Button → Shortcut → *Talk to Joi*. Back Tap (Settings → Accessibility → Touch → Back Tap) and the Control Center can run it too.")
                }
            }
            .navigationTitle("Hands-free")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
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
                if !avatar.outfits.isEmpty {
                    Section("Outfit") {
                        Picker("Outfit", selection: Binding(get: { avatar.outfits.first(where: \.worn)?.id ?? "" }, set: { wear($0) })) {
                            if !avatar.outfits.contains(where: \.worn) { Text("Own mix").tag("") }
                            ForEach(avatar.outfits, id: \.id) { Text($0.name).tag($0.id) }
                        }
                        .disabled(chat.wearingOutfit != nil)
                        if let worn = avatar.outfits.first(where: \.worn), let description = worn.description, !description.isEmpty {
                            Text(description).font(.footnote).foregroundStyle(.secondary)
                        }
                        if let why = chat.outfitFailure {
                            Text("Not changed: " + why).font(.footnote).foregroundStyle(.secondary)
                        }
                    }
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
            .overlay { if chat.switchingPart != nil || chat.wearingOutfit != nil { ProgressView() } }
        }
        .presentationDetents([.medium, .large])
    }

    private func wear(_ outfit: String) {
        guard !outfit.isEmpty, avatar.outfits.first(where: \.worn)?.id != outfit else { return }
        Task { await chat.wearOutfit(outfit) }
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

/// A task of hers that waits for the user: what she asked a tool for, and the answer. The oldest is shown, with how many more wait.
struct TaskQuestionCard: View {
    let chat: ChatModel
    let question: TaskInfo

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label(question.title, systemImage: "questionmark.bubble").font(.headline)
                Spacer()
                if chat.questions.count > 1 {
                    Text("+\(chat.questions.count - 1)").font(.footnote).foregroundStyle(.secondary)
                }
            }
            if let description = question.toolDescription ?? question.tool {
                Text(description).font(.footnote).foregroundStyle(.secondary)
            }
            if let asked = question.question, !asked.isEmpty {
                Text(asked).font(.callout)
            }
            if let url = question.url, !url.isEmpty {
                Text(url).font(.footnote).foregroundStyle(.secondary).lineLimit(2).truncationMode(.middle).textSelection(.enabled)
            }
            if let note = chat.questionNote {
                Text(note).font(.footnote).foregroundStyle(.red)
            }
            HStack {
                Button("Approve") { Task { await chat.answer(question, .approve) } }.buttonStyle(.borderedProminent)
                Button("Deny", role: .destructive) { Task { await chat.answer(question, .deny) } }.buttonStyle(.bordered)
                Spacer()
                if let tool = question.tool {
                    Menu {
                        Button("Always approve \(tool)") { Task { await chat.answer(question, .approveAlways) } }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
                if chat.answering == question.id { ProgressView() }
            }
            .disabled(chat.answering != nil)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.thinMaterial)
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
    /// Files of the images waiting to be sent with the words.
    @State private var images: [URL] = []

    var body: some View {
        VStack(spacing: 0) {
            StatusBanner(status: chat.status) { Task { await chat.start() } }
            if let question = chat.questions.first {
                TaskQuestionCard(chat: chat, question: question)
            }
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
            if !images.isEmpty {
                PendingImages(images: $images).padding(.horizontal)
            }
            if let voice = chat.voice {
                VoiceStrip(voice: voice)
            }
            HStack {
                if chat.canSeeImages {
                    ImageAttachButton(images: $images, limit: ChatModel.maxImages).font(.title3)
                }
                if let voice = chat.voice {
                    VoiceButton(voice: voice, connected: chat.status == .connected && !chat.sending).font(.title3)
                }
                TextField("Say something to \(chat.joiName)", text: $input, axis: .vertical)
                    .lineLimit(1...4)
                    .textFieldStyle(.roundedBorder)
                    .focused($focused)
                    .onSubmit(send)
                Button("Send", action: send)
                    .disabled(!canSend)
            }
            .padding()
        }
        // words heard but not sent (the connection was down) wait in the field
        .onChange(of: chat.voice?.leftover) {
            if let words = chat.voice?.takeLeftover(), input.isEmpty { input = words }
        }
    }

    private var canSend: Bool {
        !chat.sending && (!input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !images.isEmpty)
    }

    /// The words and the images leave the field at once and come back when the message was not sent.
    private func send() {
        guard canSend else { return }
        let text = input
        let files = images
        input = ""
        images = []
        Task {
            if await chat.send(text, images: files) {
                for file in files { try? FileManager.default.removeItem(at: file) }
            } else {
                if input.isEmpty { input = text }
                images = files
            }
        }
    }
}

/// The microphone: tap to talk, tap again to send what was said at once.
struct VoiceButton: View {
    let voice: VoiceInput
    let connected: Bool

    var body: some View {
        Button { voice.toggle() } label: {
            Image(systemName: voice.state == .listening ? "mic.fill" : "mic")
                .foregroundStyle(voice.state == .listening ? Color.red : Color.accentColor)
        }
        .disabled(voice.state == .idle || voice.state == .waiting ? !connected : voice.state != .listening)
        .accessibilityLabel(voice.state == .listening ? "Send what you said" : "Talk")
    }
}

/// What the microphone does: what is heard as the user talks, why nothing was said (when so), and a way to cancel.
struct VoiceStrip: View {
    let voice: VoiceInput

    var body: some View {
        if voice.state == .waiting {
            Label("Listening for the wake word", systemImage: "ear")
                .font(.footnote).foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal).padding(.vertical, 4)
        } else if voice.state != .idle {
            HStack {
                switch voice.state {
                case .starting:
                    ProgressView()
                    Text("Starting the microphone…").foregroundStyle(.secondary)
                case .listening:
                    Image(systemName: "waveform").foregroundStyle(.red)
                    Text(voice.heard.isEmpty ? "Listening…" : voice.heard).lineLimit(3)
                case .finishing:
                    ProgressView()
                    Text(voice.heard.isEmpty ? "Finishing…" : voice.heard).foregroundStyle(.secondary).lineLimit(3)
                case .idle, .waiting:
                    EmptyView()
                }
                Spacer()
                if voice.state != .finishing {
                    Button { voice.cancel() } label: { Image(systemName: "xmark.circle.fill") }
                        .foregroundStyle(.secondary)
                        .accessibilityLabel("Cancel")
                }
            }
            .font(.callout)
            .padding(.horizontal).padding(.vertical, 6)
            .background(.thinMaterial)
        } else if let note = voice.note {
            Text(note).font(.footnote).foregroundStyle(.red).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal).padding(.vertical, 4)
        }
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
            bubble(userText, color: .accentColor.opacity(0.25), alignment: .trailing)
        case .joi:
            bubble(message.text.isEmpty && message.streaming ? "…" : message.text, color: .gray.opacity(0.2), alignment: .leading)
        case .note:
            Text(message.text).font(.footnote).foregroundStyle(.secondary).frame(maxWidth: .infinity)
        case .error:
            Text(message.text).font(.footnote).foregroundStyle(.red).frame(maxWidth: .infinity)
        }
    }

    /// The words, with a line for the images that went with them.
    private var userText: String {
        guard message.imageCount > 0 else { return message.text }
        let line = "📷 " + (message.imageCount == 1 ? "1 image" : "\(message.imageCount) images")
        return message.text.isEmpty ? line : message.text + "\n" + line
    }

    private func bubble(_ text: String, color: Color, alignment: HorizontalAlignment) -> some View {
        Text(text)
            .textSelection(.enabled)
            .padding(10)
            .background(color, in: RoundedRectangle(cornerRadius: 14))
            .frame(maxWidth: .infinity, alignment: alignment == .trailing ? .trailing : .leading)
    }
}
