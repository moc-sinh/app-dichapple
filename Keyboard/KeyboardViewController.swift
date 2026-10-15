import UIKit

/// The translation keyboard: an info line, a translate bar and an iOS-style Telex keyboard.
///
/// - Info line: the Vietnamese translation of the partner's message the user just copied,
///   or the meaning of what was just sent.
/// - Translate bar: the Mộc Sinh mark (opens everything at once: the partner's last message, the
///   check of what was sent, the context), the target language ("Tự động" follows the partner),
///   "Dịch", undo.
/// - Keys: `KeyboardLayoutView`; typing goes through `Composer`.
final class KeyboardViewController: UIInputViewController, KeyboardLayoutViewDelegate {

    private enum Mode: Equatable {
        case normal, picking, busy, done, failed(String), panel(String)
    }

    private enum Info {
        case idle
        /// Something new was copied; reading it waits for a tap (see `checkPasteboard`).
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
    /// Runs when "Xong" closes the panel.
    private var panelDone: (() -> Void)?

    private var infoHeight: NSLayoutConstraint?
    private var barHeight: NSLayoutConstraint?
    private var keysHeight: NSLayoutConstraint?

    private lazy var targetCode = SharedStore.targetCode
    private var activeTarget: Language?
    private var original = ""
    /// The latest translations both ways, for the check of what was sent and the overview.
    private var lastOutgoing: (translation: Translation, original: String)?
    private var lastIncoming: (source: String, translation: Translation)?
    private var task: Task<Void, Never>?
    private var incomingTask: Task<Void, Never>?
    private var pollTimer: Timer?
    private var checkingPasteboard = false
    /// The clipboard already read (or asked about) in instant mode, so iOS isn't asked twice.
    private var attemptedPasteboard: Int?

    private var composer = Composer()
    private var lastKeyAt: CFTimeInterval = 0

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
        readField()
        matchAppearance()
        configureReturn()
        updateAutoCapitalization()
        mode = .normal
        info = .idle
        checkPasteboard(waitForTyping: false)
        if hasFullAccess, SharedStore.apiKey != nil { Translator.warmUp() }
        pollTimer?.invalidate()
        pollTimer = Timer.scheduledTimer(timeInterval: 1.0, target: self, selector: #selector(poll), userInfo: nil, repeats: true)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        SharedStore.noteKeyboard(hasFullAccess ? "4 đã hiện (có truy cập đầy đủ)" : "4 đã hiện (chưa có truy cập đầy đủ)")
        // iOS holds back touches near the screen edges (q, a, z, p, l, delete, space) while it
        // checks for a system swipe, so those keys answered late when typing fast.
        view.window?.gestureRecognizers?.forEach { $0.delaysTouchesBegan = false }
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
        infoHeight?.constant = compact ? 26 : 30
        barHeight?.constant = compact ? 36 : 42
        keysHeight?.constant = compact ? 160 : 216
        keyboard.showsGlobe = needsInputModeSwitchKey
    }

    override func textDidChange(_ textInput: UITextInput?) {
        super.textDidChange(textInput)
        matchAppearance()
        fieldReported()
        configureReturn()
    }

    override func selectionDidChange(_ textInput: UITextInput?) {
        super.selectionDidChange(textInput)
        fieldReported()
    }

    private func matchAppearance() {
        let style: UIUserInterfaceStyle = textDocumentProxy.keyboardAppearance == .dark ? .dark : .unspecified
        // Setting it again would make every key resolve its colours again.
        if view.overrideUserInterfaceStyle != style { view.overrideUserInterfaceStyle = style }
    }

    @objc private func poll() {
        checkPasteboard(waitForTyping: true)
    }

    // MARK: Keys

    func keyboardLayoutView(_ view: KeyboardLayoutView, perform action: KeyAction) {
        let now = CACurrentMediaTime()
        lastKeyAt = now
        if composer.before == nil { readField() }
        let edits: [Composer.Edit]
        switch action {
        case .letter(let c):
            edits = composer.letter(c, now: now)
        case .text(let s):
            edits = composer.insert(s, now: now)
        case .delete(let word):
            edits = word ? composer.deleteWord(now: now) : composer.deleteBackward(now: now)
        case .space:
            edits = composer.space(now: now)
            keyboard.showLetters()
        case .newline:
            edits = composer.newline(now: now)
        case .moveCursor(let offset):
            textDocumentProxy.adjustTextPosition(byCharacterOffset: offset)
            composer.forget()
            edits = []
        }
        apply(edits)
        edited()
    }

