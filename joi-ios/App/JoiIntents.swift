import AppIntents
import JoiApp

/// *Talk to Joi*: opens the app and starts the microphone, as the button does. Siri runs it ("Hey Siri, Joi"), a Vocal Shortcut the user trained
/// (iOS 18), the Action Button, Back Tap, Control Center and the Shortcuts app run it as the shortcut it is. The app's own code is `VoiceTrigger`:
/// the intent is here, in the app's target, because the system reads the intents of the app itself, not of a package.
struct TalkIntent: AppIntent {
    static let title: LocalizedStringResource = "Talk to Joi"
    static let description = IntentDescription("Opens Joi and listens to what you say.")
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        VoiceTrigger.shared.listen()
        return .result()
    }
}

struct JoiShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: TalkIntent(),
            phrases: [
                "\(.applicationName)",
                "Talk to \(.applicationName)",
                "Listen, \(.applicationName)",
            ],
            shortTitle: "Talk",
            systemImageName: "mic"
        )
    }
}
