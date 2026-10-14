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
        let icon = UIImageView()
        var hit = CGRect.zero

        init(kind: Kind) { self.kind = kind }

        var character: String? {
            if case .char(let s) = kind { return s }
            return nil
        }
    }

    private final class Track {
        var key: Key
        var committed = false
        var popup: KeyPopup?
        var anchorX: CGFloat = 0

        init(key: Key) { self.key = key }
    }

    weak var delegate: KeyboardLayoutViewDelegate?
    var hapticsEnabled = true
    var showsGlobe = false {
        didSet { if showsGlobe != oldValue { rebuild() } }
    }
    private(set) var shift: Shift = .off

    private var page: Page = .letters
    private var rows: [[Key]] = []
    private var keys: [Key] = []
    private var tracks: [UITouch: Track] = [:]
    private var lastShiftTap: CFTimeInterval = 0

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

    static let cornerRadius: CGFloat = {
        if #available(iOS 26.0, *) { return 8.5 }
        return 5
    }()
    private static let iconConfig = UIImage.SymbolConfiguration(pointSize: 18, weight: .regular)

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
        isMultipleTouchEnabled = true
        clipsToBounds = false
        rebuild()
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    // MARK: State from the controller

    /// Sentence-start capitalisation; Caps Lock wins.
    func setAutoShift(_ on: Bool) {
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
        rebuild()
    }

    // MARK: Building

    private func rowKinds() -> [[Kind]] {
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

    private func rebuild() {
        keys.forEach { $0.cap.removeFromSuperview() }
        rows = rowKinds().map { $0.map(makeKey) }
        keys = rows.flatMap { $0 }
        keys.forEach { addSubview($0.cap) }
        // Pop-ups stay above the keys.
        for track in tracks.values { if let popup = track.popup { bringSubviewToFront(popup) } }
        applyShift()
        applyReturn()
        setNeedsLayout()
    }

    private func makeKey(_ kind: Kind) -> Key {
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
            cap.accessibilityLabel = s
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

    override func layoutSubviews() {
        super.layoutSubviews()
        guard bounds.width > 0, rows.count == 4 else { return }
        let width = bounds.width
        let landscape = width > 500
        let side: CGFloat = (landscape ? 4 : 3) + max(safeAreaInsets.left, safeAreaInsets.right)
        let gap: CGFloat = 6
        let rowGap: CGFloat = landscape ? 6 : 11
        let top: CGFloat = landscape ? 4 : 8
        let bottom: CGFloat = landscape ? 2 : 4
        let keyHeight = max(28, (bounds.height - top - bottom - 3 * rowGap) / 4)
        let unit = (width - 2 * side - 9 * gap) / 10

        for (r, row) in rows.enumerated() {
            let y = top + CGFloat(r) * (keyHeight + rowGap)
            let frames = rowFrames(r, row, y: y, height: keyHeight, unit: unit, gap: gap, side: side, width: width)
            for (key, frame) in zip(row, frames) { key.cap.frame = frame }
        }

        // Hit areas meet halfway between keys and reach the edges: no touch is ever lost.
        for (r, row) in rows.enumerated() {
            for (i, key) in row.enumerated() {
                let f = key.cap.frame
                let left = i == 0 ? 0 : (row[i - 1].cap.frame.maxX + f.minX) / 2
                let right = i == row.count - 1 ? width : (f.maxX + row[i + 1].cap.frame.minX) / 2
                let minY = r == 0 ? 0 : f.minY - rowGap / 2
                let maxY = r == rows.count - 1 ? bounds.height : f.maxY + rowGap / 2
                key.hit = CGRect(x: left, y: minY, width: right - left, height: maxY - minY)
            }
        }

        for key in keys {
            let b = key.cap.bounds
            key.label.frame = key.character != nil && page == .letters ? b.offsetBy(dx: 0, dy: -1) : b.insetBy(dx: 2, dy: 0)
            key.icon.frame = b
            key.cap.layer.shadowPath = UIBezierPath(roundedRect: b, cornerRadius: Self.cornerRadius).cgPath
        }
    }

    private func rowFrames(_ r: Int, _ row: [Key], y: CGFloat, height: CGFloat, unit u: CGFloat,
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
            return pressed ? KeyColor.special : KeyColor.key
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
                case .char(let s) where page == .letters:
                    key.label.text = upper ? s.uppercased() : s
                case .shift:
                    let name = shift == .locked ? "capslock.fill" : (upper ? "shift.fill" : "shift")
                    key.icon.image = UIImage(systemName: name, withConfiguration: Self.iconConfig)
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
            key.label.text = returnTitle
            key.label.textColor = returnProminent && returnEnabled ? .white : (returnEnabled ? .label : .tertiaryLabel)
            key.cap.backgroundColor = color(for: key, pressed: false)
            key.cap.accessibilityLabel = returnTitle
        }
    }

    private func setTrackpad(_ on: Bool) {
        UIView.animate(withDuration: 0.15) {
            for key in self.keys {
                key.label.alpha = on ? 0 : 1
                key.icon.alpha = on ? 0 : 1
            }
        }
    }

    private func feedback() {
        UIDevice.current.playInputClick()
        if hapticsEnabled {
            haptics.impactOccurred(intensity: 0.5)
            haptics.prepare()
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

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches {
            guard let key = hitKey(at: touch.location(in: self)) else { continue }
            // Typing with two thumbs: an earlier letter is committed as soon as the next key goes down.
            for (other, track) in tracks where other !== touch && other !== accentTouch && !track.committed && track.key.character != nil {
                cancelLongPress(other)
                commit(track)
            }
            let track = Track(key: key)
            tracks[touch] = track
            begin(track, touch: touch, event: event)
        }
    }

    private func begin(_ track: Track, touch: UITouch, event: UIEvent?) {
        let key = track.key
        switch key.kind {
        case .char(let s):
            feedback()
            showPopup(track)
            if Self.accents[s.lowercased()] != nil { scheduleLongPress(touch, after: 0.4) }
        case .shift:
            feedback()
            let now = CACurrentMediaTime()
            switch shift {
            case .locked: shift = .off
            case .once: shift = now - lastShiftTap < 0.35 ? .locked : .off
            case .off: shift = .once
            }
            lastShiftTap = now
            applyShift()
            track.committed = true
        case .delete:
            feedback()
            setPressed(key, true)
            delegate?.keyboardLayoutView(self, perform: .delete(word: false))
            startDeleteRepeat()
        case .page(let p):
            feedback()
            page = p
            if p != .letters { shift = .off }
            rebuild()
            track.committed = true
        case .space:
            feedback()
            setPressed(key, true)
            track.anchorX = touch.location(in: self).x
            scheduleLongPress(touch, after: 0.35)
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
                // Sliding to another letter before lifting types that letter instead.
                guard !track.committed, let key = hitKey(at: point), key !== track.key, key.character != nil else { continue }
                track.key = key
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

        if touch === trackpadTouch {
            trackpadTouch = nil
            setTrackpad(false)
            setPressed(track.key, false)
            return
        }
        if touch === accentTouch {
            accentTouch = nil
            if !cancelled, let text = accentPicker?.selectedText {
                delegate?.keyboardLayoutView(self, perform: .text(text))
                consumeOnceShift()
            }
            accentPicker?.removeFromSuperview()
            accentPicker = nil
            return
        }

        switch track.key.kind {
        case .char:
            if !track.committed && !cancelled { commit(track) }
        case .delete:
            stopDeleteRepeat()
            setPressed(track.key, false)
        case .space:
            setPressed(track.key, false)
            if !cancelled { delegate?.keyboardLayoutView(self, perform: .space) }
        case .ret:
            setPressed(track.key, false)
            if !cancelled && returnEnabled { delegate?.keyboardLayoutView(self, perform: .newline) }
        case .globe:
            setPressed(track.key, false)
            if let event { delegate?.keyboardLayoutView(self, globeTouchedWith: event) }
        default:
            break
        }
    }

    private func commit(_ track: Track) {
        track.committed = true
        removePopup(track)
        guard let s = track.key.character else { return }
        let text = shift != .off && page == .letters ? s.uppercased() : s
        if page == .letters, let c = text.first {
            delegate?.keyboardLayoutView(self, perform: .letter(c))
        } else {
            delegate?.keyboardLayoutView(self, perform: .text(text))
        }
        consumeOnceShift()
    }

    private func consumeOnceShift() {
        if shift == .once {
            shift = .off
            applyShift()
        }
    }

    // MARK: Pop-ups and accents

    private func showPopup(_ track: Track) {
        removePopup(track)
        guard let s = track.key.character else { return }
        let text = shift != .off && page == .letters ? s.uppercased() : s
        let popup = KeyPopup(keyFrame: track.key.cap.frame, width: bounds.width, text: text, traits: traitCollection)
        addSubview(popup)
        track.popup = popup
    }

    private func removePopup(_ track: Track) {
        track.popup?.removeFromSuperview()
        track.popup = nil
    }

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
        longPressTimer = nil
        guard let touch = longPressTouch, let track = tracks[touch], !track.committed else { return }
        longPressTouch = nil
        switch track.key.kind {
        case .char(let s):
            guard let options = Self.accents[s.lowercased()] else { return }
            let shown = shift != .off && page == .letters ? options.map { $0.uppercased() } : options
            removePopup(track)
            let picker = AccentPicker(options: shown, keyFrame: track.key.cap.frame, width: bounds.width)
            addSubview(picker)
            picker.select(atX: touch.location(in: self).x)
            accentPicker = picker
            accentTouch = touch
            if hapticsEnabled { haptics.impactOccurred(intensity: 0.7) }
        case .space:
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
        deleteCount += 1
        let words = deleteCount > 16
        if words && deleteCount % 2 == 1 { return }
        UIDevice.current.playInputClick()
        delegate?.keyboardLayoutView(self, perform: .delete(word: words))
    }

    private func stopDeleteRepeat() {
        deleteTimer?.invalidate()
        deleteTimer = nil
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil {
            stopDeleteRepeat()
            longPressTimer?.invalidate()
            longPressTimer = nil
            for track in tracks.values { removePopup(track) }
            tracks.removeAll()
            accentPicker?.removeFromSuperview()
            accentPicker = nil
            if trackpadTouch != nil { setTrackpad(false) }
        } else {
            haptics.prepare()
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
    static let prominent = UIColor.systemBlue
    static let prominentPressed = UIColor { $0.userInterfaceStyle == .dark ? UIColor.systemBlue.withAlphaComponent(0.7) : UIColor(red: 0.0, green: 0.38, blue: 0.85, alpha: 1) }
}

// MARK: - Key pop-up

/// The enlarged letter shown above a key while it is held, shaped like the system one.
private final class KeyPopup: UIView {
    init(keyFrame k: CGRect, width: CGFloat, text: String, traits: UITraitCollection) {
        let balloonWidth = max(k.width + 22, 48)
        let balloonHeight = k.height + 14
        let balloonX = min(max(k.midX - balloonWidth / 2, 0), width - balloonWidth)
        let balloonY = k.minY - balloonHeight - 6
        let minX = min(balloonX, k.minX)
        let box = CGRect(x: minX, y: balloonY, width: max(balloonX + balloonWidth, k.maxX) - minX, height: k.maxY - balloonY)
        super.init(frame: box)
        isUserInteractionEnabled = false

        let balloon = CGRect(x: balloonX - box.minX, y: 0, width: balloonWidth, height: balloonHeight)
        let keyRect = k.offsetBy(dx: -box.minX, dy: -box.minY)
        let path = UIBezierPath(roundedRect: balloon, cornerRadius: 10)
        let neck = UIBezierPath()
        neck.move(to: CGPoint(x: balloon.minX + 3, y: balloon.maxY - 8))
        neck.addLine(to: CGPoint(x: balloon.maxX - 3, y: balloon.maxY - 8))
        neck.addLine(to: CGPoint(x: keyRect.maxX, y: keyRect.minY + 8))
        neck.addLine(to: CGPoint(x: keyRect.minX, y: keyRect.minY + 8))
        neck.close()
        path.append(neck)
        path.append(UIBezierPath(roundedRect: keyRect, cornerRadius: KeyboardLayoutView.cornerRadius))

        let shape = CAShapeLayer()
        shape.path = path.cgPath
        shape.fillColor = KeyColor.popup.resolvedColor(with: traits).cgColor
        shape.shadowColor = UIColor.black.cgColor
        shape.shadowOpacity = 0.25
        shape.shadowRadius = 2
        shape.shadowOffset = CGSize(width: 0, height: 1)
        shape.shadowPath = path.cgPath
        layer.addSublayer(shape)

        let label = UILabel(frame: balloon.insetBy(dx: 2, dy: 2))
        label.text = text
        label.textAlignment = .center
        label.font = .systemFont(ofSize: 34)
        label.adjustsFontSizeToFitWidth = true
        label.minimumScaleFactor = 0.5
        label.textColor = .label
        addSubview(label)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }
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
