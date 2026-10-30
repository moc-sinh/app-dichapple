import Foundation

/// Works out, one step at a time, how to empty a text field that the keyboard sees only near the
/// cursor (usually the current paragraph) and that reports its changes a moment late.
///
/// Up to 1.16.2 the field counted as empty after three looks 25 ms apart that saw no text around
/// the cursor. A long message of several paragraphs in Zalo or WeChat could show nothing for that
/// long while the app was still catching up with a deletion, or at a blank line: the translation
/// then went in after the paragraphs not yet deleted, and the Vietnamese stayed with it.
/// Now the text the keyboard read from the field is all deleted however little the field shows,
/// and the field counts as empty only once it says so.
struct FieldEraser {
    enum Step: Equatable {
        /// Delete this many characters before the cursor, then wait up to `wait` × 25 ms for the
        /// field to report the change.
        case delete(Int, wait: Int)
        /// There is text after the cursor: go to the end of the field first.
        case toEnd
        /// Nothing around the cursor though the field may still hold text: look again in a moment.
        case wait
        /// The field is empty.
        case done
    }

    /// Characters of the text read from the field not yet deleted.
    private(set) var remaining: Int
    private var quiet = 0
    private var toEnds = 0
    private(set) var steps = 0

    /// `expected`: the field's text as last read (or as the keyboard put it there).
    init(expected: String) {
        remaining = expected.count
    }

    mutating func next(before: String, after: String, hasText: Bool) -> Step {
        steps += 1
        guard steps <= 300 else { return .done }
        if !after.isEmpty, toEnds < 3 {
            toEnds += 1
            quiet = 0
            return .toEnd
        }
        if !before.isEmpty {
            quiet = 0
            // All that was read goes at once: what the field shows may be only its last paragraph.
            // Deleting past the start of the field does nothing.
            let count = max(before.count, remaining)
            remaining = 0
            return .delete(count, wait: 40)
        }
        if remaining > 0 {
            if hasText {
                let count = remaining
                remaining = 0
                return .delete(count, wait: 8)
            }
            quiet += 1
            return quiet < 4 ? .wait : .done
        }
        // Everything read is gone. A field showing nothing but not empty is at the start of a
        // paragraph (a line break the context leaves out), or more text than was read.
        guard hasText else { return .done }
        quiet += 1
        return quiet <= 4 ? .delete(1, wait: 8) : .done
    }

    /// Whether the field had better be read again after the new text goes in: a long text, or one
    /// of several paragraphs, is where a field showing only part of itself could leave some behind.
    static func worthChecking(_ old: String, _ new: String) -> Bool {
        old.count > 150 || new.count > 150 || old.contains("\n") || new.contains("\n")
    }

    /// The field as read after the new text went in, when it still holds more than that text:
    /// the text left over, to be erased again. Nil when the field is right (or is something this
    /// cannot tell apart, such as text the app changed).
    static func leftover(found: String, inserted: String) -> String? {
        let found = found.trimmingCharacters(in: .whitespacesAndNewlines)
        let inserted = inserted.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !inserted.isEmpty, found != inserted, found.count > inserted.count, found.contains(inserted) else { return nil }
        return found
    }
}
