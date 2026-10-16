#if canImport(CoreGraphics)
import CoreGraphics
#else
import Foundation
#endif

/// Where the keys go, in points, so that each sits where the Apple keyboard's does: a thumb used
/// to the iPhone keyboard lands on the same key here. Typing logs showed "a" taken for "q" when the
/// rows sat 7–10 pt lower than Apple's.
///
/// Measured on Apple's keyboard: the user's iPhone Pro Max with iOS 26 (screenshots: keys 45 pt
/// high and 36.3 wide, rows 56 pt apart, 6.7 pt side margin, bottom keys 80 pt above the screen
/// bottom), and VietTelex's measurements of the other iPhones (`KeyGeometry.StockSize`, MIT, see
/// TelexCore/LICENSE-VietTelex.txt). A keyboard's own view ends 75 pt above the screen bottom in
/// portrait (iOS draws the globe and dictation strip below it), 20 pt in landscape, and at the
/// bottom on iPhones with a Home button.
///
/// Rows are placed from the bottom of the view up. `extra` (the size setting) makes every key
/// taller and leaves the gaps alone.
struct KeyMetrics: Equatable {
    var keyHeight: CGFloat
    /// Between letter rows, and above the first row.
    var rowGap: CGFloat
    /// Between the z row and the bottom row.
    var bottomGap: CGFloat
    var bottomKeyHeight: CGFloat
    /// Under the bottom row.
    var bottomPad: CGFloat
    var sideMargin: CGFloat
    var keyGap: CGFloat

    /// The key area's height.
    var area: CGFloat { 3 * (rowGap + keyHeight) + bottomGap + bottomKeyHeight + bottomPad }

    /// Rows from the top (q, a, z, bottom), as (top, height) in a view `height` tall.
    func rows(in height: CGFloat) -> [(top: CGFloat, height: CGFloat)] {
        let bottom = height - bottomPad - bottomKeyHeight
        let z = bottom - bottomGap - keyHeight
        let a = z - rowGap - keyHeight
        let q = a - rowGap - keyHeight
        return [(q, keyHeight), (a, keyHeight), (z, keyHeight), (bottom, bottomKeyHeight)]
    }

    /// Width of a letter key: ten across the row.
    func letterWidth(in width: CGFloat) -> CGFloat {
        (width - 2 * sideMargin - 9 * keyGap) / 10
    }

    /// Wider than this is landscape (no iPhone is this wide upright).
    static let landscapeWidth: CGFloat = 500
    /// From this width upright: the large iPhones (Air, Plus, Pro Max).
    static let largeWidth: CGFloat = 420

    static func phone(width: CGFloat, homeButton: Bool, extra: CGFloat = 0) -> KeyMetrics {
        var m: KeyMetrics
        if width > landscapeWidth {
            m = homeButton
                ? KeyMetrics(keyHeight: 32, rowGap: 8, bottomGap: 8, bottomKeyHeight: 32, bottomPad: 4, sideMargin: 6.5, keyGap: 6)
                : KeyMetrics(keyHeight: 32, rowGap: 8, bottomGap: 8, bottomKeyHeight: 32, bottomPad: 2, sideMargin: 4.33, keyGap: 6.67)
            // The screen is short on its side: half as much taller.
            m.keyHeight += extra / 2
            m.bottomKeyHeight += extra / 2
            return m
        }
        if homeButton {
            // iPhone SE: no strip under the keyboard; Apple's bottom keys end 4 pt above the screen bottom.
            m = KeyMetrics(keyHeight: 42, rowGap: 12, bottomGap: 12, bottomKeyHeight: 42, bottomPad: 4, sideMargin: 6.5, keyGap: 6)
        } else if width >= largeWidth {
            // Apple's bottom keys end 80 pt above the screen bottom: 5 pt above our view's bottom.
            m = KeyMetrics(keyHeight: 45, rowGap: 11, bottomGap: 11, bottomKeyHeight: 45, bottomPad: 5, sideMargin: 6.67, keyGap: 6)
        } else {
            // Apple's bottom keys end 71 pt above the screen bottom, 4 pt below our view's bottom:
            // the bottom row moves up into its gap (7 pt instead of 11) so the letter rows match.
            m = KeyMetrics(keyHeight: 43, rowGap: 11, bottomGap: 7, bottomKeyHeight: 43, bottomPad: 0, sideMargin: 6.67, keyGap: 6)
        }
        m.keyHeight += extra
        m.bottomKeyHeight += extra
        return m
    }
}
