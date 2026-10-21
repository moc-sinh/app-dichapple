import AudioToolbox
import UIKit

/// What a key press asks the text field to do.
enum KeyAction {
    /// A letter on the letters page; goes through Telex.
    case letter(Character)
    /// Text inserted as is (digits, punctuation, accented letters from the long-press picker).
    case text(String)
    case delete(word: Bool)
    case space
    case newline
    case moveCursor(Int)
    /// Takes back the key typed last: holding it chose an accent instead, or turned the space bar
    /// into a trackpad.
    case undoLast
}

protocol KeyboardLayoutViewDelegate: AnyObject {
    func keyboardLayoutView(_ view: KeyboardLayoutView, perform action: KeyAction)
    func keyboardLayoutView(_ view: KeyboardLayoutView, globeTouchedWith event: UIEvent)
}

/// The key area, laid out and behaving like the iOS system keyboard:
/// a pop-up on every character key, no dead zones between keys, fast typing with
/// two thumbs, long-press accents, space-bar cursor control, double-tap Shift for
/// Caps Lock and a delete key that speeds up to whole words.
/// All keys live in one view, so a touch never waits for a button to be found.
final class KeyboardLayoutView: UIView {
    enum Shift { case off, once, locked }

    private enum Page { case letters, numbers, symbols }
    private enum Kind: Equatable { case char(String), shift, delete, page(Page), space, ret, globe }

    private final class Key {
        let kind: Kind
        let cap = UIView()
        let label = UILabel()
        /// Letters page: the capital, drawn once; Shift shows it instead of drawing the letter again.
        var capital: UILabel?
        let icon = UIImageView()
        var hit = CGRect.zero
        var shadowSize = CGSize.zero
        /// Fingers on the key while it is lit, and when it lit up (see `unlight`).
        var litBy = 0
        var litAt: CFTimeInterval = 0

        init(kind: Kind) { self.kind = kind }

        var character: String? {
            if case .char(let s) = kind { return s }
            return nil
        }
    }

    private final class Track {
        var key: Key
        var committed = false
        /// Typed the moment the finger landed (see `typesOnTouchDown`); a long press takes it back.
        var typedOnDown = false
        /// Typed as a capital: its accents are offered as capitals too.
        var upper = false
        var popup: KeyPopup?
        /// The key lit instead of a pop-up (see `showsPopups`).
        var lit: Key?
        /// The letter a typed key's finger last slid on to, for the typing log.
        var slidTo: String?
        var anchorX: CGFloat = 0
        let start: CGPoint
        /// Where the finger was when its key's long press was set: moving off it is not holding.
        var holdFrom: CGPoint
        let beganAt = CACurrentMediaTime()

        init(key: Key, start: CGPoint = .zero) {
            self.key = key
            self.start = start
            holdFrom = start
        }
    }

    /// When a key last went down: while keys are being pressed, taps reach a little higher.
    private var lastTouchAt: CFTimeInterval = 0

    weak var delegate: KeyboardLayoutViewDelegate?
    var hapticsEnabled = true
    /// Key sounds, as the user set them in MSTrans.
    var clicksEnabled = true
    /// With Full Access the keyboard plays the key sounds itself, off the main thread:
    /// `UIDevice.playInputClick` waited on iOS's sound service on the main thread (up to 89 ms in
    /// CI) and every key behind it waited too. Without Full Access it is the only way.
    var playsOwnClicks = false
    /// Letters, digits, symbols and the space bar type as the finger lands, not as it lifts: the
    /// text is in the app a whole press sooner (60–100 ms when typing fast). Holding a vowel still
    /// offers its accents, and holding the space bar still moves the cursor: the key typed is
    /// taken back. Return still waits for the lift, so a message is never sent by a graze.
    var typesOnTouchDown = true
    /// The enlarged letter above a pressed key. Off (the default), the key lights up instead, as
    /// the Apple keyboard does with Character Preview off: the balloon covered the keys above and
    /// beside the one pressed, so the next key was hidden while typing fast.
    var showsPopups = false
    var showsGlobe = false {
        didSet { if showsGlobe != oldValue { rebuildAll() } }
    }
    /// Extra height of every key over Apple's (Cài đặt › Bàn phím › Cỡ bàn phím).
    var extraKeyHeight: CGFloat = 0 {
        didSet {
            guard extraKeyHeight != oldValue else { return }
            laidOut.removeAll()
            needsKeyFrames = true
            setNeedsLayout()
        }
    }
    private(set) var shift: Shift = .off

    private var page: Page = .letters
    private var rows: [[Key]] = []
    private var keys: [Key] = []
    /// Each page's keys, built once and kept: 123 and ABC only show another set of keys, they
    /// never build 33 keys again (that held up the key after every switch, and punctuation is
    /// on the 123 page, so it happened often).
    private var built: [Page: [[Key]]] = [:]
    /// The rows of a page built so far while it is prepared a row at a time.
    private var partial: [Page: [[Key]]] = [:]
    /// The size each page was last laid out for.
    private var laidOut: [Page: CGSize] = [:]
    private var tracks: [UITouch: Track] = [:]
    private var lastShiftTap: CFTimeInterval = 0
    /// Where the last Shift tap started from: a double tap from the capital a sentence starts with
    /// locks capitals too.
    private var lastShiftFrom = Shift.off
    /// Caps Lock, kept while 123 shows.
    private var capsLock = false

    private var returnTitle = "xuống dòng"
    private var returnProminent = false
    private var returnEnabled = true

    private weak var longPressTouch: UITouch?
    private var longPressTimer: Timer?
    private var deleteTimer: Timer?
    private var deleteCount = 0
    private weak var trackpadTouch: UITouch?
    private weak var accentTouch: UITouch?
    private var accentPicker: AccentPicker?
    private let haptics = UIImpactFeedbackGenerator(style: .light)
    /// A key went down: its sound and haptic play once the pop-up has gone to the screen.
    private var pendingSound: Sound?
    private var pendingHaptic = true
    private var feedbackObserver: CFRunLoopObserver?

    /// The system keyboard's own sounds: letters, delete, and the other keys.
    private enum Sound: SystemSoundID {
        case key = 1104, delete = 1155, modifier = 1156
    }
    private static let sounds = DispatchQueue(label: "MSTrans.key-sounds", qos: .userInteractive)

