import UIKit

/// Vietnamese Telex keyboard with a translate bar on top.
/// Tapping "Dịch" replaces everything in the text field with its translation.
final class KeyboardViewController: UIInputViewController {

    private enum Mode: Equatable {
        case normal, picking, busy, done, failed(String), copied, context
    }

    private var mode: Mode = .normal { didSet { render() } }
    private var shifted = false
    private var numbers = false
    private var original = ""
    private var usedContext = 0
    private lazy var targetCode = SharedStore.targetCode
    private var task: Task<Void, Never>?
    private var deleteTask: Task<Void, Never>?

    private let bar = UIStackView()
    private let keysView = UIStackView()
    private let panel = UIStackView()
    private let panelText = UITextView()
    private let panelButton = UIButton(configuration: .filled())
    private var panelAction: (() -> Void)?
    private var letterButtons: [(UIButton, String)] = []
    private weak var shiftButton: UIButton?

    private var target: Language { Language.target(targetCode) }

    // MARK: Lifecycle

    override func loadView() {
        let input = KeyboardInputView(frame: .zero, inputViewStyle: .keyboard)
        input.allowsSelfSizing = true
        view = input
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        bar.axis = .horizontal
        bar.spacing = 6
        bar.alignment = .center
        bar.isLayoutMarginsRelativeArrangement = true
        bar.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 0, leading: 8, bottom: 0, trailing: 8)

