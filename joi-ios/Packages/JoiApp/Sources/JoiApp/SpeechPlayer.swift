import AVFoundation
import Foundation
import JoiProtocol

/// Plays the pieces of her speech one after the other as they come (the server renders the next while one plays).
@MainActor public final class SpeechPlayer: NSObject, SpeechPlaying, AVAudioPlayerDelegate {
    private var queue: [AudioChunk] = []
    private var player: AVAudioPlayer?

    public override init() {
        super.init()
    }

    public func enqueue(_ chunk: AudioChunk) {
        queue.append(chunk)
        if player == nil { playNext() }
    }

    /// Barge-in: what she was saying is dropped.
    public func stop() {
        queue = []
        player?.stop()
        player = nil
    }

    private func playNext() {
        guard !queue.isEmpty else {
            player = nil
            return
        }
        let chunk = queue.removeFirst()
        do {
            #if os(iOS)
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
            try AVAudioSession.sharedInstance().setActive(true)
            #endif
            let next = try AVAudioPlayer(data: chunk.data)
            next.delegate = self
            next.play()
            player = next
        } catch {
            playNext() // a piece that cannot be played is skipped
        }
    }

    /// Called on a thread of AVFoundation's.
    public nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in self.playNext() }
    }
}