    /// Pop-ups and the accent picker sit in their own layer above the keys: showing one never
    /// makes the keys lay out again, and the pop-ups are reused instead of built per press.
    private let overlay = UIView()
    private var sparePopups: [KeyPopup] = []
    /// Key frames are computed only when the size or the keys change, not on every pass.
    private var needsKeyFrames = true

    /// Clear to the eye but not to iOS (see `init`).
    static let touchable = UIColor(white: 0, alpha: 0.01)

    static let cornerRadius: CGFloat = {
        if #available(iOS 26.0, *) { return 8.5 }
        return 5
    }()
    private static let iconConfig = UIImage.SymbolConfiguration(pointSize: 18, weight: .regular)
    /// Made once: Shift changes its icon at the start of every sentence.
    private static let shiftOff = UIImage(systemName: "shift", withConfiguration: iconConfig)
    private static let shiftOn = UIImage(systemName: "shift.fill", withConfiguration: iconConfig)
    private static let capsLock = UIImage(systemName: "capslock.fill", withConfiguration: iconConfig)

    private static let accents: [String: [String]] = [
        "a": ["a", "á", "à", "ả", "ã", "ạ", "ă", "â"],
        "e": ["e", "é", "è", "ẻ", "ẽ", "ẹ", "ê"],
        "i": ["i", "í", "ì", "ỉ", "ĩ", "ị"],
        "o": ["o", "ó", "ò", "ỏ", "õ", "ọ", "ô", "ơ"],
        "u": ["u", "ú", "ù", "ủ", "ũ", "ụ", "ư"],
        "y": ["y", "ý", "ỳ", "ỷ", "ỹ", "ỵ"],
        "d": ["d", "đ"],
        "-": ["-", "–", "—", "•"],
        "₫": ["₫", "$", "€", "£", "¥", "₩"],
        "?": ["?", "¿"],
        "!": ["!", "¡"],
        "'": ["'", "‘", "’"],
        "\"": ["\"", "“", "”", "„"],
        ".": [".", "…"],
        "0": ["0", "°"],
        "/": ["/", "\\"],
    ]

    override init(frame: CGRect) {
        super.init(frame: frame)
        TypingLog.mark("tạo phím")
        // Not clear: iOS decides which process gets a touch by what is drawn under the finger,
        // and a touch on a clear spot (the gaps between keys, the strip left of "a") went to the
        // app behind the keyboard. Typing logs showed an "a" lost that way three times, with no
        // touch reaching the keyboard (VietTelex found the same on its keyboard).
        backgroundColor = Self.touchable
        isMultipleTouchEnabled = true
        clipsToBounds = false
        overlay.isUserInteractionEnabled = false
        overlay.clipsToBounds = false
        addSubview(overlay)
        // Two thumbs: two pop-ups can show at once. Made now, not on the first key press.
        for _ in 0..<2 {
            let popup = KeyPopup()
            popup.isHidden = true
            overlay.addSubview(popup)
            sparePopups.append(popup)
        }
        // Each pass, after Core Animation's commit (order 2,000,000) has handed the pressed key's
        // pop-up or lit cap to the screen: the key never waits for the click or the haptic engine.
        let observer = CFRunLoopObserverCreateWithHandler(nil, CFRunLoopActivity.beforeWaiting.rawValue, true, 2_100_000) { [weak self] _, _ in
            self?.playFeedback()
        }
        CFRunLoopAddObserver(CFRunLoopGetMain(), observer, .commonModes)
        feedbackObserver = observer
        showPage()
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    deinit {
        if let feedbackObserver { CFRunLoopObserverInvalidate(feedbackObserver) }
    }

    // MARK: State from the controller

    /// Whether the next letter starts a sentence, as last set: kept while 123 shows, so ABC turns
    /// Shift back on at the start of a sentence, as the system keyboard does.
    private var wantsAutoShift = false

    /// Sentence-start capitalisation; Caps Lock wins.
    func setAutoShift(_ on: Bool) {
        wantsAutoShift = on
        guard shift != .locked, page == .letters else { return }
        let next: Shift = on ? .once : .off
        if next != shift {
            shift = next
            applyShift()
        }
    }

    func configureReturn(title: String, prominent: Bool, enabled: Bool) {
        guard title != returnTitle || prominent != returnProminent || enabled != returnEnabled else { return }
        returnTitle = title
        returnProminent = prominent
        returnEnabled = enabled
        applyReturn()
    }

    /// Back to letters, as the system keyboard does after a space on the number page.
    func showLetters() {
        guard page != .letters else { return }
        page = .letters
        shift = lettersShift
        showPage()
    }

    /// The number page, as the keyboard opens on a field for digits (a code, a phone number).
    func showNumbers() {
        guard page != .numbers else { return }
        if page == .letters { capsLock = shift == .locked }
        page = .numbers
        shift = .off
        showPage()
    }

    /// Shift as the letters come back: Caps Lock if it was on, else a capital at a sentence start.
    private var lettersShift: Shift { capsLock ? .locked : wantsAutoShift ? .once : .off }

    // MARK: Building

    private func rowKinds(_ page: Page) -> [[Kind]] {
        func chars(_ s: String) -> [Kind] { s.map { .char(String($0)) } }
        let other: Page = page == .letters ? .numbers : .letters
        let bottom: [Kind] = showsGlobe ? [.page(other), .globe, .space, .ret] : [.page(other), .space, .ret]
        switch page {
        case .letters:
            return [chars("qwertyuiop"), chars("asdfghjkl"), [.shift] + chars("zxcvbnm") + [.delete], bottom]
        case .numbers:
            return [chars("1234567890"), chars("-/:;()₫&@\""), [.page(.symbols)] + chars(".,?!'") + [.delete], bottom]
        case .symbols:
            return [chars("[]{}#%^*+="), chars("_\\|~<>€£¥•"), [.page(.numbers)] + chars(".,?!'") + [.delete], bottom]
        }
    }

    /// Shows the current page's keys, building them the first time.
    private func showPage() {
        TypingLog.mark("đổi trang")
        for key in keys { key.cap.isHidden = true }
        rows = built[page] ?? build(page)
        keys = Array(rows.joined())
        for key in keys { key.cap.isHidden = false }
        applyShift()
        applyReturn()
        if laidOut[page] != bounds.size {
            needsKeyFrames = true
            setNeedsLayout()
        }
    }

    /// Builds the page's rows up to `limit`, after those already built; the page is complete
    /// once all are.
    @discardableResult
    private func build(_ page: Page, rows limit: Int = .max) -> [[Key]] {
        let kinds = rowKinds(page)
        var rows = partial[page] ?? []
        while rows.count < min(limit, kinds.count) {
            let row = kinds[rows.count].map { makeKey($0, on: page) }
            for key in row {
                key.cap.isHidden = true
                insertSubview(key.cap, belowSubview: overlay)
            }
            rows.append(row)
        }
        if rows.count == kinds.count {
            partial[page] = nil
            built[page] = rows
        } else {
            partial[page] = rows
        }
        return rows
    }

    /// The bottom row changed (globe key): every page is built again.
    private func rebuildAll() {
        for key in [built, partial].flatMap({ $0.values.joined() }).joined() { key.cap.removeFromSuperview() }
        built.removeAll()
        partial.removeAll()
        laidOut.removeAll()
        keys = []
        showPage()
    }

    /// Once the keyboard is up, in the gaps between keys: builds and lays out the 123 page and
    /// draws the capitals, so the first switch to 123 and the first capital cost nothing then.
    /// A row at a time, so a key pressed meanwhile waits for one row at most. It used to wait for
    /// half a second without keys: typing from the moment the keyboard opened, it never came, and
    /// the first 123 built the page there and then (25 ms on an iPhone).
    private func prepareHidden(step: Int = 0) {
        guard window != nil, bounds.width > 0 else { return }
        let idle = CACurrentMediaTime() - lastTouchAt
        guard idle > Self.prepareGap, tracks.isEmpty else {
            let wait = tracks.isEmpty ? Self.prepareGap - idle + 0.01 : 0.1
            DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in self?.prepareHidden(step: step) }
            return
        }
        TypingLog.mark("chuẩn bị trang ẩn")
        let started = CACurrentMediaTime()
        switch step {
        case 0..<4:
            if built[.numbers] == nil { build(.numbers, rows: step + 1) }
        case 4:
            for p in [Page.letters, .numbers] where laidOut[p] != bounds.size { layout(p) }
        case 5..<8:
            for key in row(of: .letters, step - 5) { key.capital?.layer.displayIfNeeded() }
        case 8..<12:
            for key in row(of: .numbers, step - 8) { key.label.layer.displayIfNeeded() }
        default:
            return
        }
        let took = CACurrentMediaTime() - started
        if took > 0.004 { TypingLog.add("⏱ chuẩn bị trang ẩn bước \(step) mất \(Int(took * 1000))ms") }
        DispatchQueue.main.async { [weak self] in self?.prepareHidden(step: step + 1) }
    }

