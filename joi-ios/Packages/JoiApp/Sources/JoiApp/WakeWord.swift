import Foundation

/// The wake word is text: "Hey <name>" (the assistant's name), found in what the recognizer hears, so a new name for her needs no training.
public enum WakeWord {
    /// What is said before her name: "hey Joi", "hi Joi", "ok Joi", "okay Joi".
    static let greetings = ["hey", "hi", "ok", "okay"]

    /// The phrases that wake her, for her name (kept as words: a name of two words is two).
    public static func phrases(name: String) -> [String] {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return [] }
        return greetings.map { "\($0) \(name)" }
    }

    /// What the recognizer is told to expect, so that her name is not heard as another word.
    public static func hints(name: String) -> [String] {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? [] : ["Hey \(name)", name]
    }

    /// What follows the first wake phrase in `text` ("Hey Joi, what time is it" gives "what time is it"; "hey joi" gives ""), nil when
    /// there is none. Case, accents and punctuation do not matter; the phrase has to be whole words.
    public static func remainder(in text: String, phrases: [String]) -> String? {
        let heard = tokens(text)
        var best: (start: Int, end: String.Index)?
        for phrase in phrases {
            let wanted = tokens(phrase).map(\.word)
            guard !wanted.isEmpty, heard.count >= wanted.count else { continue }
            for start in 0...(heard.count - wanted.count) where best == nil || start < best!.start {
                if (0..<wanted.count).allSatisfy({ heard[start + $0].word == wanted[$0] }) {
                    best = (start, heard[start + wanted.count - 1].range.upperBound)
                    break
                }
            }
        }
        guard let best else { return nil }
        let rest = text[best.end...]
        return rest.drop(while: { $0.isWhitespace || ",.:;!?-–—…".contains($0) }).description
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// The words of a text, folded (lowercase, no accents), with where each ends in the text.
    private static func tokens(_ text: String) -> [(word: String, range: Range<String.Index>)] {
        var out: [(String, Range<String.Index>)] = []
        var start: String.Index?
        var index = text.startIndex
        func close(at end: String.Index) {
            if let begin = start {
                let word = text[begin..<end].folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
                out.append((word, begin..<end))
                start = nil
            }
        }
        while index < text.endIndex {
            let character = text[index]
            if character.isLetter || character.isNumber || character == "'" || character == "’" {
                if start == nil { start = index }
            } else {
                close(at: index)
            }
            index = text.index(after: index)
        }
        close(at: text.endIndex)
        return out
    }
}
