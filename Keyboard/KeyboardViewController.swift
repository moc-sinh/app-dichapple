import UIKit

/// The translation keyboard: an info line, a translate bar and an iOS-style Telex keyboard.
///
/// - Info line: the Vietnamese translation of the partner's message the user just copied,
///   or the meaning of what was just sent.
/// - Translate bar: target language ("Tự động" follows the partner), "Dịch", undo.
/// - Keys: `KeyboardLayoutView`.
final class KeyboardViewController: UIInputViewController, KeyboardLayoutViewDelegate {

    private enum Mode: Equatable {
        case normal, picking, busy, done, failed(String), panel(String)
    }

    private enum Info {
        case idle
        /// Something new was copied; reading it waits for a tap (see `refreshIncoming`).
        case copied
        case incomingBusy
        case incoming(source: String, translation: Translation)
        case outgoing(Translation, original: String)
        case message(String)
    }

    private var mode: Mode = .normal { didSet { renderBar() } }
    private var info: Info = .idle { didSet { renderInfo() } }

    private let infoButton = UIButton(configuration: .plain())
    private let bar = UIStackView()
    private let keyboard = KeyboardLayoutView()
    private let panel = UIStackView()
    private let panelText = UITextView()
    private let panelButton = UIButton(configuration: .filled())
    private var panelAction: (() -> Void)?

    private var infoHeight: NSLayoutConstraint?
    private var barHeight: NSLayoutConstraint?
    private var keysHeight: NSLayoutConstraint?

    private lazy var targetCode = SharedStore.targetCode
    private var activeTarget: Language?
    private var original = ""
    private var task: Task<Void, Never>?
    private var incomingTask: Task<Void, Never>?
    private var pollTimer: Timer?
    /// The clipboard already read (or asked about) in instant mode, so iOS isn't asked twice.
    private var attemptedPasteboard: Int?
    private var benchmark: TypingBenchmark?
    /// Clipboard checks made on the main thread, and the time they took (typing benchmark).
    /// Checks now run on `clipboardQueue`, so these stay at zero.
    static var mainThreadPasteboardChecks = 0
    static var mainThreadPasteboardTime: CFTimeInterval = 0

    // The clipboard, asked off the main thread: every question is a round trip to another
    // process, long enough to drop frames, so it never runs while keys are being pressed.
    private let clipboardQueue = DispatchQueue(label: "com.mocsinh.mstrans.clipboard", qos: .utility)
    private var clipboardCheckRunning = false
    /// From the last check: the clipboard holds text that was not translated yet.
    private var copyPending = false
    private var copyChangeCount = 0
    private var copyButtonShown = false
    private var lastKeyAt: CFTimeInterval = 0

    // Telex: the word at the cursor while it is being typed.
    private var word = TelexWord()
    private var lastSpaceAt: CFTimeInterval = 0

    // MARK: Lifecycle

    // Each step is recorded so the app's diagnostics can tell how far the keyboard got.
    override init(nibName nibNameOrNil: String?, bundle nibBundleOrNil: Bundle?) {
        super.init(nibName: nibNameOrNil, bundle: nibBundleOrNil)
        SharedStore.noteKeyboard("1 khởi động")
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        SharedStore.noteKeyboard("1 khởi động")
    }

    override func loadView() {
        let input = KeyboardInputView(frame: .zero, inputViewStyle: .keyboard)
        input.allowsSelfSizing = true
        view = input
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        SharedStore.noteKeyboard("2 tạo giao diện")
        // The first language check loads a model and the first Telex key builds its tables;
        // do both now, off the main thread, not on the first key press.
        DispatchQueue.global(qos: .userInitiated).async {
            _ = Telex.output(for: "nguwowif")
            _ = LanguageDetector.isVietnamese("Xin chào")
        }

        infoButton.contentHorizontalAlignment = .leading
        infoButton.addAction(UIAction { [weak self] _ in self?.openInfo() }, for: .touchUpInside)

        bar.axis = .horizontal
        bar.spacing = 6
        bar.alignment = .center
        bar.isLayoutMarginsRelativeArrangement = true
        bar.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 0, leading: 8, bottom: 0, trailing: 8)

        keyboard.delegate = self
        buildPanel()