    /// How long after a key the hidden pages may take a step: a typing gap, not a pause.
    private static let prepareGap: CFTimeInterval = 0.15

    private func row(of page: Page, _ r: Int) -> [Key] {
        guard let rows = built[page], rows.indices.contains(r) else { return [] }
        return rows[r]
    }

    private func makeKey(_ kind: Kind, on page: Page) -> Key {
        let key = Key(kind: kind)
        let cap = key.cap
        cap.isUserInteractionEnabled = false
        cap.layer.cornerRadius = Self.cornerRadius
        cap.layer.cornerCurve = .continuous
        cap.layer.shadowColor = UIColor.black.cgColor
        cap.layer.shadowOpacity = 0.3
        cap.layer.shadowRadius = 0
        cap.layer.shadowOffset = CGSize(width: 0, height: 1)
        cap.isAccessibilityElement = true
        cap.accessibilityTraits = .keyboardKey

        key.label.textAlignment = .center
        key.label.textColor = .label
        key.label.adjustsFontSizeToFitWidth = true
        key.label.minimumScaleFactor = 0.6
        key.icon.contentMode = .center
        key.icon.tintColor = .label
        cap.addSubview(key.label)
        cap.addSubview(key.icon)

        switch kind {
        case .char(let s):
            key.label.text = s
            key.label.font = .systemFont(ofSize: page == .letters ? 23 : 22.5)
            key.label.adjustsFontSizeToFitWidth = false
            cap.accessibilityLabel = s
            if page == .letters {
                let capital = UILabel()
                capital.text = s.uppercased()
                capital.font = key.label.font
                capital.textColor = .label
                capital.textAlignment = .center
                capital.isHidden = true
                cap.addSubview(capital)
                key.capital = capital
            }
        case .shift:
            cap.accessibilityLabel = "Shift"
        case .delete:
            key.icon.image = UIImage(systemName: "delete.left", withConfiguration: Self.iconConfig)
            cap.accessibilityLabel = "Xóa"
        case .page(let p):
            key.label.text = p == .letters ? "ABC" : (p == .numbers ? "123" : "#+=")
            key.label.font = .systemFont(ofSize: 16)
            cap.accessibilityLabel = p == .letters ? "Chữ cái" : (p == .numbers ? "Số" : "Ký hiệu")
        case .space:
            key.label.text = "dấu cách"
            key.label.font = .systemFont(ofSize: 16)
            cap.accessibilityLabel = "dấu cách"
        case .ret:
            key.label.font = .systemFont(ofSize: 16)
        case .globe:
            key.icon.image = UIImage(systemName: "globe", withConfiguration: Self.iconConfig)
            cap.accessibilityLabel = "Bàn phím tiếp theo"
        }
        cap.backgroundColor = color(for: key, pressed: false)
        return key
    }

    // MARK: Layout (iPhone system keyboard metrics)

    override func safeAreaInsetsDidChange() {
        super.safeAreaInsetsDidChange()
        laidOut.removeAll()
        needsKeyFrames = true
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard bounds.width > 0, needsKeyFrames || laidOut[page] != bounds.size else { return }
        needsKeyFrames = false
        layout(page)
    }

