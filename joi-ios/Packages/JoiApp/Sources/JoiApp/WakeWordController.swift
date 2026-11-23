import Foundation
import Observation
#if canImport(UIKit)
import UIKit
#endif

/// The user's choices about the wake word, and when it is listened for: off until the user turns it on, and then (when they want) only while the
/// phone charges, since a microphone that is always open costs battery. It arms and disarms the `VoiceInput`.
@MainActor @Observable
public final class WakeWordController {
    public private(set) var enabled: Bool
    public private(set) var onlyWhileCharging: Bool
    /// Whether the phone charges now (always true off the phone, where there is no battery to ask).
    public private(set) var charging = true
    /// The phrase to say ("Hey Joi").
    public let phrase: String

    private let name: String
    private let voice: VoiceInput
    private let defaults: UserDefaults

    enum Keys {
        static let enabled = "joi.wakeWord.enabled"
        static let onlyWhileCharging = "joi.wakeWord.onlyWhileCharging"
    }

    public init(name: String, voice: VoiceInput, defaults: UserDefaults = .standard) {
        self.name = name
        self.voice = voice
        self.defaults = defaults
        phrase = "Hey \(name)"
        enabled = defaults.bool(forKey: Keys.enabled)
        onlyWhileCharging = defaults.bool(forKey: Keys.onlyWhileCharging)
        watchBattery()
        apply()
    }

    public func setEnabled(_ value: Bool) {
        enabled = value
        defaults.set(value, forKey: Keys.enabled)
        apply()
    }

    public func setOnlyWhileCharging(_ value: Bool) {
        onlyWhileCharging = value
        defaults.set(value, forKey: Keys.onlyWhileCharging)
        apply()
    }

    func setCharging(_ value: Bool) {
        guard value != charging else { return }
        charging = value
        apply()
    }

    /// Whether the wake word is listened for now.
    public var listening: Bool { enabled && (!onlyWhileCharging || charging) }

    private func apply() {
        if listening {
            voice.arm(phrases: WakeWord.phrases(name: name), hints: WakeWord.hints(name: name))
        } else {
            voice.disarm()
        }
    }

    private func watchBattery() {
        #if canImport(UIKit) && !os(tvOS) && !os(watchOS)
        let device = UIDevice.current
        device.isBatteryMonitoringEnabled = true
        charging = device.batteryState == .charging || device.batteryState == .full
        // lives as long as the app: the closure holds the controller weakly, so a chat that is gone leaves it doing nothing
        _ = NotificationCenter.default.addObserver(forName: UIDevice.batteryStateDidChangeNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                let state = UIDevice.current.batteryState
                self?.setCharging(state == .charging || state == .full)
            }
        }
        #endif
    }
}

/// A request to listen from outside the chat (the App Intent *Talk to Joi*, run by Siri, a Vocal Shortcut, the Action Button): the chat takes it
/// when it is there. An app that was not running has no chat yet; the request waits for it a few seconds.
@MainActor public final class VoiceTrigger {
    public static let shared = VoiceTrigger()

    private var handler: (@MainActor () -> Void)?
    private var requested: Date?
    /// How long a request waits for the chat (the app is starting, the user is signed in already).
    static let patience: TimeInterval = 15

    public init() {}

    /// The intent ran.
    public func listen(now: Date = Date()) {
        if let handler {
            handler()
        } else {
            requested = now
        }
    }

    /// The chat is there: it takes the request that waits, and the next ones as they come. `nil` when it goes away.
    public func attach(_ handler: (@MainActor () -> Void)?, now: Date = Date()) {
        self.handler = handler
        guard let handler, let asked = requested else { return }
        requested = nil
        if now.timeIntervalSince(asked) <= Self.patience { handler() }
    }
}