        for v in [infoButton, bar, keyboard, panel] as [UIView] {
            v.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(v)
        }
        let infoHeight = infoButton.heightAnchor.constraint(equalToConstant: 30)
        let barHeight = bar.heightAnchor.constraint(equalToConstant: 42)
        let keysHeight = keyboard.heightAnchor.constraint(equalToConstant: 216)
        let bottom = keyboard.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        bottom.priority = UILayoutPriority(999)
        NSLayoutConstraint.activate([
            infoButton.topAnchor.constraint(equalTo: view.topAnchor),
            infoButton.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 4),
            infoButton.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -4),
            infoHeight,
            bar.topAnchor.constraint(equalTo: infoButton.bottomAnchor),
            bar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            bar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            barHeight,
            keyboard.topAnchor.constraint(equalTo: bar.bottomAnchor),
            keyboard.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            keyboard.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            keysHeight,
            bottom,
            panel.topAnchor.constraint(equalTo: bar.bottomAnchor, constant: 4),
            panel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 6),
            panel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -6),
            panel.bottomAnchor.constraint(equalTo: keyboard.bottomAnchor, constant: -4),
        ])
        self.infoHeight = infoHeight
        self.barHeight = barHeight
        self.keysHeight = keysHeight
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        SharedStore.noteKeyboard(hasFullAccess ? "3 sắp hiện (có truy cập đầy đủ)" : "3 sắp hiện (chưa có truy cập đầy đủ)")
        if hasFullAccess { SharedStore.keyboardHasFullAccess = true }
        targetCode = SharedStore.targetCode
        keyboard.hapticsEnabled = hasFullAccess && SharedStore.haptics
        keyboard.showsGlobe = needsInputModeSwitchKey
        resetComposing()
        matchAppearance()
        configureReturn()
        updateAutoCapitalization()
        mode = .normal
        info = .idle
        checkClipboard()
        if hasFullAccess, SharedStore.apiKey != nil { Translator.warmUp() }
        pollTimer?.invalidate()
        pollTimer = Timer.scheduledTimer(timeInterval: 1.0, target: self, selector: #selector(poll), userInfo: nil, repeats: true)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        SharedStore.noteKeyboard(hasFullAccess ? "4 đã hiện (có truy cập đầy đủ)" : "4 đã hiện (chưa có truy cập đầy đủ)")
        // iOS holds back touches near the screen edges in case they start a system swipe, so
        // q, a, p, l, Shift and delete answered later than the middle keys. A keyboard has no
        // edge swipes to wait for.
        var ancestor: UIView? = view
        while let current = ancestor {
            current.gestureRecognizers?.forEach { $0.delaysTouchesBegan = false }
            ancestor = current.superview
        }
        if SharedStore.defaults.bool(forKey: TypingBenchmark.flagKey) {
            SharedStore.defaults.removeObject(forKey: TypingBenchmark.flagKey)
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
                guard let self else { return }
                self.benchmark = TypingBenchmark(keyboard: self.keyboard) { [weak self] result in
                    SharedStore.defaults.set(result, forKey: TypingBenchmark.resultKey)
                    self?.benchmark = nil
                }
                self.benchmark?.start()
            }
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        pollTimer?.invalidate()
        pollTimer = nil
        task?.cancel()
        incomingTask?.cancel()
    }

    override func viewWillLayoutSubviews() {
        super.viewWillLayoutSubviews()
        let compact = traitCollection.verticalSizeClass == .compact
        // Only real changes: setting a constraint, even to its value, asks for another layout.
        for (constraint, value) in [(infoHeight, compact ? 26 : 30), (barHeight, compact ? 36 : 42), (keysHeight, compact ? 160 : 216)] {
            if let constraint, constraint.constant != CGFloat(value) { constraint.constant = CGFloat(value) }
        }
        keyboard.showsGlobe = needsInputModeSwitchKey
    }

    override func textDidChange(_ textInput: UITextInput?) {
        super.textDidChange(textInput)
        matchAppearance()
        configureReturn()
        updateAutoCapitalization()
    }

    private func matchAppearance() {
        let style: UIUserInterfaceStyle = textDocumentProxy.keyboardAppearance == .dark ? .dark : .unspecified
        // Setting it re-resolves every colour on the keyboard; only when it really changes.
        if view.overrideUserInterfaceStyle != style { view.overrideUserInterfaceStyle = style }
    }

    @objc private func poll() {
        // Typing comes first: the clipboard waits until the keys have been quiet for a moment.
        guard CACurrentMediaTime() - lastKeyAt > 1.5 else { return }
        checkClipboard()
    }

    // MARK: Keys

    func keyboardLayoutView(_ view: KeyboardLayoutView, perform action: KeyAction) {
        lastKeyAt = CACurrentMediaTime()
        let proxy = textDocumentProxy
        switch action {
        case .letter(let c):
            typeLetter(c)
        case .text(let s):
            proxy.insertText(s)
            if s.allSatisfy(\.isLetter) {
                word = TelexWord(resuming: word.shown + s)
            } else {
                resetComposing()
            }
        case .delete(let word):
            if word { deleteWord() } else { deleteCharacter() }
        case .space:
            insertSpace()
        case .newline:
            proxy.insertText("\n")
            resetComposing()
        case .moveCursor(let offset):
            proxy.adjustTextPosition(byCharacterOffset: offset)
            resetComposing()
        }
        if case .space = action {} else { lastSpaceAt = 0 }
        edited()
    }

    func keyboardLayoutView(_ view: KeyboardLayoutView, globeTouchedWith event: UIEvent) {
        handleInputModeList(from: view, with: event)
    }

    // MARK: Typing

    private func typeLetter(_ c: Character) {
        // Pick up the word at the cursor if it was changed outside this keyboard.
        let current = trailingWord(textDocumentProxy.documentContextBeforeInput ?? "")
        if current != word.shown { word = TelexWord(resuming: current) }
        let before = word.shown
        word.type(c)
        replaceWord(before, with: word.shown)
    }

    /// Changes only the part of the word that differs, so the field never flickers.
    private func replaceWord(_ old: String, with new: String) {
        let a = Array(old), b = Array(new)
        var same = 0
        while same < a.count, same < b.count, a[same] == b[same] { same += 1 }
        for _ in same..<a.count { textDocumentProxy.deleteBackward() }
        if same < b.count { textDocumentProxy.insertText(String(b[same...])) }
    }

    private func trailingWord(_ text: String) -> String {
        String(text.reversed().prefix { $0.isLetter }.reversed())
    }

    private func deleteCharacter() {
        textDocumentProxy.deleteBackward()
        if !word.shown.isEmpty { word = TelexWord(resuming: String(word.shown.dropLast())) }
    }

    private func deleteWord() {
        let before = Array(textDocumentProxy.documentContextBeforeInput ?? "")
        var i = before.count
        while i > 0, before[i - 1].isWhitespace { i -= 1 }
        while i > 0, !before[i - 1].isWhitespace { i -= 1 }
        for _ in 0..<max(1, before.count - i) { textDocumentProxy.deleteBackward() }
        resetComposing()
    }

    /// Two spaces after a word make ". ", as on the system keyboard.
    private func insertSpace() {
        let proxy = textDocumentProxy
        let before = proxy.documentContextBeforeInput ?? ""
        let now = CACurrentMediaTime()
        if now - lastSpaceAt < 1.2, before.hasSuffix(" "),
           let previous = before.dropLast().last, previous.isLetter || previous.isNumber {
            proxy.deleteBackward()
            proxy.insertText(". ")
            lastSpaceAt = 0
        } else {
            proxy.insertText(" ")
            lastSpaceAt = now
        }
        resetComposing()
        keyboard.showLetters()
    }

    private func resetComposing() {
        word = TelexWord()
    }

    private func edited() {
        switch mode {
        case .done, .failed: mode = .normal
        default: break
        }
        switch info {
        case .outgoing, .message: info = .idle
        default: break
        }
        updateAutoCapitalization()
        configureReturn()
    }

    private func updateAutoCapitalization() {
        let proxy = textDocumentProxy
        let before = proxy.documentContextBeforeInput ?? ""
        let on: Bool
        switch proxy.autocapitalizationType ?? .sentences {
        case .allCharacters:
            on = true
        case .words:
            on = before.isEmpty || before.last?.isWhitespace == true
        case .sentences:
            if before.isEmpty || before.last == "\n" {
                on = true
            } else if before.last == " ", let last = before.last(where: { !$0.isWhitespace }) {
                on = ".!?".contains(last)
            } else {
                on = false
            }
        default:
            on = false
        }
        keyboard.setAutoShift(on)
    }

    private func configureReturn() {
        let proxy = textDocumentProxy
        let type = proxy.returnKeyType ?? .default
        let title: String
        switch type {
        case .go: title = "Đi"
        case .google, .yahoo, .search: title = "Tìm kiếm"
        case .join: title = "Tham gia"
        case .next: title = "Tiếp"
        case .route: title = "Chỉ đường"
        case .send: title = "Gửi"
        case .done: title = "Xong"
        case .emergencyCall: title = "Khẩn cấp"
        case .continue: title = "Tiếp tục"
        default: title = "xuống dòng"
        }
        let enabled = !(proxy.enablesReturnKeyAutomatically ?? false) || proxy.hasText
        keyboard.configureReturn(title: title, prominent: type != .default, enabled: enabled)
    }

    // MARK: Info line

    /// The icon carries the colour (orange: partner, green: you, gold: check this, red: problem);
    /// the text stays in label colours so it reads on the keyboard background.
    private func renderInfo() {
        var c = UIButton.Configuration.plain()
        c.titleLineBreakMode = .byTruncatingTail
        c.imagePadding = 7
        c.contentInsets = NSDirectionalEdgeInsets(top: 0, leading: 10, bottom: 0, trailing: 10)
        c.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(pointSize: 13, weight: .semibold)
        let title: String
        let symbol: String
        let tint: UIColor
        var text = UIColor.label
        switch info {
        case .idle:
            let count = SharedStore.context().count
            title = count > 0 ? "Ngữ cảnh: \(count) tin gần đây" : "Chưa có ngữ cảnh"
            symbol = "eye"
            tint = .secondaryLabel
            text = .secondaryLabel
        case .copied:
            title = "Có tin vừa sao chép · chạm để dịch"
            symbol = "doc.on.clipboard.fill"
            tint = Brand.orange
        case .incomingBusy:
            title = "Đang dịch tin mới của đối tác…"
            symbol = "ellipsis.bubble.fill"
            tint = Brand.orange
            text = .secondaryLabel
        case .incoming(_, let translation):
            title = translation.text
            symbol = translation.note == nil ? "bubble.left.fill" : "exclamationmark.bubble.fill"
            tint = Brand.orange
        case .outgoing(let translation, _):
            if let note = translation.note {
                title = "Lưu ý: \(note)"
                symbol = "exclamationmark.triangle.fill"
                tint = Brand.gold
            } else if let meaning = translation.meaning {
                title = "Nghĩa: \(meaning)"
                symbol = "checkmark.circle.fill"
                tint = Brand.green
            } else {
                title = "Đã dịch sang \(translation.language.name)"
                symbol = "checkmark.circle.fill"
                tint = Brand.green
            }
        case .message(let message):
            title = message
            symbol = "exclamationmark.circle.fill"
            tint = .systemRed
        }
        c.title = title
        c.image = UIImage(systemName: symbol)
        c.baseForegroundColor = text
        c.imageColorTransformer = UIConfigurationColorTransformer { _ in tint }
        // VoiceOver hears whose message it is; sighted users get it from the icon and its colour.
        if case .incoming = info {
            infoButton.accessibilityLabel = "Tin đối tác, bằng tiếng Việt: \(title)"
        } else {
            infoButton.accessibilityLabel = title
        }
        c.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { attributes in
            var a = attributes
            a.font = .systemFont(ofSize: 15)
            return a
        }
        infoButton.configuration = c
        infoButton.accessibilityHint = "Chạm để xem chi tiết"
    }

    private func openInfo() {
        switch info {
        case .idle, .message:
            openContext()
        case .copied:
            translateCopied()
        case .incomingBusy:
            break
        case .incoming(let source, let translation):
            presentIncoming(source: source, translation: translation)
        case .outgoing(let translation, let original):
            var parts = [("Bạn viết", true), (original, false), ("Đã đổi sang \(translation.language.name)", true), (translation.text, false)]
            if let meaning = translation.meaning { parts += [("Nghĩa khi dịch ngược", true), (meaning, false)] }
            if let note = translation.note { parts += [("Lưu ý", true), (note, false)] }
            showPanel(parts, button: "Hoàn tác, lấy lại câu gốc") { [weak self] in self?.undo() }
            mode = .panel("Kiểm tra bản dịch")
        }
    }

    private func presentIncoming(source: String, translation: Translation) {
        var parts = [(source, true), ("→ " + translation.text, false)]
        if let note = translation.note { parts.append(("Lưu ý: " + note, true)) }
        showPanel(parts, button: "Sao chép bản dịch") { [weak self] in
            UIPasteboard.general.string = translation.text
            SharedStore.handledPasteboard = UIPasteboard.general.changeCount
            self?.panelButton.configuration?.title = "Đã sao chép"
        }
        mode = .panel("Tin đối tác → Tiếng Việt")
    }

    /// A long translation of the partner's message opens in full while nothing has been typed
    /// yet: reading comes before replying, and one tap closes it.
    private func expandIfLong(source: String, translation: Translation) {
        guard mode == .normal, !textDocumentProxy.hasText, !fitsInfoLine(translation.text) else { return }
        presentIncoming(source: source, translation: translation)
    }

    private func fitsInfoLine(_ text: String) -> Bool {
        guard !text.contains("\n") else { return false }
        let available = infoButton.bounds.width - 64
        guard available > 0 else { return true }
        return (text as NSString).size(withAttributes: [.font: UIFont.systemFont(ofSize: 15)]).width <= available
    }

    private func openContext() {
        let lines = SharedStore.context()
        let parts: [(String, Bool)] = lines.isEmpty
            ? [("Chưa có tin nào làm ngữ cảnh.", true)]
            : lines.map { (($0.side == .partner ? "Đối tác: " : "Tôi: ") + $0.text, $0.side == .me) }
        showPanel(parts, button: "Cuộc mới (xóa ngữ cảnh)") { [weak self] in
            SharedStore.clearContext()
            self?.mode = .normal
            self?.info = .idle
        }
        mode = .panel("Ngữ cảnh đang dùng")
    }

    // MARK: Translate bar

    private func renderBar() {
        bar.arrangedSubviews.forEach { $0.removeFromSuperview() }
        switch mode {
        case .normal:
            bar.addArrangedSubview(logoButton())
            let context = SharedStore.context()
            let target = resolvedTarget(context: context)
            let title = targetCode == Language.autoCode ? "Tự động · \(target.label)" : "Việt → \(target.label)"
            let language = pill(title, image: "chevron.down", trailingImage: true) { [weak self] in self?.mode = .picking }
            language.accessibilityLabel = "Ngôn ngữ dịch: \(target.name)"
            // On narrow iPhones this pill gives way (and truncates) before the Dịch button does.
            language.configuration?.titleLineBreakMode = .byTruncatingTail
            language.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
            bar.addArrangedSubview(language)
            bar.addArrangedSubview(spacer())
            copyButtonShown = !SharedStore.autoIncoming && copyPending
            if copyButtonShown {
                bar.addArrangedSubview(pill("Tin copy", style: .incoming) { [weak self] in self?.translateCopied() })
            }
            bar.addArrangedSubview(pill("Dịch", image: "translate", style: .primary) { [weak self] in self?.translateDraft() })
        case .picking:
            bar.addArrangedSubview(languageChips())
            bar.addArrangedSubview(pill("Xong") { [weak self] in self?.mode = .normal })
        case .busy:
            bar.addArrangedSubview(status("Đang dịch sang \(activeTarget?.name ?? "ngôn ngữ đích")…", spinner: true))
            bar.addArrangedSubview(pill("Hủy") { [weak self] in
                self?.task?.cancel()
                self?.mode = .normal
            })
        case .done:
            bar.addArrangedSubview(status("Đã dịch sang \(activeTarget?.name ?? "ngôn ngữ đích")", symbol: "checkmark.circle.fill"))
            bar.addArrangedSubview(pill("Hoàn tác") { [weak self] in self?.undo() })
        case .failed(let message):
            bar.addArrangedSubview(status(message, symbol: "exclamationmark.circle.fill", tint: .systemRed))
            bar.addArrangedSubview(pill("Đóng") { [weak self] in self?.mode = .normal })
        case .panel(let title):
            bar.addArrangedSubview(status(title, color: .secondaryLabel))
            bar.addArrangedSubview(pill("Xong") { [weak self] in self?.mode = .normal })
        }
        let panelVisible: Bool
        if case .panel = mode { panelVisible = true } else { panelVisible = false }
        panel.isHidden = !panelVisible
        keyboard.isHidden = panelVisible
    }

    private func languageChips() -> UIView {
        let scroll = UIScrollView()
        scroll.showsHorizontalScrollIndicator = false
        let stack = UIStackView()
        stack.spacing = 6
        stack.alignment = .center
        stack.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor),
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
            stack.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor),
            scroll.heightAnchor.constraint(equalToConstant: 36),
        ])
        let options = [(Language.autoCode, "Tự động")] + Language.all.map { ($0.code, $0.label) }
        for (code, label) in options {
            let chip = pill(label, style: code == targetCode ? .primary : .plain) { [weak self] in
                self?.targetCode = code
                SharedStore.targetCode = code
                self?.mode = .normal
            }
            if code == targetCode { chip.accessibilityTraits.insert(.selected) }
            stack.addArrangedSubview(chip)
        }
        scroll.setContentHuggingPriority(.defaultLow, for: .horizontal)
        scroll.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return scroll
    }

    private enum PillStyle { case plain, primary, incoming }

    private func pill(_ title: String, image: String? = nil, trailingImage: Bool = false,
                      style: PillStyle = .plain, action: @escaping () -> Void) -> UIButton {
        var c: UIButton.Configuration
        switch style {
        case .plain:
            c = .gray()
            c.baseForegroundColor = .label
        case .primary:
            c = .filled()
            c.baseBackgroundColor = Brand.greenFill
            c.baseForegroundColor = .white
        case .incoming:
            c = .tinted()
            c.baseBackgroundColor = Brand.orange
            c.baseForegroundColor = Brand.orange
        }
        c.title = title
        c.cornerStyle = .capsule
        c.contentInsets = NSDirectionalEdgeInsets(top: 7, leading: 12, bottom: 7, trailing: 12)
        c.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { attributes in
            var a = attributes
            a.font = .systemFont(ofSize: 15, weight: .semibold)
            return a
        }
        if let image {
            c.image = UIImage(systemName: image)
            c.imagePadding = 4
            c.imagePlacement = trailingImage ? .trailing : .leading
            c.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(pointSize: 13, weight: .semibold)
        }
        let b = UIButton(configuration: c, primaryAction: UIAction { _ in action() })
        b.setContentHuggingPriority(.required, for: .horizontal)
        b.setContentCompressionResistancePriority(.required, for: .horizontal)
        return b
    }

    private func status(_ text: String, color: UIColor = .label, spinner: Bool = false,
                        symbol: String? = nil, tint: UIColor = Brand.green) -> UIView {
        let label = UILabel()
        label.text = text
        label.font = .systemFont(ofSize: 15)
        label.textColor = color
        label.adjustsFontSizeToFitWidth = true
        label.minimumScaleFactor = 0.75
        let stack = UIStackView(arrangedSubviews: [label])
        stack.spacing = 6
        stack.alignment = .center
        if spinner {
            let indicator = UIActivityIndicatorView(style: .medium)
            indicator.startAnimating()
            stack.insertArrangedSubview(indicator, at: 0)
        }
        if let symbol {
            let icon = UIImageView(image: UIImage(systemName: symbol))
            icon.tintColor = tint
            icon.setContentHuggingPriority(.required, for: .horizontal)
            icon.setContentCompressionResistancePriority(.required, for: .horizontal)
            stack.insertArrangedSubview(icon, at: 0)
        }
        stack.setContentHuggingPriority(.defaultLow, for: .horizontal)
        stack.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return stack
    }

    /// The Mộc Sinh mark: shows which keyboard is up, and opens the context in use.
    private func logoButton() -> UIButton {
        let button = UIButton(configuration: .plain(), primaryAction: UIAction { [weak self] _ in self?.openContext() })
        let mark = UIImageView(image: UIImage(named: "Logo"))
        mark.contentMode = .scaleAspectFit
        mark.isUserInteractionEnabled = false
        mark.translatesAutoresizingMaskIntoConstraints = false
        button.addSubview(mark)
        NSLayoutConstraint.activate([
            button.widthAnchor.constraint(equalToConstant: 36),
            button.heightAnchor.constraint(equalToConstant: 36),
            mark.centerXAnchor.constraint(equalTo: button.centerXAnchor),
            mark.centerYAnchor.constraint(equalTo: button.centerYAnchor),
            mark.widthAnchor.constraint(equalToConstant: 24),
            mark.heightAnchor.constraint(equalToConstant: 24),
        ])
        button.accessibilityLabel = Brand.name
        button.accessibilityHint = "Xem ngữ cảnh đang dùng"
        button.setContentHuggingPriority(.required, for: .horizontal)
        return button
    }

    private func spacer() -> UIView {
        let v = UIView()
        v.setContentHuggingPriority(.defaultLow, for: .horizontal)
        return v
    }

    // MARK: Panel

    private func buildPanel() {
        panel.axis = .vertical
        panel.spacing = 8
        panel.isLayoutMarginsRelativeArrangement = true
        panel.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 4, leading: 6, bottom: 8, trailing: 6)
        panel.backgroundColor = KeyColor.popup
        panel.layer.cornerRadius = 12
        panel.layer.cornerCurve = .continuous
        panel.isHidden = true

        panelText.isEditable = false
        panelText.backgroundColor = .clear

        var c = UIButton.Configuration.filled()
        c.baseBackgroundColor = Brand.greenFill
        c.baseForegroundColor = .white
        c.cornerStyle = .medium
        panelButton.configuration = c
        panelButton.addAction(UIAction { [weak self] _ in self?.panelAction?() }, for: .touchUpInside)
        panelButton.heightAnchor.constraint(equalToConstant: 44).isActive = true

        panel.addArrangedSubview(panelText)
        panel.addArrangedSubview(panelButton)
    }

    /// Each part is (text, isSecondary): secondary parts are small grey captions.
    private func showPanel(_ parts: [(String, Bool)], button: String, action: @escaping () -> Void) {
        let text = NSMutableAttributedString()
        for (i, part) in parts.enumerated() {
            let attributes: [NSAttributedString.Key: Any] = part.1
                ? [.font: UIFont.systemFont(ofSize: 13, weight: .medium), .foregroundColor: UIColor.secondaryLabel]
                : [.font: UIFont.systemFont(ofSize: 17), .foregroundColor: UIColor.label]
            text.append(NSAttributedString(string: (i == 0 ? "" : "\n") + part.0, attributes: attributes))
        }
        panelText.attributedText = text
        panelText.setContentOffset(.zero, animated: false)
        panelButton.configuration?.title = button
        panelAction = action
    }

    // MARK: Translating the user's message

    private func readyKey() -> String? {
        guard hasFullAccess else {
            mode = .failed(TranslateError.noFullAccess.errorDescription ?? "")
            return nil
        }
        guard let key = SharedStore.apiKey else {
            offerKeyPaste()
            return nil
        }
        return key
    }

    /// The keyboard reads the key the app saved only when both share an App Group, which some
    /// installers leave out. Then the key can be pasted here instead; it stays with the keyboard.
    private func offerKeyPaste() {
        showPanel([("Bàn phím chưa có API key OpenAI.", true),
                   ("Sao chép API key (bắt đầu bằng sk-) rồi bấm nút bên dưới.", false),
                   ("Đã dán key trong app MSTrans mà vẫn thấy dòng này: app và bàn phím chưa dùng chung dữ liệu (do cách cài). Dán ở đây là đủ.", true)],
                  button: "Dán API key") { [weak self] in self?.pasteKey() }
        mode = .panel("Cần API key")
    }

    /// Off the main thread: reading the clipboard can wait on iOS's paste prompt.
    private func pasteKey() {
        panelButton.configuration?.title = "Đang đọc…"
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let copied = UIPasteboard.general.string?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let changeCount = UIPasteboard.general.changeCount
            DispatchQueue.main.async {
                guard let self else { return }
                guard copied.hasPrefix("sk-"), copied.count > 20, !copied.contains(where: \.isWhitespace) else {
                    self.panelButton.configuration?.title = copied.isEmpty ? "Chưa đọc được, thử lại" : "Không phải API key, thử lại"
                    return
                }
                SharedStore.apiKey = copied
                SharedStore.handledPasteboard = changeCount
                self.showPanel([("Đã lưu API key cho bàn phím.", true), ("Bấm Dịch để dịch câu bạn đang viết.", false)],
                               button: "Xong") { [weak self] in self?.mode = .normal }
            }
        }
    }

    /// "Tự động" answers in the partner's language; otherwise the chosen one.
    private func resolvedTarget(context: [ChatLine]) -> Language {
        if targetCode == Language.autoCode {
            if let detected = LanguageDetector.partnerLanguage(in: context) {
                if Language.named(detected.code) != nil { SharedStore.lastPartnerCode = detected.code }
                return detected
            }
            return Language.named(SharedStore.lastPartnerCode) ?? Language.all[0]
        }
        return Language.named(targetCode) ?? Language.all[0]
    }

    private func translateDraft() {
        guard let apiKey = readyKey() else { return }
        let context = SharedStore.context()
        let target = resolvedTarget(context: context)
        activeTarget = target
        mode = .busy
        task?.cancel()
        readDocument { [weak self] text in
            guard let self, self.mode == .busy, self.view.window != nil else { return }
            guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                self.mode = .failed(TranslateError.empty.errorDescription ?? "")
                return
            }
            self.task = Task { [weak self] in
                do {
                    let result = try await Translator(apiKey: apiKey)
                        .translate(text, to: target, context: context, checkMeaning: SharedStore.checkMeaning)
                    guard let self, !Task.isCancelled else { return }
                    self.original = text
                    self.replaceDocument(with: result.text) { [weak self] in
                        SharedStore.remember(ChatLine(side: .me, text: result.text, date: Date()))
                        SharedStore.addHistory(HistoryItem(side: .me, source: text, translation: result.text, languageCode: target.code,
                                                           meaning: result.meaning, note: result.note))
                        self?.mode = .done
                        self?.info = .outgoing(result, original: text)
                    }
                } catch is CancellationError {
                } catch {
                    self?.mode = .failed(error.localizedDescription)
                }
            }
        }
    }

    private func undo() {
        replaceDocument(with: original) { [weak self] in
            self?.mode = .normal
            self?.info = .idle
        }
    }

    // MARK: The whole text field

    /// iOS hands a keyboard only the text near the cursor, usually the current paragraph, and
    /// reports a cursor move only after the current turn of the run loop. So the cursor walks to
    /// the end of the field, back over it a paragraph at a time, and to the end again.
    private func readDocument(_ completion: @escaping (String) -> Void) {
        let proxy = textDocumentProxy
        var collected = ""
        var steps = 0

        func back() {
            steps += 1
            let before = proxy.documentContextBeforeInput ?? ""
            if !before.isEmpty, steps < 80 {
                collected = before + collected
                proxy.adjustTextPosition(byCharacterOffset: -before.utf16.count)
                return tick(back)
            }
            // Nothing before the cursor means the start of the field, or of a paragraph: step
            // over one character and see whether the field moved.
            let after = proxy.documentContextAfterInput ?? ""
            proxy.adjustTextPosition(byCharacterOffset: -1)
            tick {
                let moved = !(proxy.documentContextBeforeInput ?? "").isEmpty || (proxy.documentContextAfterInput ?? "") != after
                if moved, steps < 80 {
                    collected = "\n" + collected
                    return back()
                }
                proxy.adjustTextPosition(byCharacterOffset: collected.utf16.count)
                self.tick { completion(collected) }
            }
        }

        moveToEnd {
            steps = 0
            back()
        }
    }

    private func moveToEnd(_ completion: @escaping () -> Void) {
        let proxy = textDocumentProxy
        var steps = 0
        func forward() {
            steps += 1
            let after = proxy.documentContextAfterInput ?? ""
            if !after.isEmpty, steps < 80 {
                proxy.adjustTextPosition(byCharacterOffset: after.utf16.count)
                return tick(forward)
            }
            let before = proxy.documentContextBeforeInput ?? ""
            proxy.adjustTextPosition(byCharacterOffset: 1)
            tick {
                let moved = (proxy.documentContextBeforeInput ?? "") != before || (proxy.documentContextAfterInput ?? "") != after
                if moved, steps < 80 { return forward() }
                completion()
            }
        }
        forward()
    }

    /// Replaces the whole field: from the end, deletes a paragraph at a time until the field
    /// says it is empty, then inserts.
    private func replaceDocument(with text: String, completion: @escaping () -> Void) {
        let proxy = textDocumentProxy
        var steps = 0
        var emptyRounds = 0
        func clear() {
            steps += 1
            let before = proxy.documentContextBeforeInput ?? ""
            let after = proxy.documentContextAfterInput ?? ""
            emptyRounds = before.isEmpty && after.isEmpty ? emptyRounds + 1 : 0
            guard proxy.hasText, emptyRounds < 3, steps < 80 else {
                proxy.insertText(text)
                resetComposing()
                return completion()
            }
            for _ in 0..<max(1, before.count) { proxy.deleteBackward() }
            tick(clear)
        }
        moveToEnd(clear)
    }

    private func tick(_ work: @escaping () -> Void) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.025, execute: work)
    }

    // MARK: Translating the partner's messages

    /// Whether the clipboard holds text not translated yet. The change count and `hasStrings`
    /// never ask for paste permission (only reading the text does), but each is a round trip to
    /// the clipboard's process, so they run on `clipboardQueue` and the answer comes back here.
    private func checkClipboard() {
        guard hasFullAccess, SharedStore.apiKey != nil, !clipboardCheckRunning else { return }
        clipboardCheckRunning = true
        let handled = SharedStore.handledPasteboard
        clipboardQueue.async { [weak self] in
            let board = UIPasteboard.general
            let changeCount = board.changeCount
            let pending = changeCount != handled && board.hasStrings
            DispatchQueue.main.async {
                guard let self else { return }
                self.clipboardCheckRunning = false
                self.copyPending = pending
                self.copyChangeCount = changeCount
                self.refreshIncoming()
            }
        }
    }

    /// Offers to translate a fresh copy, or in instant mode translates it. Reading the clipboard
    /// makes iOS ask for paste permission and blocks the reader until it is answered, so the
    /// text is only ever read off the main thread.
    private func refreshIncoming() {
        guard hasFullAccess, SharedStore.apiKey != nil else { return }
        guard SharedStore.autoIncoming else {
            // The "Tin copy" button on the bar follows the clipboard.
            if mode == .normal, copyPending != copyButtonShown { renderBar() }
            return
        }
        // Offered whenever the line is free, so pressing Dịch first or a denied paste doesn't lose it.
        switch info {
        case .idle where copyPending:
            // Instant mode reads each copy once by itself; after that the offer stays for a tap.
            if SharedStore.instantIncoming, attemptedPasteboard != copyChangeCount {
                attemptedPasteboard = copyChangeCount
                translateCopied()
            } else {
                info = .copied
            }
        case .copied where !copyPending: info = .idle
        default: break
        }
    }

    /// Translates the copied message, reading it off the main thread so the keys stay
    /// responsive while iOS asks for paste permission.
    private func translateCopied() {
        guard readyKey() != nil else { return }
        mode = .normal
        info = .incomingBusy
        // Its own queue: the read can wait for the paste prompt, and clipboard checks must not.
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let changeCount = UIPasteboard.general.changeCount
            let copied = UIPasteboard.general.string?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            DispatchQueue.main.async { self?.received(copied: copied, changeCount: changeCount) }
        }
    }

    private func received(copied: String, changeCount: Int) {
        // Nothing read usually means paste was not allowed; keep offering it so it can be retried.
        guard !copied.isEmpty else {
            info = .message("Chưa đọc được tin đã sao chép. Khi iOS hỏi, chọn “Cho phép dán”.")
            return
        }
        SharedStore.handledPasteboard = changeCount
        copyPending = false
        guard !LanguageDetector.isVietnamese(copied) else {
            info = .message("Tin vừa sao chép đã là tiếng Việt.")
            return
        }
        SharedStore.remember(ChatLine(side: .partner, text: copied, date: Date()))
        translateIncoming(copied)
    }

    private func translateIncoming(_ text: String) {
        if let cached = SharedStore.cachedIncoming(text) {
            let translation = Translation(text: cached, meaning: nil, note: nil, language: .vietnamese)
            info = .incoming(source: text, translation: translation)
            expandIfLong(source: text, translation: translation)
            return
        }
        guard let apiKey = SharedStore.apiKey else { return }
        let context = SharedStore.context()
        info = .incomingBusy
        incomingTask?.cancel()
        incomingTask = Task { [weak self] in
            do {
                let result = try await Translator(apiKey: apiKey).translate(text, to: .vietnamese, context: context)
                SharedStore.cacheIncoming(text, translation: result.text)
                SharedStore.addHistory(HistoryItem(side: .partner, source: text, translation: result.text,
                                                   languageCode: LanguageDetector.language(of: text)?.code ?? "",
                                                   note: result.note))
                guard let self, !Task.isCancelled else { return }
                self.info = .incoming(source: text, translation: result)
                self.expandIfLong(source: text, translation: result)
            } catch is CancellationError {
            } catch {
                self?.info = .message(error.localizedDescription)
            }
        }
    }
}

/// Lets keys play the standard keyboard click.
final class KeyboardInputView: UIInputView, UIInputViewAudioFeedback {
    var enableInputClicksWhenVisible: Bool { true }
}