    /// Places one page's keys for the current size.
    private func layout(_ page: Page) {
        TypingLog.mark("xếp phím")
        guard let rows = built[page], rows.count == 4 else { return }
        laidOut[page] = bounds.size
        overlay.frame = bounds
        Self.layoutPasses += 1
        let width = bounds.width
        // Where Apple's keyboard puts its keys (`KeyMetrics`), rows placed from the bottom up.
        let m = KeyMetrics.phone(width: width, homeButton: showsGlobe, extra: extraKeyHeight)
        let side = m.sideMargin + max(safeAreaInsets.left, safeAreaInsets.right)
        let gap = m.keyGap
        let unit = (width - 2 * side - 9 * gap) / 10
        let spans = m.rows(in: bounds.height)

        for (r, row) in rows.enumerated() {
            let frames = rowFrames(r, row, page: page, y: spans[r].top, height: spans[r].height, unit: unit, gap: gap, side: side, width: width)
            for (key, frame) in zip(row, frames) { key.cap.frame = frame }
        }

        // Hit areas meet halfway between keys and between rows, and reach the edges: no touch is
        // ever lost.
        for (r, row) in rows.enumerated() {
            let minY = r == 0 ? 0 : (spans[r - 1].top + spans[r - 1].height + spans[r].top) / 2
            let maxY = r == rows.count - 1 ? bounds.height : (spans[r].top + spans[r].height + spans[r + 1].top) / 2
            for (i, key) in row.enumerated() {
                let f = key.cap.frame
                let left = i == 0 ? 0 : (row[i - 1].cap.frame.maxX + f.minX) / 2
                let right = i == row.count - 1 ? width : (f.maxX + row[i + 1].cap.frame.minX) / 2
                key.hit = CGRect(x: left, y: minY, width: right - left, height: maxY - minY)
            }
        }

        for key in rows.joined() {
            let b = key.cap.bounds
            key.label.frame = key.character != nil && page == .letters ? b.offsetBy(dx: 0, dy: -1) : b.insetBy(dx: 2, dy: 0)
            key.capital?.frame = key.label.frame
            key.icon.frame = b
            if key.shadowSize != b.size {
                key.shadowSize = b.size
                key.cap.layer.shadowPath = UIBezierPath(roundedRect: b, cornerRadius: Self.cornerRadius).cgPath
            }
        }
    }

    private func rowFrames(_ r: Int, _ row: [Key], page: Page, y: CGFloat, height: CGFloat, unit u: CGFloat,
                           gap g: CGFloat, side: CGFloat, width w: CGFloat) -> [CGRect] {
        func rect(_ x: CGFloat, _ width: CGFloat) -> CGRect { CGRect(x: x, y: y, width: width, height: height) }

        switch r {
        case 0, 1:
            let n = CGFloat(row.count)
            var x = (w - (n * u + (n - 1) * g)) / 2
            return row.map { _ in
                defer { x += u + g }
                return rect(x, u)
            }
        case 2:
            let edge = (u * 1.36).rounded()
            let inner = row.count - 2
            var frames = [rect(side, edge)]
            if page == .letters {
                let n = CGFloat(inner)
                var x = (w - (n * u + (n - 1) * g)) / 2
                for _ in 0..<inner { frames.append(rect(x, u)); x += u + g }
            } else {
                let start = side + edge + 2 * g
                let end = w - side - edge - 2 * g
                let n = CGFloat(inner)
                let keyWidth = (end - start - (n - 1) * g) / n
                var x = start
                for _ in 0..<inner { frames.append(rect(x, keyWidth)); x += keyWidth + g }
            }
            frames.append(rect(w - side - edge, edge))
            return frames
        default:
            let pageWidth = (u * 2.35 + g).rounded()
            let returnWidth = (u * 2.6 + g).rounded()
            let globeWidth = (u * 1.25).rounded()
            let fixed = pageWidth + returnWidth + (showsGlobe ? globeWidth + g : 0) + 2 * g
            var x = side
            return row.map { key in
                let keyWidth: CGFloat
                switch key.kind {
                case .page: keyWidth = pageWidth
                case .globe: keyWidth = globeWidth
                case .ret: keyWidth = returnWidth
                default: keyWidth = w - 2 * side - fixed
                }
                defer { x += keyWidth + g }
                return rect(x, keyWidth)
            }
        }
    }

    // MARK: Appearance

    private func color(for key: Key, pressed: Bool) -> UIColor {
        switch key.kind {
        case .char, .space:
            return pressed ? KeyColor.pressed : KeyColor.key
        case .ret:
            if returnProminent { return returnEnabled ? (pressed ? KeyColor.prominentPressed : KeyColor.prominent) : KeyColor.special }
            return pressed ? KeyColor.key : KeyColor.special
        case .shift:
            return shift == .off && !pressed ? KeyColor.special : KeyColor.key
        default:
            return pressed ? KeyColor.key : KeyColor.special
        }
    }

    private func setPressed(_ key: Key, _ pressed: Bool) {
        key.cap.backgroundColor = color(for: key, pressed: pressed)
    }

    private func applyShift() {
        let upper = shift != .off
        UIView.performWithoutAnimation {
            for key in keys {
                switch key.kind {
                case .char:
                    // Both cases are drawn once; Shift only swaps which one shows.
                    if let capital = key.capital, capital.isHidden == upper {
                        capital.isHidden = !upper
                        key.label.isHidden = upper
                    }
                case .shift:
                    let image = shift == .locked ? Self.capsLock : (upper ? Self.shiftOn : Self.shiftOff)
                    if key.icon.image !== image { key.icon.image = image }
                    key.cap.backgroundColor = color(for: key, pressed: false)
                    key.cap.accessibilityTraits = upper ? [.keyboardKey, .selected] : .keyboardKey
                    key.cap.accessibilityValue = shift == .locked ? "Khóa chữ hoa" : nil
                default:
                    break
                }
            }
        }
    }

    private func applyReturn() {
        for key in keys where key.kind == .ret {
            if key.label.text != returnTitle { key.label.text = returnTitle }
            key.label.textColor = returnProminent && returnEnabled ? .white : (returnEnabled ? .label : .tertiaryLabel)
            key.cap.backgroundColor = color(for: key, pressed: false)
            key.cap.accessibilityLabel = returnTitle
        }
    }

