import UIKit

/// MSTrans colours, taken from the Mộc Sinh logo.
///
/// One colour, one meaning: green is the brand, your side of the chat and every action;
/// orange is the partner's side; gold flags something to double-check. Each has a light and a
/// dark value that keeps text at 4.5:1 or better on the system backgrounds.
enum Brand {
    static let name = "MSTrans"

    /// Text, icons, selection. #0B6542 on white 7.1:1; #34A86F on #1C1C1E 5.6:1.
    static let green = UIColor(light: 0x0B6542, dark: 0x34A86F)
    /// Behind white labels (primary buttons). White on #0B6542 7.1:1, on #1B7A4C 5.3:1.
    static let greenFill = UIColor(light: 0x0B6542, dark: 0x1B7A4C)
    /// The partner's messages. #B9461A on white 5.3:1; #F07A47 on #1C1C1E 6.1:1.
    static let orange = UIColor(light: 0xB9461A, dark: 0xF07A47)
    /// Notes worth a second look. #8F6200 on white 5.4:1; #E8B04A on #1C1C1E 8.7:1.
    static let gold = UIColor(light: 0x8F6200, dark: 0xE8B04A)
    /// Behind white labels (swipe actions). White on #8F6200 5.4:1 in both appearances.
    static let goldFill = UIColor(rgb: 0x8F6200)
    /// Soft backgrounds behind green, orange or gold content.
    static let greenWash = UIColor(light: 0xE7F2EC, dark: 0x16271E)
    static let orangeWash = UIColor(light: 0xFCEDE6, dark: 0x2E1C14)
    static let goldWash = UIColor(light: 0xFBF3E2, dark: 0x2B2414)
}

extension UIColor {
    convenience init(light: UInt32, dark: UInt32) {
        self.init { $0.userInterfaceStyle == .dark ? UIColor(rgb: dark) : UIColor(rgb: light) }
    }

    convenience init(rgb: UInt32) {
        self.init(red: CGFloat((rgb >> 16) & 0xFF) / 255,
                  green: CGFloat((rgb >> 8) & 0xFF) / 255,
                  blue: CGFloat(rgb & 0xFF) / 255,
                  alpha: 1)
    }
}