    func keyboardLayoutView(_ view: KeyboardLayoutView, globeTouchedWith event: UIEvent) {
        handleInputModeList(from: view, with: event)
    }

    // MARK: Typing

    private func apply(_ edits: [Composer.Edit]) {
        let proxy = textDocumentProxy
        for edit in edits {
            switch edit {
            case .delete(let count):
                for _ in 0..<count { proxy.deleteBackward() }
            case .insert(let s):
                proxy.insertText(s)
            }
        }
    }

    /// Takes the field's text as it is now: on appearing, or after the cursor moved.
    private func readField() {
        let proxy = textDocumentProxy
        composer.start(before: proxy.documentContextBeforeInput ?? "", after: proxy.documentContextAfterInput ?? "",
                       now: CACurrentMediaTime())
    }

    /// The app reported its text. While it is only catching up with keys typed here nothing
    /// changes; when the text was changed elsewhere (sent, cleared, cursor moved) typing starts
    /// afresh from it.
    private func fieldReported() {
        let proxy = textDocumentProxy
        if composer.fieldChanged(before: proxy.documentContextBeforeInput ?? "", after: proxy.documentContextAfterInput ?? "",
                                 now: CACurrentMediaTime()) {
            updateAutoCapitalization()
        }
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
        let before = composer.before ?? proxy.documentContextBeforeInput ?? ""
        let on: Bool
        switch proxy.autocapitalizationType ?? .sentences {
        case .allCharacters:
            on = true
        case .words:
            on = before.isEmpty || before.last?.isWhitespace == true
        case .sentences:
            if before.isEmpty || before.hasSuffix("\n") {
                on = true
            } else if before.hasSuffix(" "), let last = before.last(where: { $0 != " " && $0 != "\t" }) {
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
        let typed = !(composer.before ?? "").isEmpty
        let enabled = !(proxy.enablesReturnKeyAutomatically ?? false) || proxy.hasText || typed
        keyboard.configureReturn(title: title, prominent: type != .default, enabled: enabled)
    }

    // MARK: Translate bar

    private func renderBar() {
        bar.arrangedSubviews.forEach { $0.removeFromSuperview() }
        switch mode {
        case .normal:
            bar.addArrangedSubview(logoButton())
            bar.addArrangedSubview(languagePill())
            bar.addArrangedSubview(spacer())
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
            bar.addArrangedSubview(pill("Xong") { [weak self] in self?.closePanel() })
        }
        let panelVisible: Bool
        if case .panel = mode { panelVisible = true } else { panelVisible = false }
        panel.isHidden = !panelVisible
        keyboard.isHidden = panelVisible
    }

    private func languagePill() -> UIView {
        let target = resolvedTarget(context: SharedStore.context())
        let title = targetCode == Language.autoCode ? "Tự động · \(target.label)" : "Việt → \(target.label)"
        let language = pill(title, image: "chevron.down", trailingImage: true) { [weak self] in self?.mode = .picking }
        language.accessibilityLabel = "Ngôn ngữ dịch: \(target.name)"
        // On narrow iPhones this pill gives way (and truncates) before the Dịch button does.
        language.configuration?.titleLineBreakMode = .byTruncatingTail
        language.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return language
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
            openOverview()
        case .copied:
            translateCopied()
        case .incomingBusy:
            break
        case .incoming(let source, let translation):
            presentIncoming(source: source, translation: translation)
        case .outgoing:
            openOutgoingCheck()
        }
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

    /// The Mộc Sinh mark: shows which keyboard is up, and opens everything at once.
    private func logoButton() -> UIButton {
        let button = UIButton(configuration: .plain(), primaryAction: UIAction { [weak self] _ in self?.openOverview() })
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
        button.accessibilityHint = "Xem tin đối tác, câu vừa dịch và ngữ cảnh"
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
    private func showPanel(_ title: String, _ parts: [(String, Bool)], button: String,
                           done: (() -> Void)? = nil, action: @escaping () -> Void) {
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
        panelDone = done
        mode = .panel(title)
    }

    private func closePanel() {
        let done = panelDone
        panelDone = nil
        mode = .normal
        done?()
    }

    /// Behind the mark: the partner's last message, the check of what was sent, and the context.
    private func openOverview() {
        var parts: [(String, Bool)] = []
        let recent = lastIncoming == nil || lastOutgoing == nil ? SharedStore.recentHistory() : []

        if let incoming = lastIncoming {
            parts += incomingParts(source: incoming.source, translation: incoming.translation.text, note: incoming.translation.note)
        } else if let item = recent.first(where: { $0.side == .partner }) {
            parts += incomingParts(source: item.source, translation: item.translation, note: item.note)
        }
        if let outgoing = lastOutgoing {
            let t = outgoing.translation
            parts += outgoingParts(original: outgoing.original, translation: t.text, language: t.language.name,
                                   meaning: t.meaning, note: t.note)
        } else if let item = recent.first(where: { $0.side == .me }) {
            parts += outgoingParts(original: item.source, translation: item.translation, language: item.languageName.lowercased(),
                                   meaning: item.meaning, note: item.note)
        }

        let lines = SharedStore.context()
        parts.append((lines.isEmpty ? "Ngữ cảnh: chưa có tin nào" : "Ngữ cảnh: \(lines.count) tin gần đây", true))
        parts += lines.map { (($0.side == .partner ? "Đối tác: " : "Tôi: ") + $0.text, false) }
        showPanel(Brand.name, parts, button: "Cuộc mới (xóa ngữ cảnh)") { [weak self] in
            SharedStore.clearContext()
            self?.lastIncoming = nil
            self?.lastOutgoing = nil
            self?.closePanel()
        }
    }

    private func incomingParts(source: String, translation: String, note: String?) -> [(String, Bool)] {
        var parts = [("Tin đối tác", true), (source, true), ("→ " + translation, false)]
        if let note { parts.append(("Lưu ý: " + note, true)) }
        return parts
    }

    private func outgoingParts(original: String, translation: String, language: String,
                               meaning: String?, note: String?) -> [(String, Bool)] {
        var parts = [(language.isEmpty ? "Bạn gửi" : "Bạn gửi (\(language))", true), (original, true), ("→ " + translation, false)]
        if let meaning { parts.append(("Nghĩa: " + meaning, true)) }
        if let note { parts.append(("Lưu ý: " + note, true)) }
        return parts
    }

    private func openOutgoingCheck() {
        guard let outgoing = lastOutgoing else { return }
        let t = outgoing.translation
        var parts = [("Bạn viết", true), (outgoing.original, false), ("Đã đổi sang \(t.language.name)", true), (t.text, false)]
        if let meaning = t.meaning { parts += [("Nghĩa khi dịch ngược", true), (meaning, false)] }
        if let note = t.note { parts += [("Lưu ý", true), (note, false)] }
        showPanel("Kiểm tra bản dịch", parts, button: "Hoàn tác, lấy lại câu gốc") { [weak self] in self?.undo() }
    }

    private func presentIncoming(source: String, translation: Translation) {
        var parts = [(source, true), ("→ " + translation.text, false)]
        if let note = translation.note { parts.append(("Lưu ý: " + note, true)) }
        showPanel("Tin đối tác → Tiếng Việt", parts, button: "Sao chép bản dịch") { [weak self] in
            UIPasteboard.general.string = translation.text
            SharedStore.handledPasteboard = UIPasteboard.general.changeCount
            self?.panelButton.configuration?.title = "Đã sao chép"
        }
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
        showPanel("Cần API key",
                  [("Bàn phím chưa có API key OpenAI.", true),
                   ("Sao chép API key (bắt đầu bằng sk-) rồi bấm nút bên dưới.", false),
                   ("Đã dán key trong app MSTrans mà vẫn thấy dòng này: app và bàn phím chưa dùng chung dữ liệu (do cách cài). Dán ở đây là đủ.", true)],
                  button: "Dán API key") { [weak self] in self?.pasteKey() }
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
                self.showPanel("Cần API key", [("Đã lưu API key cho bàn phím.", true), ("Bấm Dịch để dịch câu bạn đang viết.", false)],
                               button: "Xong") { [weak self] in self?.closePanel() }
            }
        }
    }

    /// "Tự động" answers in the partner's language; otherwise the chosen one.
    private func resolvedTarget(context: [ChatLine]) -> Language {
        Language.target(code: targetCode, context: context)
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
                        self?.lastOutgoing = (result, text)
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
            self?.lastOutgoing = nil
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
        composer.forget()

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
        composer.forget()
        func clear() {
            steps += 1
            let before = proxy.documentContextBeforeInput ?? ""
            let after = proxy.documentContextAfterInput ?? ""
            emptyRounds = before.isEmpty && after.isEmpty ? emptyRounds + 1 : 0
            guard proxy.hasText, emptyRounds < 3, steps < 80 else {
                proxy.insertText(text)
                // The field is read again before the next key.
                composer.forget()
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

    /// Looks for a newly copied message. Asking the clipboard goes to another process, so it is
    /// done off the main thread, and not while the user is typing: the keys always come first.
    /// The change count and `hasStrings` never ask for paste permission; only reading the text does.
    private func checkPasteboard(waitForTyping: Bool) {
        // Typing comes first: nothing is asked while keys are being pressed.
        if waitForTyping && CACurrentMediaTime() - lastKeyAt < 1.5 { return }
        guard hasFullAccess, !checkingPasteboard, SharedStore.apiKey != nil else { return }
        checkingPasteboard = true
        let handled = SharedStore.handledPasteboard
        DispatchQueue.global(qos: .utility).async { [weak self] in
            let pasteboard = UIPasteboard.general
            let changeCount = pasteboard.changeCount
            let fresh = changeCount != handled && pasteboard.hasStrings
            DispatchQueue.main.async {
                self?.checkingPasteboard = false
                self?.pasteboardChecked(changeCount: changeCount, fresh: fresh)
            }
        }
    }

    /// Offers a fresh copy (or, in instant mode, translates it once by itself). Reading the text
    /// makes iOS ask for paste permission and blocks until it is answered, so that waits for a
    /// tap unless the user chose instant mode.
    private func pasteboardChecked(changeCount: Int, fresh: Bool) {
        switch info {
        case .idle, .incoming, .outgoing:
            guard fresh else { return }
            if SharedStore.instantIncoming, attemptedPasteboard != changeCount {
                attemptedPasteboard = changeCount
                translateCopied()
            } else {
                info = .copied
            }
        case .copied:
            if !fresh { info = .idle }
        case .incomingBusy, .message:
            break
        }
    }

    /// Translates the copied message, off the main thread so the keys stay responsive while iOS
    /// asks for paste permission.
    private func translateCopied() {
        guard readyKey() != nil else { return }
        let changeCount = UIPasteboard.general.changeCount
        if case .panel = mode { mode = .normal }
        info = .incomingBusy
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
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
        // Whatever was copied is translated into Vietnamese: no guessing whether it already is
        // (a foreign message that names a Vietnamese person read as Vietnamese).
        SharedStore.handledPasteboard = changeCount
        SharedStore.remember(ChatLine(side: .partner, text: copied, date: Date()))
        translateIncoming(copied)
    }

    private func translateIncoming(_ text: String) {
        if let cached = SharedStore.cachedIncoming(text) {
            show(incoming: text, translation: Translation(text: cached, meaning: nil, note: nil, language: .vietnamese))
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
                self.show(incoming: text, translation: result)
            } catch is CancellationError {
            } catch {
                self?.info = .message(error.localizedDescription)
            }
        }
    }

    private func show(incoming source: String, translation: Translation) {
        lastIncoming = (source, translation)
        info = .incoming(source: source, translation: translation)
        expandIfLong(source: source, translation: translation)
    }
}

/// Lets keys play the standard keyboard click.
final class KeyboardInputView: UIInputView, UIInputViewAudioFeedback {
    var enableInputClicksWhenVisible: Bool { true }
}