    private func setTrackpad(_ on: Bool) {
        // Every page: the other thumb may switch pages while the bar is held.
        let all = Array(built.values.joined().joined())
        UIView.animate(withDuration: 0.15) {
            for key in all {
                key.label.alpha = on ? 0 : 1
                key.capital?.alpha = on ? 0 : 1
                key.icon.alpha = on ? 0 : 1
            }
        }
    }

    private func feedback(_ sound: Sound = .modifier, haptic: Bool = true) {
        pendingSound = sound
        pendingHaptic = haptic
    }

    private func playFeedback() {
        guard let sound = pendingSound else { return }
        pendingSound = nil
        play(sound)
        if hapticsEnabled && pendingHaptic {
            let started = CACurrentMediaTime()
            haptics.impactOccurred(intensity: 0.5)
            haptics.prepare()
            let took = CACurrentMediaTime() - started
            if took > 0.004 { TypingLog.add("⏱ rung mất \(Int(took * 1000))ms") }
        }
    }

    private func play(_ sound: Sound) {
        guard clicksEnabled else { return }
        if playsOwnClicks {
            Self.sounds.async { AudioServicesPlaySystemSound(sound.rawValue) }
        } else {
            let started = CACurrentMediaTime()
            UIDevice.current.playInputClick()
            let took = CACurrentMediaTime() - started
            if took > 0.004 { TypingLog.add("⏱ tiếng click mất \(Int(took * 1000))ms") }
        }
    }

    // MARK: Touches

    private func hitKey(at point: CGPoint) -> Key? {
        if let key = keys.first(where: { $0.hit.contains(point) }) { return key }
        return keys.min { a, b in
            hypot(a.cap.frame.midX - point.x, a.cap.frame.midY - point.y)
                < hypot(b.cap.frame.midX - point.x, b.cap.frame.midY - point.y)
        }
    }

