import UIKit

/// The translation keyboard: an info line, a translate bar and an iOS-style Telex keyboard.
///
/// - Info line: the Vietnamese translation of the partner's newest message (read from the
///   screen or from a fresh copy), or the meaning of what was just sent.
/// - Translate bar: target language ("Tự động" follows the partner), "Dịch", undo.
/// - Keys: `KeyboardLayoutView`.
final class KeyboardViewController: UIInputViewController, KeyboardLayoutViewDelegate {

    private enum Mode: Equatable {
        case normal, picking, busy, done, failed(String), panel(String)
    }

    private enum Info {
        case idle
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
    private var lastIncoming = ""
    private var lastPasteboardCheck = -1

    // Telex: the word at the cursor while it is being typed.
    private var word = TelexWord()
    private var lastSpaceAt: CFTimeInterval = 0

    // MARK: Lifecycle

    override func loadView() {
        let input = KeyboardInputView(frame: .zero, inputViewStyle: .keyboard)
        input.allowsSelfSizing = true
        view = input
    }

    override func viewDidLoad() {
        super.viewDidLoad()

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
        if hasFullAccess {
            SharedStore.keyboardHasFullAccess = true
            SharedStore.keyboardVisible = true
        }
        targetCode = SharedStore.targetCode
        keyboard.hapticsEnabled = hasFullAccess && SharedStore.haptics
        keyboard.showsGlobe = needsInputModeSwitchKey
        resetComposing()
        matchAppearance()
        configureReturn()
        updateAutoCapitalization()
        mode = .normal
        info = .idle
        refreshIncoming()
        pollTimer?.invalidate()
        pollTimer = Timer.scheduledTimer(timeInterval: 1.5, target: self, selector: #selector(poll), userInfo: nil, repeats: true)
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if hasFullAccess { SharedStore.keyboardVisible = false }
        pollTimer?.invalidate()
        pollTimer = nil
        task?.cancel()
        incomingTask?.cancel()
    }

    override func viewWillLayoutSubviews() {
        super.viewWillLayoutSubviews()
        let compact = traitCollection.verticalSizeClass == .compact
        infoHeight?.constant = compact ? 26 : 30
        barHeight?.constant = compact ? 36 : 42
        keysHeight?.constant = compact ? 160 : 216
        keyboard.showsGlobe = needsInputModeSwitchKey
    }

    override func textDidChange(_ textInput: UITextInput?) {
        super.textDidChange(textInput)
        matchAppearance()
        configureReturn()
        updateAutoCapitalization()
    }

    private func matchAppearance() {
        view.overrideUserInterfaceStyle = textDocumentProxy.keyboardAppearance == .dark ? .dark : .unspecified
    }

    @objc private func poll() {
        refreshIncoming()
    }

    // MARK: Keys

    func keyboardLayoutView(_ view: KeyboardLayoutView, perform action: KeyAction) {
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
            if before.isEmpty || before.hasSuffix("\n") {
                on = true
            } else if before.hasSuffix(" "), let last = before.trimmingCharacters(in: .whitespaces).last {
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

    private func renderInfo() {
        var c = UIButton.Configuration.plain()
        c.titleLineBreakMode = .byTruncatingTail
        c.imagePadding = 6
        c.contentInsets = NSDirectionalEdgeInsets(top: 0, leading: 8, bottom: 0, trailing: 8)
        c.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(pointSize: 13, weight: .semibold)
        let title: String
        let symbol: String
        let color: UIColor
        switch info {
        case .idle:
            let count = SharedStore.context().count
            let screen = SharedStore.screenUpdatedAt != nil
            title = screen ? "Đang đọc màn hình · \(count) tin ngữ cảnh"
                : (count > 0 ? "Ngữ cảnh: \(count) tin gần đây" : "Chưa có ngữ cảnh")
            symbol = screen ? "eye.fill" : "eye"
            color = .secondaryLabel
        case .incomingBusy:
            title = "Đang dịch tin mới của đối tác…"
            symbol = "ellipsis.bubble"
            color = .secondaryLabel
        case .incoming(_, let translation):
            title = translation.text
            symbol = translation.note == nil ? "bubble.left.fill" : "exclamationmark.bubble.fill"
            color = Palette.incoming
        case .outgoing(let translation, _):
            if let note = translation.note {
                title = "Lưu ý: \(note)"
                symbol = "exclamationmark.triangle.fill"
                color = .systemOrange
            } else if let meaning = translation.meaning {
                title = "Nghĩa: \(meaning)"
                symbol = "checkmark.circle.fill"
                color = Palette.ok
            } else {
                title = "Đã dịch sang \(translation.language.name)"
                symbol = "checkmark.circle.fill"
                color = Palette.ok
            }
        case .message(let text):
            title = text
            symbol = "exclamationmark.circle"
            color = .systemRed
        }
        c.title = title
        c.image = UIImage(systemName: symbol)
        c.baseForegroundColor = color
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
        case .incomingBusy:
            break
        case .incoming(let source, let translation):
            var parts = [(source, true), ("→ " + translation.text, false)]
            if let note = translation.note { parts.append(("Lưu ý: " + note, true)) }
            showPanel(parts, button: "Sao chép bản dịch") { [weak self] in
                UIPasteboard.general.string = translation.text
                SharedStore.handledPasteboard = UIPasteboard.general.changeCount
                self?.panelButton.configuration?.title = "Đã sao chép"
            }
            mode = .panel("Tin đối tác → Tiếng Việt")
        case .outgoing(let translation, let original):
            var parts = [("Bạn viết", true), (original, false), ("Đã đổi sang \(translation.language.name)", true), (translation.text, false)]
            if let meaning = translation.meaning { parts += [("Nghĩa khi dịch ngược", true), (meaning, false)] }
            if let note = translation.note { parts += [("Lưu ý", true), (note, false)] }
            showPanel(parts, button: "Hoàn tác, lấy lại câu gốc") { [weak self] in self?.undo() }
            mode = .panel("Kiểm tra bản dịch")
        }
    }

    private func openContext() {
        let lines = SharedStore.context()
        let parts: [(String, Bool)] = lines.isEmpty
            ? [("Chưa có tin nào làm ngữ cảnh.", true)]
            : lines.map { (($0.side == .partner ? "Đối tác: " : "Tôi: ") + $0.text, $0.side == .me) }
        showPanel(parts, button: "Cuộc mới (xóa ngữ cảnh)") { [weak self] in
            SharedStore.clearContext()
            self?.lastIncoming = ""
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
            let context = SharedStore.context()
            let target = resolvedTarget(context: context)
            let title = targetCode == Language.autoCode ? "Tự động · \(target.label)" : "Việt → \(target.label)"
            let language = pill(title, image: "chevron.down", trailingImage: true) { [weak self] in self?.mode = .picking }
            language.accessibilityLabel = "Ngôn ngữ dịch: \(target.name)"
            bar.addArrangedSubview(language)
            bar.addArrangedSubview(spacer())
            if !SharedStore.autoIncoming && hasNewCopy {
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
            bar.addArrangedSubview(status("Đã dịch sang \(activeTarget?.name ?? "ngôn ngữ đích")", check: true))
            bar.addArrangedSubview(pill("Hoàn tác") { [weak self] in self?.undo() })
        case .failed(let message):
            bar.addArrangedSubview(status(message, color: .systemRed))
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
            stack.addArrangedSubview(pill(label, style: code == targetCode ? .primary : .plain) { [weak self] in
                self?.targetCode = code
                SharedStore.targetCode = code
                self?.mode = .normal
            })
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
            c.baseBackgroundColor = Palette.accent
            c.baseForegroundColor = .white
        case .incoming:
            c = .tinted()
            c.baseBackgroundColor = Palette.incoming
            c.baseForegroundColor = Palette.incoming
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

    private func status(_ text: String, color: UIColor = .label, spinner: Bool = false, check: Bool = false) -> UIView {
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
        if check {
            let icon = UIImageView(image: UIImage(systemName: "checkmark.circle.fill"))
            icon.tintColor = Palette.ok
            stack.insertArrangedSubview(icon, at: 0)
        }
        stack.setContentHuggingPriority(.defaultLow, for: .horizontal)
        stack.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return stack
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
        c.baseBackgroundColor = Palette.accent
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
            mode = .failed(TranslateError.noKey.errorDescription ?? "")
            return nil
        }
        return key
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
        let proxy = textDocumentProxy
        let text = (proxy.documentContextBeforeInput ?? "") + (proxy.selectedText ?? "") + (proxy.documentContextAfterInput ?? "")
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            mode = .failed(TranslateError.empty.errorDescription ?? "")
            return
        }
        let context = SharedStore.context()
        let target = resolvedTarget(context: context)
        activeTarget = target
        mode = .busy
        task?.cancel()
        task = Task { [weak self] in
            do {
                let result = try await Translator(apiKey: apiKey)
                    .translate(text, to: target, context: context, checkMeaning: SharedStore.checkMeaning)
                guard let self, !Task.isCancelled else { return }
                self.original = text
                self.replaceAll(with: result.text)
                SharedStore.remember(ChatLine(side: .me, text: result.text, date: Date()))
                self.mode = .done
                self.info = .outgoing(result, original: text)
            } catch is CancellationError {
            } catch {
                self?.mode = .failed(error.localizedDescription)
            }
        }
    }

    /// Replaces everything in the text field.
    private func replaceAll(with text: String) {
        let proxy = textDocumentProxy
        if let selected = proxy.selectedText, !selected.isEmpty { proxy.deleteBackward() }
        let before = proxy.documentContextBeforeInput ?? ""
        let after = proxy.documentContextAfterInput ?? ""
        if !after.isEmpty { proxy.adjustTextPosition(byCharacterOffset: after.utf16.count) }
        for _ in 0..<(before.count + after.count) { proxy.deleteBackward() }
        proxy.insertText(text)
        resetComposing()
    }

    private func undo() {
        replaceAll(with: original)
        mode = .normal
        info = .idle
    }

    // MARK: Translating the partner's messages

    private var hasNewCopy: Bool {
        hasFullAccess && UIPasteboard.general.hasStrings
            && UIPasteboard.general.changeCount != SharedStore.handledPasteboard
    }

    /// Translates the partner's newest message as soon as it appears: a fresh copy first,
    /// then the screen. Each message is translated once.
    private func refreshIncoming() {
        guard SharedStore.autoIncoming, hasFullAccess, SharedStore.apiKey != nil else { return }
        var source: String?
        let changeCount = UIPasteboard.general.changeCount
        if changeCount != lastPasteboardCheck {
            lastPasteboardCheck = changeCount
            if hasNewCopy {
                SharedStore.handledPasteboard = changeCount
                if let copied = UIPasteboard.general.string?.trimmingCharacters(in: .whitespacesAndNewlines),
                   !copied.isEmpty, !LanguageDetector.isVietnamese(copied) {
                    SharedStore.remember(ChatLine(side: .partner, text: copied, date: Date()))
                    source = copied
                }
            }
        }
        if source == nil, let line = SharedStore.screenLines().last(where: { $0.side == .partner }),
           !LanguageDetector.isVietnamese(line.text) {
            source = line.text
        }
        guard let text = source, !similar(text, lastIncoming) else { return }
        lastIncoming = text
        translateIncoming(text)
    }

    private func translateCopied() {
        guard readyKey() != nil else { return }
        SharedStore.handledPasteboard = UIPasteboard.general.changeCount
        guard let copied = UIPasteboard.general.string?.trimmingCharacters(in: .whitespacesAndNewlines), !copied.isEmpty else {
            mode = .failed("Chưa có tin nào được sao chép.")
            return
        }
        SharedStore.remember(ChatLine(side: .partner, text: copied, date: Date()))
        lastIncoming = copied
        translateIncoming(copied)
        mode = .normal
    }

    private func translateIncoming(_ text: String) {
        if let cached = SharedStore.cachedIncoming(text) {
            info = .incoming(source: text, translation: Translation(text: cached, meaning: nil, note: nil, language: .vietnamese))
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
                guard let self, !Task.isCancelled else { return }
                self.info = .incoming(source: text, translation: result)
            } catch is CancellationError {
            } catch {
                self?.info = .message(error.localizedDescription)
            }
        }
    }

    /// Screen reading returns slightly different text from frame to frame; treat near-equal as equal.
    private func similar(_ a: String, _ b: String) -> Bool {
        let x = Array(a.lowercased().filter { !$0.isWhitespace })
        let y = Array(b.lowercased().filter { !$0.isWhitespace })
        if x == y { return true }
        guard !x.isEmpty, !y.isEmpty else { return false }
        var counts: [Character: Int] = [:]
        for c in x { counts[c, default: 0] += 1 }
        var common = 0
        for c in y where counts[c, default: 0] > 0 {
            counts[c, default: 0] -= 1
            common += 1
        }
        return Double(common) / Double(max(x.count, y.count)) >= 0.85
    }
}

/// Lets keys play the standard keyboard click.
final class KeyboardInputView: UIInputView, UIInputViewAudioFeedback {
    var enableInputClicksWhenVisible: Bool { true }
}

enum Palette {
    static let accent = UIColor {
        $0.userInterfaceStyle == .dark ? UIColor(red: 0.18, green: 0.37, blue: 0.88, alpha: 1) : UIColor(red: 0.11, green: 0.31, blue: 0.85, alpha: 1)
    }
    static let incoming = UIColor {
        $0.userInterfaceStyle == .dark ? UIColor(red: 0.94, green: 0.63, blue: 0.44, alpha: 1) : UIColor(red: 0.60, green: 0.20, blue: 0.07, alpha: 1)
    }
    static let ok = UIColor {
        $0.userInterfaceStyle == .dark ? UIColor(red: 0.20, green: 0.83, blue: 0.60, alpha: 1) : UIColor(red: 0.06, green: 0.46, blue: 0.43, alpha: 1)
    }
}
