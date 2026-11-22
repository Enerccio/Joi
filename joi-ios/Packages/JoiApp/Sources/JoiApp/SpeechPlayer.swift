import AVFoundation
import Foundation
import JoiProtocol

/// Plays the pieces of her speech one after the other as they come (the server renders the next while one plays).
@MainActor public final class SpeechPlayer: NSObject, SpeechPlaying, AVAudioPlayerDelegate {
    private var queue: [(number: Int, chunk: AudioChunk)] = []
    private var player: AVAudioPlayer?
    private var numbers = 0
    public var onFinished: (@MainActor () -> Void)?
    /// A piece came, before it plays: the number it plays under and the piece (her mouth is read from its sound).
    public var onQueued: (@MainActor (Int, AudioChunk) -> Void)?
    /// The piece of that number plays now.
    public var onStarted: (@MainActor (Int) -> Void)?
    /// Nothing plays any more: her speech is over, or was stopped.
    public var onStopped: (@MainActor () -> Void)?

    public override init() {
        super.init()
    }

    public func enqueue(_ chunk: AudioChunk) {
        numbers += 1
        queue.append((numbers, chunk))
        onQueued?(numbers, chunk)
        if player == nil { playNext() }
    }

    /// Barge-in: what she was saying is dropped.
    public func stop() {
        queue = []
        player?.stop()
        player = nil
        onStopped?()
    }

    private func playNext() {
        guard !queue.isEmpty else {
            let wasPlaying = player != nil
            player = nil
            if wasPlaying {
                onStopped?()
                onFinished?()
            }
            return
        }
        let (number, chunk) = queue.removeFirst()
        do {
            #if os(iOS)
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
            try AVAudioSession.sharedInstance().setActive(true)
            #endif
            let next = try AVAudioPlayer(data: chunk.data)
            next.delegate = self
            next.play()
            player = next
            onStarted?(number)
        } catch {
            playNext() // a piece that cannot be played is skipped
        }
    }

    /// Called on a thread of AVFoundation's.
    public nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in self.playNext() }
    }
}