    /// Taps a little above the top row still type. The translate bar sits right above it, and a
    /// thumb typing fast grazed it: the letter was lost and the language list opened. While keys
    /// are being pressed the reach goes further up, over most of the bar; at rest the bar keeps
    /// all but its lowest strip.
    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        let reach: CGFloat = CACurrentMediaTime() - lastTouchAt < 0.3 ? 30 : 10
        return point.x >= 0 && point.x <= bounds.width && point.y >= -reach && point.y <= bounds.height
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        TypingLog.mark("chạm")
        let now = CACurrentMediaTime()
        lastTouchAt = now
        // Two fingers landing in the same instant: the earlier one types first.
        for touch in touches.sorted(by: { $0.timestamp < $1.timestamp }) {
            // Another key while a vowel's accent row is open: the row closes and its letter stays
            // as typed. An accent chosen after it took back that other key instead ("aá").
            if accentTouch != nil, accentTouch !== touch { closeAccents() }
            // Typing with two thumbs: a key still held is typed by the time the next one goes down,
            // in the order pressed (⏎ held while the next message's first letter landed sent that
            // letter with the message), and it no longer opens its accents or the trackpad (another
            // key was typed after it, so it can't be taken back).
            for (other, track) in tracks where other !== touch {
                cancelLongPress(other)
                if !track.committed, other !== trackpadTouch { commitHeld(track) }
            }
            // Only now: a space typed just above may have brought back the letters.
            let point = touch.location(in: self)
            guard let key = hitKey(at: point) else { continue }
            // How long the touch took to get here: iOS holding it back, or the keyboard busy.
            let late = now - touch.timestamp
            if late > 0.03 { TypingLog.add("⌛ \(TypingLog.typed(key.character ?? "phím")) đến trễ \(Int(late * 1000))ms") }
            if point.y < 0 { TypingLog.add("↑ \(TypingLog.typed(key.character ?? "?")) (chạm cao)") }
            let track = Track(key: key, start: point)
            tracks[touch] = track
            begin(track, touch: touch, event: event)
        }
    }

    /// A key still held when the next one goes down: typed now, as its lift would have.
    private func commitHeld(_ track: Track) {
        switch track.key.kind {
        case .char:
            commit(track)
        case .space:
            track.committed = true
            delegate?.keyboardLayoutView(self, perform: .space)
        case .ret:
            track.committed = true
            if returnEnabled { delegate?.keyboardLayoutView(self, perform: .newline) }
        default:
            break
        }
    }

    private func closeAccents() {
        accentTouch = nil
        accentPicker?.removeFromSuperview()
        accentPicker = nil
    }

    private func begin(_ track: Track, touch: UITouch?, event: UIEvent?) {
        let key = track.key
        switch key.kind {
        case .char(let s):
            showPopup(track)
            if typesOnTouchDown {
                track.typedOnDown = true
                type(track)
            }
            feedback(.key)
            if let touch, Self.accents[s.lowercased()] != nil { scheduleLongPress(touch, after: 0.4) }
        case .shift:
            feedback()
            let now = CACurrentMediaTime()
            let quick = now - lastShiftTap < 0.35
            let from = shift
            switch shift {
            case .locked: shift = .off
            // A double tap locks capitals: from Shift tapped on, or from the capital a sentence
            // starts with (the first tap turns that off).
            case .once: shift = quick && lastShiftFrom == .off ? .locked : .off
            case .off: shift = quick && lastShiftFrom == .once ? .locked : .once
            }
            lastShiftTap = now
            lastShiftFrom = from
            applyShift()
            track.committed = true
            // Keys that type nothing are noted too: a tap that hit Shift is not read as a lost letter.
            TypingLog.add(shift == .locked ? "⇪" : shift == .once ? "⇧" : "⇧ tắt")
        case .delete:
            feedback(.delete)
            setPressed(key, true)
            delegate?.keyboardLayoutView(self, perform: .delete(word: false))
            startDeleteRepeat()
        case .page(let p):
            feedback()
            if page == .letters { capsLock = shift == .locked }
            page = p
            shift = p == .letters ? lettersShift : .off
            showPage()
            track.committed = true
            TypingLog.add(p == .letters ? "▦ ABC" : p == .numbers ? "▦ 123" : "▦ #+=")
        case .space:
            feedback()
            setPressed(key, true)
            if typesOnTouchDown {
                track.committed = true
                track.typedOnDown = true
                delegate?.keyboardLayoutView(self, perform: .space)
            }
            if let touch {
                track.anchorX = touch.location(in: self).x
                scheduleLongPress(touch, after: 0.35)
            }
        case .ret:
            feedback()
            setPressed(key, true)
        case .globe:
            setPressed(key, true)
            if let event { delegate?.keyboardLayoutView(self, globeTouchedWith: event) }
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches {
            guard let track = tracks[touch] else { continue }
            let point = touch.location(in: self)

            if touch === trackpadTouch {
                let step: CGFloat = 8
                let steps = Int((point.x - track.anchorX) / step)
                if steps != 0 {
                    track.anchorX += CGFloat(steps) * step
                    delegate?.keyboardLayoutView(self, perform: .moveCursor(steps))
                }
                continue
            }
            if touch === accentTouch {
                accentPicker?.select(atX: point.x)
                continue
            }
            switch track.key.kind {
            case .char:
                // A finger that moves off where it held is not holding the key: no accent row. A
                // slow roll from "d" to the next letter could open the "đ" row.
                if hypot(point.x - track.holdFrom.x, point.y - track.holdFrom.y) > 10 { cancelLongPress(touch) }
                guard let key = hitKey(at: point), key !== track.key, let s = key.character else { continue }
                guard !track.committed else {
                    // Typed as the finger landed: the finger now on another letter types nothing
                    // more. Noted, so a letter lost this way (a thumb that rolled on to the next
                    // key without lifting) is told from one the screen never passed on.
                    if track.slidTo != s {
                        track.slidTo = s
                        TypingLog.add("↝ \(TypingLog.typed(track.key.character ?? "?")) trượt sang \(TypingLog.typed(s)), không gõ")
                    }
                    continue
                }
                // Sliding to another letter before lifting types that letter instead.
                track.key = key
                track.holdFrom = point
                showPopup(track)
                cancelLongPress(touch)
                if let s = key.character, Self.accents[s.lowercased()] != nil { scheduleLongPress(touch, after: 0.4) }
            case .globe:
                if let event { delegate?.keyboardLayoutView(self, globeTouchedWith: event) }
            default:
                break
            }
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches { finish(touch, event: event, cancelled: false) }
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches { finish(touch, event: event, cancelled: true) }
    }

    private func finish(_ touch: UITouch, event: UIEvent?, cancelled: Bool) {
        cancelLongPress(touch)
        guard let track = tracks.removeValue(forKey: touch) else { return }
        removePopup(track)
        // A real tap lasts 30 ms or more: one that began and ended together was held back by iOS
        // until the finger lifted (the edge delay `KeyboardViewController.viewDidAppear` turns off).
        if !cancelled, let s = track.key.character, CACurrentMediaTime() - track.beganAt < 0.012 {
            TypingLog.add("⌛ \(TypingLog.typed(s)) iOS giữ lại tới lúc nhấc tay")
        }
        var cancelled = cancelled
        if cancelled, track.key.character != nil, !track.committed {
            // iOS sometimes cancels a quick tap while it checks for a system swipe; a short tap
            // that barely moved was a key press, and is typed rather than lost.
            let point = touch.location(in: self)
            let quick = CACurrentMediaTime() - track.beganAt < 0.35 && hypot(point.x - track.start.x, point.y - track.start.y) < 14
            TypingLog.add("x \(TypingLog.typed(track.key.character ?? "?")) bị iOS hủy, \(quick ? "vẫn gõ" : "bỏ")")
            if quick { cancelled = false }
        }

        if touch === trackpadTouch {
            trackpadTouch = nil
            setTrackpad(false)
            setPressed(track.key, false)
            return
        }
        if touch === accentTouch {
            accentTouch = nil
            TypingLog.add("… bảng dấu đóng: \(cancelled ? "bị hủy" : accentPicker?.selected == 0 ? "giữ nguyên" : TypingLog.typed(accentPicker?.selectedText ?? "?"))")
            if !cancelled, let picker = accentPicker, let text = picker.selectedText {
                if !track.typedOnDown {
                    consumeOnceShift()
                    delegate?.keyboardLayoutView(self, perform: .text(text))
                } else if picker.selected != 0 {
                    // The plain letter went in as the finger landed: the accented one replaces it.
                    delegate?.keyboardLayoutView(self, perform: .undoLast)
                    delegate?.keyboardLayoutView(self, perform: .text(text))
                }
            }
            accentPicker?.removeFromSuperview()
            accentPicker = nil
            return
        }

        end(track, event: event, cancelled: cancelled)
    }

    private func end(_ track: Track, event: UIEvent?, cancelled: Bool) {
        switch track.key.kind {
        case .char:
            if !track.committed && !cancelled { commit(track) }
        case .delete:
            stopDeleteRepeat()
            setPressed(track.key, false)
        case .space:
            setPressed(track.key, false)
            if !cancelled && !track.committed { delegate?.keyboardLayoutView(self, perform: .space) }
        case .ret:
            setPressed(track.key, false)
            if !cancelled && returnEnabled && !track.committed { delegate?.keyboardLayoutView(self, perform: .newline) }
        case .globe:
            setPressed(track.key, false)
            if let event { delegate?.keyboardLayoutView(self, globeTouchedWith: event) }
        default:
            break
        }
    }

    private func commit(_ track: Track) {
        removePopup(track)
        type(track)
    }

    /// Types the key's character; its pop-up stays up while the finger does.
    private func type(_ track: Track) {
        track.committed = true
        guard let s = track.key.character else { return }
        track.upper = shift != .off && page == .letters
        let text = track.upper ? s.uppercased() : s
        // Before the controller hears of the key: it may turn Shift straight back on (a field in
        // capitals; afterwards, every word after the first came out lower case).
        consumeOnceShift()
        if page == .letters, let c = text.first {
            delegate?.keyboardLayoutView(self, perform: .letter(c))
        } else {
            delegate?.keyboardLayoutView(self, perform: .text(text))
        }
    }

    private func consumeOnceShift() {
        if shift == .once {
            shift = .off
            applyShift()
        }
    }

    // MARK: Pop-ups and accents

    /// Marks the key under the finger: its pop-up, or the key lit when pop-ups are off.
    private func showPopup(_ track: Track) {
        guard let s = track.key.character else { return removePopup(track) }
        if !showsPopups {
            guard track.lit !== track.key else { return }
            if let lit = track.lit { unlight(lit) }
            light(track.key)
            track.lit = track.key
            return
        }
        let text = shift != .off && page == .letters ? s.uppercased() : s
        let popup: KeyPopup
        if let current = track.popup {
            popup = current
        } else if let spare = sparePopups.popLast() {
            popup = spare
        } else {
            popup = KeyPopup()
            overlay.addSubview(popup)
        }
        popup.show(keyFrame: track.key.cap.frame, width: bounds.width, text: text, traits: traitCollection)
        track.popup = popup
    }

    private func removePopup(_ track: Track) {
        if let lit = track.lit {
            track.lit = nil
            unlight(lit)
        }
        guard let popup = track.popup else { return }
        popup.isHidden = true
        sparePopups.append(popup)
        track.popup = nil
    }

    private func light(_ key: Key) {
        key.litBy += 1
        key.litAt = CACurrentMediaTime()
        setPressed(key, true)
    }

    /// A finger left the key. It goes dark once no finger holds it (two on one key: dd, oo), and not
    /// before it has been lit 70 ms: near the screen edges iOS can hand over a touch and its lift
    /// together, and a key lit and dark in the same moment never shows it was pressed.
    private func unlight(_ key: Key) {
        key.litBy = max(0, key.litBy - 1)
        guard key.litBy == 0 else { return }
        let shown = CACurrentMediaTime() - key.litAt
        guard shown < Self.minimumLit else { return setPressed(key, false) }
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.minimumLit - shown) { [weak self, weak key] in
            guard let self, let key, key.litBy == 0 else { return }
            self.setPressed(key, false)
        }
    }

    private static let minimumLit: CFTimeInterval = 0.07

    private func scheduleLongPress(_ touch: UITouch, after delay: TimeInterval) {
        longPressTimer?.invalidate()
        longPressTouch = touch
        longPressTimer = Timer.scheduledTimer(timeInterval: delay, target: self, selector: #selector(longPressFired),
                                              userInfo: nil, repeats: false)
    }

    private func cancelLongPress(_ touch: UITouch) {
        guard touch === longPressTouch else { return }
        longPressTimer?.invalidate()
        longPressTimer = nil
        longPressTouch = nil
    }

    @objc private func longPressFired() {
        TypingLog.mark("giữ phím")
        longPressTimer = nil
        guard let touch = longPressTouch, let track = tracks[touch], !track.committed || track.typedOnDown else { return }
        longPressTouch = nil
        switch track.key.kind {
        case .char(let s):
            guard let options = Self.accents[s.lowercased()] else { return }
            // Lifting on the plain letter types nothing more: noted, so it is not read as a lost key.
            TypingLog.add("… giữ \(TypingLog.typed(s)): bảng dấu")
            let upper = track.typedOnDown ? track.upper : shift != .off && page == .letters
            let shown = upper ? options.map { $0.uppercased() } : options
            removePopup(track)
            let picker = AccentPicker(options: shown, keyFrame: track.key.cap.frame, width: bounds.width)
            overlay.addSubview(picker)
            picker.select(atX: touch.location(in: self).x)
            accentPicker = picker
            accentTouch = touch
            if hapticsEnabled { haptics.impactOccurred(intensity: 0.7) }
        case .space:
            // The space typed as the finger landed goes: holding the bar moves the cursor instead.
            if track.typedOnDown { delegate?.keyboardLayoutView(self, perform: .undoLast) }
            trackpadTouch = touch
            track.anchorX = touch.location(in: self).x
            setTrackpad(true)
            if hapticsEnabled { haptics.impactOccurred(intensity: 0.8) }
        default:
            break
        }
    }

    // MARK: Delete repeat: letters first, then whole words

    private func startDeleteRepeat() {
        deleteCount = 0
        deleteTimer?.invalidate()
        deleteTimer = Timer.scheduledTimer(timeInterval: 0.45, target: self, selector: #selector(beginDeleteRepeat),
                                           userInfo: nil, repeats: false)
    }

    @objc private func beginDeleteRepeat() {
        deleteTimer = Timer.scheduledTimer(timeInterval: 0.09, target: self, selector: #selector(deleteTick),
                                           userInfo: nil, repeats: true)
        deleteTick()
    }

    @objc private func deleteTick() {
        onRepeat?()
        deleteCount += 1
        let words = deleteCount > 16
        if words && deleteCount % 2 == 1 { return }
        delegate?.keyboardLayoutView(self, perform: .delete(word: words))
        // After the edit and its frame, as for every key: without Full Access the click waits on
        // iOS's sound service, and each deletion waited behind it.
        feedback(.delete, haptic: false)
    }

    private func stopDeleteRepeat() {
        deleteTimer?.invalidate()
        deleteTimer = nil
    }

    // MARK: Typing benchmark (CI)

    /// Layout passes that placed the keys; the typing benchmark reports them per key.
    static var layoutPasses = 0
    /// Called as each delete repeat starts, so the benchmark can time it.
    var onRepeat: (() -> Void)?

    /// Presses the key for `character` as a finger would, through the same handling, minus
    /// UIKit's touch delivery. Returns what `benchmarkRelease` needs.
    func benchmarkPress(_ character: Character) -> AnyObject? {
        let wanted = String(character).lowercased()
        let key: Key?
        switch character {
        case " ": key = keys.first { $0.kind == .space }
        case "\u{8}": key = keys.first { $0.kind == .delete }
        // 123 / ABC, at the left of the bottom row.
        case "\u{2}": key = keys.last { if case .page = $0.kind { return true } else { return false } }
        default: key = keys.first { $0.character?.lowercased() == wanted }
        }
        guard let key else { return nil }
        lastTouchAt = CACurrentMediaTime()
        for (_, track) in tracks where !track.committed && track.key.character != nil { commit(track) }
        let track = Track(key: key)
        begin(track, touch: nil, event: nil)
        return track
    }

    func benchmarkRelease(_ token: AnyObject) {
        guard let track = token as? Track else { return }
        removePopup(track)
        end(track, event: nil, cancelled: false)
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil {
            stopDeleteRepeat()
            longPressTimer?.invalidate()
            longPressTimer = nil
            if trackpadTouch != nil { setTrackpad(false) }
            for track in tracks.values {
                removePopup(track)
                // ⌫, space or ⏎ held as the keyboard went away: not drawn pressed when it comes back.
                setPressed(track.key, false)
            }
            tracks.removeAll()
            closeAccents()
            trackpadTouch = nil
        } else {
            haptics.prepare()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in self?.prepareHidden() }
        }
    }
}