        keysView.axis = .vertical
        keysView.spacing = 11
        keysView.distribution = .fillEqually
        keysView.isLayoutMarginsRelativeArrangement = true
        keysView.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 8, leading: 3, bottom: 4, trailing: 3)

        buildPanel()

        for v in [bar, keysView, panel] as [UIView] {
            v.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(v)
        }
        let height = view.heightAnchor.constraint(equalToConstant: 262)
        height.priority = UILayoutPriority(999)
        NSLayoutConstraint.activate([
            height,
            bar.topAnchor.constraint(equalTo: view.topAnchor),
            bar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            bar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bar.heightAnchor.constraint(equalToConstant: 46),
            keysView.topAnchor.constraint(equalTo: bar.bottomAnchor),
            keysView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            keysView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            keysView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            panel.topAnchor.constraint(equalTo: bar.bottomAnchor, constant: 6),
            panel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 6),
            panel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -6),
            panel.bottomAnchor.constraint(equalTo: view.bottomAnchor, constant: -4),
        ])
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        if hasFullAccess {
            SharedStore.keyboardHasFullAccess = true
            SharedStore.keyboardVisible = true
        }
        targetCode = SharedStore.targetCode
        matchAppearance()
        buildKeys()
        mode = .normal
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if hasFullAccess { SharedStore.keyboardVisible = false }
        task?.cancel()
        deleteTask?.cancel()
    }

    override func textDidChange(_ textInput: UITextInput?) {
        super.textDidChange(textInput)
        matchAppearance()
    }

    private func matchAppearance() {
        view.overrideUserInterfaceStyle = textDocumentProxy.keyboardAppearance == .dark ? .dark : .unspecified
    }

    // MARK: Keys

    private func buildKeys() {
        keysView.arrangedSubviews.forEach { $0.removeFromSuperview() }
        letterButtons = []
        let rows = numbers
            ? ["1234567890", "-/:;()&@\"", ".,?!'%+"]
            : ["qwertyuiop", "asdfghjkl", "zxcvbnm"]

        keysView.addArrangedSubview(keyRow(rows[0], inset: 0))
        keysView.addArrangedSubview(keyRow(rows[1], inset: numbers ? 0 : 18))

        let third = UIStackView()
        third.spacing = 8
        let left: UIView
        if numbers {
            left = UIView()
        } else {
            let shift = key(nil, image: "shift", special: true) { [weak self] in self?.toggleShift() }
            shift.accessibilityLabel = "Chữ hoa"
            shiftButton = shift
            left = shift
        }
        let back = backspaceKey()
        third.addArrangedSubview(left)
        third.addArrangedSubview(keyRow(rows[2], inset: 0))
        third.addArrangedSubview(back)
        left.widthAnchor.constraint(equalToConstant: 44).isActive = true
        back.widthAnchor.constraint(equalToConstant: 44).isActive = true
        keysView.addArrangedSubview(third)

        let bottom = UIStackView()
        bottom.spacing = 6
        if needsInputModeSwitchKey {
            let globe = key(nil, image: "globe", special: true) {}
            globe.accessibilityLabel = "Bàn phím tiếp theo"
            globe.addTarget(self, action: #selector(handleInputModeList(from:with:)), for: .allTouchEvents)
            globe.widthAnchor.constraint(equalToConstant: 44).isActive = true
            bottom.addArrangedSubview(globe)
        }
        let modeKey = key(numbers ? "ABC" : "123", special: true) { [weak self] in
            guard let self else { return }
            self.numbers.toggle()
            self.shifted = false
            self.buildKeys()
        }
        let space = key("dấu cách") { [weak self] in self?.insert(" ") }
        let newline = key("xuống dòng", special: true) { [weak self] in self?.insert("\n") }
        modeKey.widthAnchor.constraint(equalToConstant: 80).isActive = true
        newline.widthAnchor.constraint(equalToConstant: 92).isActive = true
        for b in [modeKey, space, newline] { bottom.addArrangedSubview(b) }
        keysView.addArrangedSubview(bottom)
        updateShift()
    }

    private func keyRow(_ characters: String, inset: CGFloat) -> UIStackView {
        let row = UIStackView()
        row.spacing = 5
        row.distribution = .fillEqually
        row.isLayoutMarginsRelativeArrangement = true
        row.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 0, leading: inset, bottom: 0, trailing: inset)
        for c in characters {
            let s = String(c)
            let b = key(s) { [weak self] in self?.type(s) }
            if c.isLetter { letterButtons.append((b, s)) }
            row.addArrangedSubview(b)
        }
        return row
    }

    private func key(_ title: String?, image: String? = nil, special: Bool = false, action: @escaping () -> Void) -> UIButton {
        let b = UIButton(type: .custom)
        if let title {
            b.setTitle(title, for: .normal)
            b.titleLabel?.font = .systemFont(ofSize: title.count > 1 ? 16 : 22)
            b.setTitleColor(.label, for: .normal)
        }
        if let image {
            b.setImage(UIImage(systemName: image, withConfiguration: UIImage.SymbolConfiguration(pointSize: 18)), for: .normal)
            b.tintColor = .label
        }
        b.backgroundColor = special ? Palette.specialKey : Palette.key
        b.layer.cornerRadius = 6
        b.layer.shadowColor = UIColor.black.cgColor
        b.layer.shadowOpacity = 0.3
        b.layer.shadowOffset = CGSize(width: 0, height: 1)
        b.layer.shadowRadius = 0
        b.addAction(UIAction { _ in
            UIDevice.current.playInputClick()
            action()
        }, for: .touchUpInside)
        return b
    }

    private func backspaceKey() -> UIButton {
        let b = key(nil, image: "delete.left", special: true) {}
        b.accessibilityLabel = "Xóa"
        b.addAction(UIAction { [weak self] _ in self?.startDeleting() }, for: .touchDown)
        b.addAction(UIAction { [weak self] _ in self?.deleteTask?.cancel() }, for: [.touchUpInside, .touchUpOutside, .touchCancel])
        return b
    }

    private func startDeleting() {
        deleteBackward()
        deleteTask?.cancel()
        deleteTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 450_000_000)
            while !Task.isCancelled {
                self?.deleteBackward()
                try? await Task.sleep(nanoseconds: 80_000_000)
            }
        }
    }

    private func toggleShift() {
        shifted.toggle()
        updateShift()
    }

    private func updateShift() {
        UIView.performWithoutAnimation {
            for (b, s) in letterButtons {
                b.setTitle(shifted ? s.uppercased() : s, for: .normal)
                b.layoutIfNeeded()
            }
        }
        shiftButton?.backgroundColor = shifted ? Palette.key : Palette.specialKey
        shiftButton?.accessibilityTraits = shifted ? [.button, .selected] : .button
    }

    // MARK: Typing

    private func type(_ s: String) {
        guard !numbers, let first = s.first, first.isLetter else { return insert(s) }
        let key = Character(shifted ? s.uppercased() : s)
        let proxy = textDocumentProxy
        let before = proxy.documentContextBeforeInput ?? ""
        let word = String(before.reversed().prefix { $0.isLetter }.reversed())
        let result = Telex.apply(word, key)
        if result == word + String(key) {
            proxy.insertText(String(key))
        } else {
            for _ in 0..<word.count { proxy.deleteBackward() }
            proxy.insertText(result)
        }
        if shifted { shifted = false; updateShift() }
        edited()
    }

    private func insert(_ s: String) {
        textDocumentProxy.insertText(s)
        edited()
    }

    private func deleteBackward() {
        textDocumentProxy.deleteBackward()
        edited()
    }

    private func edited() {
        switch mode {
        case .done, .failed: mode = .normal
        default: break
        }
    }

    // MARK: Translate bar

    private func render() {
        bar.arrangedSubviews.forEach { $0.removeFromSuperview() }
        switch mode {
        case .normal:
            bar.addArrangedSubview(pill("Việt → \(target.label)", image: "chevron.down", trailingImage: true) { [weak self] in
                self?.mode = .picking
            })
            let count = SharedStore.context().count
            let ctx = pill("\(count)", image: "eye") { [weak self] in self?.openContext() }
            ctx.accessibilityLabel = "Ngữ cảnh: \(count) tin"
            bar.addArrangedSubview(ctx)
            bar.addArrangedSubview(spacer())
            if hasNewCopy {
                bar.addArrangedSubview(pill("Tin copy", style: .incoming) { [weak self] in self?.translateCopied() })
            }
            bar.addArrangedSubview(pill("Dịch", image: "translate", style: .primary) { [weak self] in self?.translateDraft() })
        case .picking:
            for language in Language.targets {
                bar.addArrangedSubview(pill(language.label, style: language.code == targetCode ? .primary : .plain) { [weak self] in
                    self?.targetCode = language.code
                    SharedStore.targetCode = language.code
                    self?.mode = .normal
                })
            }
            bar.addArrangedSubview(spacer())
        case .busy:
            bar.addArrangedSubview(status("Đang dịch sang \(target.label)…", spinner: true))
            bar.addArrangedSubview(pill("Hủy") { [weak self] in
                self?.task?.cancel()
                self?.mode = .normal
            })
        case .done:
            let note = usedContext > 0 ? " · \(usedContext) tin ngữ cảnh" : ""
            bar.addArrangedSubview(status("Đã dịch bằng AI" + note, check: true))
            bar.addArrangedSubview(pill("Hoàn tác") { [weak self] in self?.undo() })
        case .failed(let message):
            bar.addArrangedSubview(status(message, color: .systemRed))
            bar.addArrangedSubview(pill("Đóng") { [weak self] in self?.mode = .normal })
        case .copied:
            bar.addArrangedSubview(status("Tin đến → Tiếng Việt", color: Palette.incoming))
            bar.addArrangedSubview(pill("Xong") { [weak self] in
                self?.task?.cancel()
                self?.mode = .normal
            })
        case .context:
            bar.addArrangedSubview(status("Ngữ cảnh đang dùng"))
            bar.addArrangedSubview(pill("Xong") { [weak self] in self?.mode = .normal })
        }
        let panelVisible = mode == .copied || mode == .context
        panel.isHidden = !panelVisible
        keysView.isHidden = panelVisible
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
            icon.tintColor = .systemGreen
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

    // MARK: Panel (copied message, context)

    private func buildPanel() {
        panel.axis = .vertical
        panel.spacing = 8
        panel.isLayoutMarginsRelativeArrangement = true
        panel.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 4, leading: 6, bottom: 8, trailing: 6)
        panel.backgroundColor = Palette.key
        panel.layer.cornerRadius = 12
        panel.isHidden = true

        panelText.isEditable = false
        panelText.backgroundColor = .clear
        panelText.font = .systemFont(ofSize: 17)
        panelText.textColor = .label

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

    private func showPanel(_ text: String, button: String, action: @escaping () -> Void) {
        panelText.text = text
        panelText.setContentOffset(.zero, animated: false)
        panelButton.configuration?.title = button
        panelAction = action
    }

    private func openContext() {
        let lines = SharedStore.context()
        let text = lines.isEmpty
            ? "Chưa có tin nào làm ngữ cảnh."
            : lines.map { ($0.side == .partner ? "Đối tác: " : "Tôi: ") + $0.text }.joined(separator: "\n")
        showPanel(text, button: "Cuộc mới") { [weak self] in
            SharedStore.clearContext()
            self?.mode = .normal
        }
        mode = .context
    }

    // MARK: Translation

    private var hasNewCopy: Bool {
        hasFullAccess && UIPasteboard.general.hasStrings
            && UIPasteboard.general.changeCount != SharedStore.handledPasteboard
    }

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

    private func translateDraft() {
        guard let apiKey = readyKey() else { return }
        let proxy = textDocumentProxy
        let text = (proxy.documentContextBeforeInput ?? "") + (proxy.selectedText ?? "") + (proxy.documentContextAfterInput ?? "")
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            mode = .failed(TranslateError.empty.errorDescription ?? "")
            return
        }
        let target = self.target
        let context = SharedStore.context()
        usedContext = context.count
        mode = .busy
        task = Task { [weak self] in
            do {
                let output = try await Translator(apiKey: apiKey).translate(text, to: target, context: context)
                guard let self, !Task.isCancelled else { return }
                self.original = text
                self.replaceAll(with: output)
                SharedStore.remember(ChatLine(side: .me, text: output, date: Date()))
                self.mode = .done
            } catch is CancellationError {
            } catch {
                self?.mode = .failed(error.localizedDescription)
            }
        }
    }

    private func translateCopied() {
        guard let apiKey = readyKey() else { return }
        let pasteboard = UIPasteboard.general
        SharedStore.handledPasteboard = pasteboard.changeCount
        guard let text = pasteboard.string?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else {
            mode = .failed("Chưa có tin nào được sao chép.")
            return
        }
        let context = SharedStore.context()
        SharedStore.remember(ChatLine(side: .partner, text: text, date: Date()))
        showPanel("Đang dịch…", button: "Sao chép bản dịch") {}
        mode = .copied
        task = Task { [weak self] in
            do {
                let output = try await Translator(apiKey: apiKey).translate(text, to: .vietnamese, context: context)
                guard let self, !Task.isCancelled else { return }
                self.showPanel(output, button: "Sao chép bản dịch") { [weak self] in
                    UIPasteboard.general.string = output
                    SharedStore.handledPasteboard = UIPasteboard.general.changeCount
                    self?.panelButton.configuration?.title = "Đã sao chép"
                }
            } catch is CancellationError {
            } catch {
                self?.showPanel(error.localizedDescription, button: "Thử lại") { [weak self] in self?.translateCopied() }
            }
        }
    }

    /// Replaces the whole text field content, keeping it a single undoable step for the user.
    private func replaceAll(with text: String) {
        let proxy = textDocumentProxy
        if let selected = proxy.selectedText, !selected.isEmpty { proxy.deleteBackward() }
        let before = proxy.documentContextBeforeInput ?? ""
        let after = proxy.documentContextAfterInput ?? ""
        if !after.isEmpty { proxy.adjustTextPosition(byCharacterOffset: after.utf16.count) }
        for _ in 0..<(before.count + after.count) { proxy.deleteBackward() }
        proxy.insertText(text)
    }

    private func undo() {
        replaceAll(with: original)
        mode = .normal
    }
}

/// Lets keys play the standard keyboard click.
final class KeyboardInputView: UIInputView, UIInputViewAudioFeedback {
    var enableInputClicksWhenVisible: Bool { true }
}

enum Palette {
    static let key = UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.42, alpha: 1) : .white }
    static let specialKey = UIColor {
        $0.userInterfaceStyle == .dark ? UIColor(white: 0.27, alpha: 1) : UIColor(red: 0.68, green: 0.71, blue: 0.75, alpha: 1)
    }
    static let accent = UIColor {
        $0.userInterfaceStyle == .dark ? UIColor(red: 0.18, green: 0.37, blue: 0.88, alpha: 1) : UIColor(red: 0.11, green: 0.31, blue: 0.85, alpha: 1)
    }
    static let incoming = UIColor {
        $0.userInterfaceStyle == .dark ? UIColor(red: 0.94, green: 0.63, blue: 0.44, alpha: 1) : UIColor(red: 0.60, green: 0.20, blue: 0.07, alpha: 1)
    }
}