// MARK: - Colours

enum KeyColor {
    static let key = UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 1, alpha: 0.32) : .white }
    static let special = UIColor {
        $0.userInterfaceStyle == .dark ? UIColor(white: 1, alpha: 0.15) : UIColor(red: 0.671, green: 0.698, blue: 0.737, alpha: 1)
    }
    static let popup = UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.42, alpha: 1) : .white }
    /// A letter or the space bar while pressed: grey in light mode, lighter than the key in dark,
    /// as the system keyboard lights its keys.
    static let pressed = UIColor {
        $0.userInterfaceStyle == .dark ? UIColor(white: 1, alpha: 0.55) : UIColor(red: 0.671, green: 0.698, blue: 0.737, alpha: 1)
    }
    /// Send, Go, Search… in the Mộc Sinh green, like the Dịch button.
    static let prominent = Brand.greenFill
    static let prominentPressed = UIColor(light: 0x084D32, dark: 0x146140)
}

// MARK: - Key pop-up

/// The enlarged letter shown above a key while it is held, shaped like the system one.
/// Built once and reshaped for each press.
private final class KeyPopup: UIView {
    private let shape = CAShapeLayer()
    private let label = UILabel()
    private var shapedFor: (key: CGRect, width: CGFloat)?

    init() {
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        shape.shadowColor = UIColor.black.cgColor
        shape.shadowOpacity = 0.25
        shape.shadowRadius = 2
        shape.shadowOffset = CGSize(width: 0, height: 1)
        layer.addSublayer(shape)
        label.textAlignment = .center
        label.font = .systemFont(ofSize: 34)
        label.textColor = .label
        addSubview(label)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func show(keyFrame k: CGRect, width: CGFloat, text: String, traits: UITraitCollection) {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        if shapedFor?.key != k || shapedFor?.width != width {
            shapedFor = (k, width)
            reshape(keyFrame: k, width: width)
        }
        shape.fillColor = KeyColor.popup.resolvedColor(with: traits).cgColor
        CATransaction.commit()
        if label.text != text { label.text = text }
        isHidden = false
    }

    private func reshape(keyFrame k: CGRect, width: CGFloat) {
        let balloonWidth = max(k.width + 22, 48)
        let balloonHeight = k.height + 14
        let balloonX = min(max(k.midX - balloonWidth / 2, 0), width - balloonWidth)
        let balloonY = k.minY - balloonHeight - 6
        let minX = min(balloonX, k.minX)
        frame = CGRect(x: minX, y: balloonY, width: max(balloonX + balloonWidth, k.maxX) - minX, height: k.maxY - balloonY)

        let balloon = CGRect(x: balloonX - frame.minX, y: 0, width: balloonWidth, height: balloonHeight)
        let keyRect = k.offsetBy(dx: -frame.minX, dy: -frame.minY)
        let path = UIBezierPath(roundedRect: balloon, cornerRadius: 10)
        let neck = UIBezierPath()
        neck.move(to: CGPoint(x: balloon.minX + 3, y: balloon.maxY - 8))
        neck.addLine(to: CGPoint(x: balloon.maxX - 3, y: balloon.maxY - 8))
        neck.addLine(to: CGPoint(x: keyRect.maxX, y: keyRect.minY + 8))
        neck.addLine(to: CGPoint(x: keyRect.minX, y: keyRect.minY + 8))
        neck.close()
        path.append(neck)
        path.append(UIBezierPath(roundedRect: keyRect, cornerRadius: KeyboardLayoutView.cornerRadius))
        shape.path = path.cgPath
        shape.shadowPath = path.cgPath
        label.frame = balloon.insetBy(dx: 2, dy: 2)
    }
}

// MARK: - Accent picker

/// The row of accented letters shown when a vowel is held; slide to choose, lift to type.
private final class AccentPicker: UIView {
    private let options: [String]
    private var cells: [UILabel] = []
    private let cellWidth: CGFloat
    private let inset: CGFloat = 6
    private(set) var selected = 0

    var selectedText: String? { options.indices.contains(selected) ? options[selected] : nil }

    init(options: [String], keyFrame k: CGRect, width: CGFloat) {
        self.options = options
        cellWidth = max(min(k.width, 40), 30)
        let w = cellWidth * CGFloat(options.count) + 2 * inset
        let h = k.height + 12
        let x = min(max(k.minX - inset, 2), width - w - 2)
        super.init(frame: CGRect(x: x, y: k.minY - h - 8, width: w, height: h))
        isUserInteractionEnabled = false
        backgroundColor = KeyColor.popup
        layer.cornerRadius = 10
        layer.cornerCurve = .continuous
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.3
        layer.shadowRadius = 4
        layer.shadowOffset = CGSize(width: 0, height: 2)

        for (i, option) in options.enumerated() {
            let label = UILabel(frame: CGRect(x: inset + CGFloat(i) * cellWidth, y: inset, width: cellWidth, height: h - 2 * inset))
            label.text = option
            label.textAlignment = .center
            label.font = .systemFont(ofSize: 24)
            label.layer.cornerRadius = 6
            label.layer.masksToBounds = true
            addSubview(label)
            cells.append(label)
        }
        update()
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    /// `x` is in the keyboard's coordinates.
    func select(atX x: CGFloat) {
        let index = Int((x - frame.minX - inset) / cellWidth)
        let clamped = min(max(index, 0), options.count - 1)
        if clamped != selected {
            selected = clamped
            update()
        }
    }

    private func update() {
        for (i, cell) in cells.enumerated() {
            cell.backgroundColor = i == selected ? .systemBlue : .clear
            cell.textColor = i == selected ? .white : .label
        }
    }
}
