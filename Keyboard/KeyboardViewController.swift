import UIKit

/// The translation keyboard: an info line, a translate bar and an iOS-style Telex keyboard.
///
/// - Info line, only while there is something to read: the Vietnamese translation of the
///   partner's message the user just copied, or the meaning of what was just sent.
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
    /// The clipboard's change count at the last look, and at the last translation of the user's
    /// message: a copy older than that translation does not take the line showing its meaning.
    private var seenPasteboard: Int?
    private var pasteboardAtOutgoing: Int?
    /// The field the keyboard is typing in (`documentIdentifier`): another one means another text
    /// field, where a translation of the text read from the first must not go.
    private var fieldID: UUID?
    /// `documentIdentifier` now, or nil while the field is being reset. UIKit declares it never nil, and
    /// read as declared that nil stops the keyboard (a `textDidChange` came then in the CI test); asked
    /// for as an object, it is just nil.
    private var currentField: UUID? {
        textDocumentProxy.perform(#selector(getter: UITextDocumentProxy.documentIdentifier))?.takeUnretainedValue() as? UUID
    }
    /// The field takes text as typed: an e-mail address, a link, a user name, a code or a number
    /// (`readFieldTraits`). Letters go in without Telex ("dd" in an address stayed "dd", not "đ").
    private var plainTyping = false
    /// The field is for digits, and the keyboard opened on the number page for it.
    private var digitField = false

    private var composer = Composer()
    private var lastKeyAt: CFTimeInterval = 0
    /// Read once as the keyboard appears: `hasFullAccess` asks iOS's privacy service every time
    /// (the clipboard check asked it every second), and it cannot change while the keyboard is up.
    private var fullAccess = false
    /// Whether the globe key is needed is only known for sure once the keyboard is connected to
    /// the app (`viewDidAppear`); until then it is asked again at each layout, after that never.
    private var globeKnown = false
    /// Until the first key: iOS sometimes hands the app's text settings over only after the
    /// keyboard appeared, so Shift for the first letter is worked out again when the app reports.
    private var typedSinceAppear = false
    /// A look at the field is due once typing pauses (`checkField`).
    private var checkPending = false
    /// The key typed last and the typing as it was before it, so holding that key can take it
    /// back (`KeyAction.undoLast`).
    private var lastTyped: (composer: Composer, edits: [Composer.Edit])?
    /// When the latest edit went to the app, until the app reports it; the slowest per burst of
    /// typing goes to the typing log, to tell a slow app from a slow keyboard.
    private var editSentAt: CFTimeInterval = 0
    private var slowestApp: CFTimeInterval = 0
    /// A pause this long means the keys typed so far have reached the app.
    private static let settle: CFTimeInterval = 0.5
    private var benchmark: TypingBenchmark?
    /// The cursor is walking the field (`readDocument`, `replaceDocument`): keys wait until it is
    /// back at the end and go in there, in order. Typed meanwhile, they went in wherever the walk
    /// was (often the start of the message), or were wiped with the text being replaced.
    private var walking = 0
    private var heldKeys: [KeyAction] = []
    /// Each walk belongs to one showing of the keyboard: hidden meanwhile, it stops (it would go on
    /// in whatever field has the keyboard next).
    private var walkGeneration = 0
    /// How tall the keys are (Cài đặt › Bàn phím › Cỡ bàn phím), read as the keyboard appears.
    private var keySize = KeyboardSize.medium
    /// The language "Tự động" stands for on the pill. Working it out runs language detection over
    /// the partner's messages, so it is done off the main thread and kept: the bar is drawn again
    /// on the first key after a translation, and that key no longer waits for it.
    private var pillTarget: Language?
    private var pillTargetRequest = 0
    private weak var languageButton: UIButton?
    /// The shared context and history (JSON in the App Group) are read and written here, in order
    /// and off the main thread: a translation coming back while the user types never holds up a key.
    private static let store = DispatchQueue(label: "MSTrans.store", qos: .utility)

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
        // Not clear: a touch on a clear spot (around the bar's buttons, between keys) goes to the app
        // behind the keyboard (see `KeyboardLayoutView.touchable`).
        input.backgroundColor = KeyboardLayoutView.touchable
        view = input
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        TypingLog.mark("tạo giao diện")
        SharedStore.noteKeyboard("2 tạo giao diện")
        TypingLog.watchMainThread()
        // The first Telex key builds the engine's tables: now, off the main thread, not on the
        // first key press.
        DispatchQueue.global(qos: .userInitiated).async {
            var warm = Composer()
            warm.start(before: "", after: "", now: 0)
            for key in "nguwowif chuaw" {
                if key == " " { _ = warm.space(now: 0) } else { _ = warm.letter(key, now: 0) }
            }
            // The key pop-up's big letter: the first one drawn sets up the font at that size.
            _ = UIGraphicsImageRenderer(size: CGSize(width: 48, height: 48)).image { _ in
                ("aĂ" as NSString).draw(at: .zero, withAttributes: [.font: UIFont.systemFont(ofSize: 34)])
            }
            // The key letters at their sizes, both cases, the same way.
            let letters = "qwertyuiopasdfghjklzxcvbnm"
            _ = UIGraphicsImageRenderer(size: CGSize(width: 700, height: 120)).image { _ in
                for (text, size, y) in [(letters, 23.0, 0.0), (letters.uppercased(), 23, 30),
                                        ("1234567890-/:;()₫&@\".,?!'", 22.5, 60), ("ABC123#+=dấu cách", 16, 90)] as [(String, CGFloat, CGFloat)] {
                    (text as NSString).draw(at: CGPoint(x: 0, y: y), withAttributes: [.font: UIFont.systemFont(ofSize: size)])
                }
            }
        }
        // The language model is only needed to translate, and loading it is heavy: at a lower
        // priority, so it never takes a fast core from the keys while the keyboard opens.
        DispatchQueue.global(qos: .utility).async {
            _ = LanguageDetector.language(of: "Can you send me the price list?")
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
        let infoHeight = infoButton.heightAnchor.constraint(equalToConstant: 0)
        let barHeight = bar.heightAnchor.constraint(equalToConstant: 42)
        let keysHeight = keyboard.heightAnchor.constraint(equalToConstant: keyArea())
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
        TypingLog.mark("mở")
        fullAccess = hasFullAccess
        SharedStore.noteKeyboard(fullAccess ? "3 sắp hiện (có truy cập đầy đủ)" : "3 sắp hiện (chưa có truy cập đầy đủ)")
        if fullAccess { SharedStore.keyboardHasFullAccess = true }
        targetCode = SharedStore.targetCode
        keyboard.hapticsEnabled = fullAccess && SharedStore.haptics
        keyboard.clicksEnabled = SharedStore.keySounds
        keyboard.playsOwnClicks = fullAccess
        keyboard.typesOnTouchDown = SharedStore.typesOnTouchDown
        keyboard.showsPopups = SharedStore.keyPopups
        let size = SharedStore.keyboardSize
        if size != keySize {
            keySize = size
            view.setNeedsLayout()
        }
        keyboard.extraKeyHeight = CGFloat(keySize.extraKeyHeight)
        globeKnown = false
        keyboard.showsGlobe = needsInputModeSwitchKey
        readFieldTraits(appearing: true)
        readField()
        typedSinceAppear = false
        TypingLog.add("▶ mở: \(TypingLog.tail(composer.before ?? ""))|\(TypingLog.head(textDocumentProxy.documentContextAfterInput ?? ""))")
        matchAppearance()
        configureReturn()
        updateAutoCapitalization()
        refreshPillTarget()
        mode = .normal
        info = .idle
        checkPasteboard(waitForTyping: false)
        // Setting up the network session takes a moment: not on the main thread as the keys appear.
        if fullAccess, SharedStore.apiKey != nil { Self.store.async { Translator.warmUp() } }
        pollTimer?.invalidate()
        pollTimer = Timer.scheduledTimer(timeInterval: 1.0, target: self, selector: #selector(poll), userInfo: nil, repeats: true)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        fullAccess = hasFullAccess
        SharedStore.noteKeyboard(fullAccess ? "4 đã hiện (có truy cập đầy đủ)" : "4 đã hiện (chưa có truy cập đầy đủ)")
        // Turned off later in Settings, the app's setup and diagnostics say so again.
        if SharedStore.keyboardHasFullAccess != fullAccess { SharedStore.keyboardHasFullAccess = fullAccess }
        keyboard.showsGlobe = needsInputModeSwitchKey
        globeKnown = true
        // iOS holds back touches near the screen edges (q, a, z, p, l, delete, space) while it
        // checks for a system swipe, so those keys answered late when typing fast. The window and
        // every view up to it can carry such a recognizer. The log says how many were found: a
        // tap held back anyway shows as "iOS giữ lại" (see `KeyboardLayoutView.finish`).
        var released = 0
        var ancestor: UIView? = view
        while let current = ancestor {
            for recognizer in current.gestureRecognizers ?? [] where recognizer.delaysTouchesBegan {
                recognizer.delaysTouchesBegan = false
                released += 1
            }
            ancestor = current.superview
        }
        TypingLog.add("▶ đã hiện · cử chỉ iOS thôi giữ chạm: \(released)")
        if !typedSinceAppear {
            updateAutoCapitalization()
            configureReturn()
        }
        startBenchmarkIfAsked()
    }

    /// CI only (ci/keyboard-smoke.sh): types through the real keys and records how long each
    /// key keeps the main thread busy.
    private func startBenchmarkIfAsked() {
        guard SharedStore.defaults.bool(forKey: TypingBenchmark.flagKey) else { return }
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

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        pollTimer?.invalidate()
        pollTimer = nil
        task?.cancel()
        incomingTask?.cancel()
        walkGeneration += 1
        walking = 0
        heldKeys = []
        TypingLog.add("■ đóng")
        TypingLog.save(now: true)
    }

    override func viewWillLayoutSubviews() {
        super.viewWillLayoutSubviews()
        TypingLog.mark("bố cục")
        let compact = traitCollection.verticalSizeClass == .compact
        // Only real changes: a constraint set again asks for another layout of the keyboard.
        let heights: [(NSLayoutConstraint?, CGFloat)] = [(infoHeight, infoLineHeight), (barHeight, compact ? 36 : 42),
                                                          (keysHeight, keyArea())]
        for (constraint, value) in heights where constraint?.constant != value { constraint?.constant = value }
        if !globeKnown { keyboard.showsGlobe = needsInputModeSwitchKey }
    }

    /// The keys' height for the view's width: Apple's keyboard's, plus the size setting (`KeyMetrics`).
    private func keyArea() -> CGFloat {
        KeyMetrics.phone(width: view.bounds.width, homeButton: keyboard.showsGlobe, extra: CGFloat(keySize.extraKeyHeight)).area
    }

    override func textDidChange(_ textInput: UITextInput?) {
        super.textDidChange(textInput)
        // Another text field took the keyboard without it going away: what it is for is read again.
        // While a field is being reset it has no identifier: a later change tells which it is.
        if let field = currentField, field != fieldID { readFieldTraits(appearing: false) }
        matchAppearance()
        fieldReported()
        configureReturn()
    }

    /// What the field is for. An e-mail address, a link, a user name or a code is typed as is,
    /// digits open on the number page, and the typing log keeps nothing typed in such a field.
    private func readFieldTraits(appearing: Bool) {
        let proxy = textDocumentProxy
        // Not known while the field is being reset: the last field known stays until it is.
        let field = currentField
        let switched = fieldID != nil && field != nil && field != fieldID
        fieldID = field ?? fieldID
        let type = proxy.keyboardType ?? .default
        let content: UITextContentType? = proxy.textContentType ?? nil
        // Not the name-or-phone pad: a Vietnamese name needs its marks.
        let plainTypes: [UIKeyboardType] = [.emailAddress, .URL, .numberPad, .phonePad, .decimalPad, .asciiCapableNumberPad]
        let plainContent: [UITextContentType] = [.emailAddress, .URL, .username, .password, .newPassword, .oneTimeCode,
                                                 .creditCardNumber, .telephoneNumber]
        let digitTypes: [UIKeyboardType] = [.numberPad, .phonePad, .decimalPad, .asciiCapableNumberPad, .numbersAndPunctuation]
        let digitContent: [UITextContentType] = [.oneTimeCode, .creditCardNumber, .telephoneNumber]
        plainTyping = plainTypes.contains(type) || content.map { plainContent.contains($0) } == true
        TypingLog.hidesText = plainTyping
        let digits = digitTypes.contains(type) || content.map { digitContent.contains($0) } == true
        // As the keyboard appears, or as a field of the other kind takes it; never while typing.
        if appearing || (switched && digits != digitField) {
            if digits { keyboard.showNumbers() } else { keyboard.showLetters() }
        }
        digitField = digits
        if switched {
            TypingLog.add("▶ ô khác")
            if mode == .busy {
                // The text being translated was read from the other field: it must not replace this one.
                task?.cancel()
                mode = .failed(Self.fieldSwitched)
            }
            composer.forget()
        }
    }

    override func selectionDidChange(_ textInput: UITextInput?) {
        super.selectionDidChange(textInput)
        fieldReported()
    }

    private func matchAppearance() {
        // An app that asks for a light or dark keyboard gets it whatever the system's setting.
        let style: UIUserInterfaceStyle
        switch textDocumentProxy.keyboardAppearance ?? .default {
        case .dark: style = .dark
        case .light: style = .light
        default: style = .unspecified
        }
        // Setting it again would make every key resolve its colours again.
        if view.overrideUserInterfaceStyle != style { view.overrideUserInterfaceStyle = style }
    }

    @objc private func poll() {
        checkPasteboard(waitForTyping: true)
    }

    // MARK: Keys

    func keyboardLayoutView(_ view: KeyboardLayoutView, perform action: KeyAction) {
        TypingLog.mark("phím")
        if walking > 0 {
            heldKeys.append(action)
            return
        }
        let now = CACurrentMediaTime()
        if composer.before == nil {
            readField()
        } else if now - lastKeyAt >= Self.settle {
            // The first key after a pause: the field has caught up, so it is looked at first.
            reconcile(now: now)
        }
        lastKeyAt = now
        typedSinceAppear = true
        let previous = composer
        var typed = false
        let edits: [Composer.Edit]
        switch action {
        case .letter(let c):
            edits = plainTyping ? composer.insert(String(c), now: now) : composer.letter(c, now: now)
            typed = true
        case .text(let s):
            edits = composer.insert(s, now: now)
            typed = true
        case .delete(let word):
            edits = word ? composer.deleteWord(now: now) : composer.deleteBackward(now: now)
        case .space:
            edits = composer.space(now: now)
            typed = true
            keyboard.showLetters()
        case .newline:
            edits = composer.newline(now: now)
        case .moveCursor(let offset):
            textDocumentProxy.adjustTextPosition(byCharacterOffset: offset)
            composer.moveCursor(by: offset, now: now)
            edits = []
        case .undoLast:
            if let last = lastTyped, let undo = Composer.inverse(of: last.edits, before: last.composer.before) {
                edits = undo
                composer = last.composer
            } else {
                edits = []
            }
        }
        lastTyped = typed ? (composer: previous, edits: edits) : nil
        apply(edits)
        if !edits.isEmpty { editSentAt = now }
        scheduleCheck()
        edited()
        // The whole key, the bar and Shift it updated included.
        let took = CACurrentMediaTime() - now
        TypingLog.add("\(Self.describe(action)) → \(edits.map(Self.describeEdit).joined()) | \(TypingLog.tail(composer.before ?? "?"))"
                      + (took > 0.002 ? " ⏱\(Int(took * 1000))ms" : ""))
    }

    private static func describe(_ action: KeyAction) -> String {
        switch action {
        case .letter(let c): return TypingLog.typed(String(c))
        case .text(let s): return TypingLog.typed(s)
        case .delete(let word): return word ? "⌫⌫" : "⌫"
        case .space: return "␣"
        case .newline: return "⏎"
        case .moveCursor(let offset): return "↔\(offset)"
        case .undoLast: return "↶"
        }
    }

    private static func describeEdit(_ edit: Composer.Edit) -> String {
        switch edit {
        case .delete(let count): return "-\(count)"
        case .insert(let s): return "+" + TypingLog.typed(s).replacingOccurrences(of: "\n", with: "⏎")
        }
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
    ///
    /// While keys are going in, reports trail them, and some apps (Spotlight) now and then report
    /// text that was never there: taking it reset the word being typed and turned Shift on
    /// mid-sentence. So the keyboard keeps to what it typed and looks at the field once typing
    /// pauses. Only an emptied field is taken at once: the message was sent, the next one starts
    /// afresh (with a capital).
    private func fieldReported() {
        TypingLog.mark("ô báo")
        let proxy = textDocumentProxy
        let before = proxy.documentContextBeforeInput ?? "", after = proxy.documentContextAfterInput ?? ""
        let now = CACurrentMediaTime()
        TypingLog.add("ô \(TypingLog.tail(before))|\(TypingLog.head(after))")
        guard let mine = composer.before else { return updateAutoCapitalization() }
        if editSentAt > 0, (before.isEmpty ? mine.isEmpty : mine.hasSuffix(before)) {
            slowestApp = max(slowestApp, now - editSentAt)
            editSentAt = 0
        }
        if now - lastKeyAt < Self.settle, !(before.isEmpty && after.isEmpty && !proxy.hasText) {
            composer.fieldCaughtUp(before: before, after: after, now: now)
            return scheduleCheck()
        }
        reconcile(before: before, after: after, now: now)
        if !typedSinceAppear { updateAutoCapitalization() }
    }

    private func reconcile(now: CFTimeInterval) {
        let proxy = textDocumentProxy
        reconcile(before: proxy.documentContextBeforeInput ?? "", after: proxy.documentContextAfterInput ?? "", now: now)
    }

    /// Takes the field's text if it was changed elsewhere (see `Composer.fieldChanged`).
    private func reconcile(before: String, after: String, now: CFTimeInterval) {
        guard composer.before != nil else { return }
        // Some apps now and then hand over no text at all although the field has some.
        if before.isEmpty, after.isEmpty, textDocumentProxy.hasText { return }
        guard composer.fieldChanged(before: before, after: after, now: now) else { return }
        TypingLog.add("↺ theo ô: \(TypingLog.tail(before))")
        lastTyped = nil
        updateAutoCapitalization()
    }

    /// Looks at the field once typing pauses, and again once the app must have caught up: a field
    /// still behind then means the app dropped an edit, and the keyboard follows the field rather
    /// than edit text that is not there.
    private func scheduleCheck() {
        guard !checkPending else { return }
        checkPending = true
        let wait = max(0.05, lastKeyAt + Self.settle - CACurrentMediaTime())
        DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in self?.checkField() }
    }

    private func checkField() {
        TypingLog.mark("kiểm tra ô")
        checkPending = false
        let now = CACurrentMediaTime()
        let idle = now - lastKeyAt
        if idle < Self.settle { return scheduleCheck() }
        reconcile(now: now)
        let caughtUp = TypedText.lag + 0.1
        if idle < caughtUp {
            checkPending = true
            DispatchQueue.main.asyncAfter(deadline: .now() + (caughtUp - idle)) { [weak self] in self?.checkField() }
        } else {
            if slowestApp > 0 {
                // When the app reported the text back, not when it showed it: many apps report only
                // now and then (one did 1.6 s after the last key, once the user stopped).
                TypingLog.add("⌛ app báo lại chữ chậm nhất sau \(Int(slowestApp * 1000))ms")
                slowestApp = 0
            }
            TypingLog.save()
        }
    }

    private func edited() {
        switch mode {
        case .busy:
            // The translation would replace the field, these keys with it.
            TypingLog.add("▭ gõ tiếp: hủy dịch")
            task?.cancel()
            mode = .normal
        case .done, .failed:
            // After this key's frame: building the bar again held up the first key after a translation.
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                switch self.mode {
                case .done, .failed:
                    TypingLog.add("▭ thanh dịch vẽ lại")
                    self.mode = .normal
                default: break
                }
            }
        default: break
        }
        switch info {
        case .outgoing, .message:
            // After this key's frame too: the keyboard gets shorter.
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                switch self.info {
                case .outgoing, .message:
                    TypingLog.add("ⓘ ẩn dòng báo")
                    self.info = .idle
                default: break
                }
            }
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
                on = ".!?\n".contains(last)
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
        // The field's own answer trails the keys; a message just sent with this key ends in a newline
        // and leaves the field empty.
        let typed = composer.before.map { !$0.isEmpty && $0.last != "\n" } ?? false
        let enabled = !(proxy.enablesReturnKeyAutomatically ?? false) || proxy.hasText || typed
        keyboard.configureReturn(title: title, prominent: type != .default, enabled: enabled)
    }

    // MARK: Translate bar

    private func renderBar() {
        TypingLog.mark("thanh dịch")
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
        let target = shownTarget
        let language = pill(pillTitle(target), image: "chevron.down", trailingImage: true) { [weak self] in self?.mode = .picking }
        language.accessibilityLabel = "Ngôn ngữ dịch: \(target.name)"
        // On narrow iPhones this pill gives way (and truncates) before the Dịch button does.
        language.configuration?.titleLineBreakMode = .byTruncatingTail
        language.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        languageButton = language
        return language
    }

    private func pillTitle(_ target: Language) -> String {
        targetCode == Language.autoCode ? "Tự động · \(target.label)" : "Việt → \(target.label)"
    }

    /// The pill's language now: the one chosen, or for "Tự động" the one worked out last (until
    /// then the partner language seen last, as `Language.target` falls back to).
    private var shownTarget: Language {
        guard targetCode == Language.autoCode else { return Language.named(targetCode) ?? Language.all[0] }
        return pillTarget ?? Language.named(SharedStore.lastPartnerCode) ?? Language.all[0]
    }

    /// Works out again, off the main thread, which language "Tự động" stands for: as the keyboard
    /// appears and whenever the context changed.
    private func refreshPillTarget() {
        guard targetCode == Language.autoCode else { return }
        pillTargetRequest += 1
        let request = pillTargetRequest
        Self.store.async { [weak self] in
            let target = Language.target(code: Language.autoCode, context: SharedStore.context())
            DispatchQueue.main.async {
                guard let self, request == self.pillTargetRequest else { return }
                self.pillTarget = target
                self.updateLanguagePill()
            }
        }
    }

    /// Only the pill's title: the bar is not built again.
    private func updateLanguagePill() {
        TypingLog.mark("tên ngôn ngữ")
        guard let button = languageButton, button.superview != nil else { return }
        let target = shownTarget
        let title = pillTitle(target)
        guard button.configuration?.title != title else { return }
        button.configuration?.title = title
        button.accessibilityLabel = "Ngôn ngữ dịch: \(target.name)"
    }

    // MARK: Info line

    /// Nothing to read, no line: the keyboard is just the bar and the keys.
    private var infoLineHeight: CGFloat {
        if case .idle = info { return 0 }
        return traitCollection.verticalSizeClass == .compact ? 26 : 30
    }

    /// The icon carries the colour (orange: partner, green: you, gold: check this, red: problem);
    /// the text stays in label colours so it reads on the keyboard background.
    private func renderInfo() {
        TypingLog.mark("dòng báo")
        let height = infoLineHeight
        infoButton.isHidden = height == 0
        if infoHeight?.constant != height { infoHeight?.constant = height }
        guard height > 0 else { return }

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
            return
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
            symbol = "bubble.left.fill"
            tint = Brand.orange
        case .outgoing(let translation, _):
            if let meaning = translation.meaning {
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
                self?.refreshPillTarget()
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
    /// The history and the context are read off the main thread (the history is a few hundred items).
    private func openOverview() {
        let needsHistory = lastIncoming == nil || lastOutgoing == nil
        Self.store.async {
            let recent = needsHistory ? SharedStore.recentHistory() : []
            let lines = SharedStore.context()
            DispatchQueue.main.async { [weak self] in self?.showOverview(recent: recent, lines: lines) }
        }
    }

    private func showOverview(recent: [HistoryItem], lines: [ChatLine]) {
        var parts: [(String, Bool)] = []

        if let incoming = lastIncoming {
            parts += incomingParts(translation: incoming.translation.text)
        } else if let item = recent.first(where: { $0.side == .partner }) {
            parts += incomingParts(translation: item.translation)
        }
        if let outgoing = lastOutgoing {
            let t = outgoing.translation
            parts += outgoingParts(original: outgoing.original, translation: t.text, language: t.language.name,
                                   meaning: t.meaning)
        } else if let item = recent.first(where: { $0.side == .me }) {
            parts += outgoingParts(original: item.source, translation: item.translation,
                                   language: item.language?.name ?? item.languageName, meaning: item.meaning)
        }

        parts.append((lines.isEmpty ? "Ngữ cảnh: chưa có tin nào" : "Ngữ cảnh: \(lines.count) tin gần đây", true))
        parts += lines.map { (($0.side == .partner ? "Đối tác: " : "Tôi: ") + $0.text, false) }
        showPanel(Brand.name, parts, button: "Cuộc mới (xóa ngữ cảnh)") { [weak self] in
            Self.store.async { SharedStore.clearContext() }
            self?.lastIncoming = nil
            self?.lastOutgoing = nil
            self?.refreshPillTarget()
            self?.closePanel()
        }
    }

    /// Only the translation: the partner's message itself is in the chat.
    private func incomingParts(translation: String) -> [(String, Bool)] {
        [("Tin đối tác (đã dịch)", true), (translation, false)]
    }

    private func outgoingParts(original: String, translation: String, language: String,
                               meaning: String?) -> [(String, Bool)] {
        var parts = [(language.isEmpty ? "Bạn gửi" : "Bạn gửi (\(language))", true), (original, true), ("→ " + translation, false)]
        if let meaning { parts.append(("Nghĩa: " + meaning, true)) }
        return parts
    }

    private func openOutgoingCheck() {
        guard let outgoing = lastOutgoing else { return }
        let t = outgoing.translation
        var parts = [("Bạn viết", true), (outgoing.original, false), ("Đã đổi sang \(t.language.name)", true), (t.text, false)]
        if let meaning = t.meaning { parts += [("Nghĩa khi dịch ngược", true), (meaning, false)] }
        showPanel("Kiểm tra bản dịch", parts, button: "Hoàn tác, lấy lại câu gốc") { [weak self] in self?.undo() }
    }

    /// Only the translation: the partner's message is right above in the chat.
    private func presentIncoming(source: String, translation: Translation) {
        let parts = [(translation.text, false)]
        showPanel("Tin đối tác → Tiếng Việt", parts, button: "Sao chép bản dịch") { [weak self] in
            UIPasteboard.general.string = translation.text
            SharedStore.handledPasteboard = UIPasteboard.general.changeCount
            self?.panelButton.configuration?.title = "Đã sao chép"
        }
    }

    /// A long translation of the partner's message opens in full while nothing has been typed
    /// yet: reading comes before replying, and one tap closes it.
    private func expandIfLong(source: String, translation: Translation) {
        // Not while typing has begun: the panel hides the keys, and the field may not say it has
        // text yet.
        guard mode == .normal, CACurrentMediaTime() - lastKeyAt > 1.5, !textDocumentProxy.hasText,
              !fitsInfoLine(translation.text) else { return }
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
        guard fullAccess else {
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
        // The cursor is still walking the field for an undo (the panel's Xong brings Dịch back
        // meanwhile): a second walk over the same field would read it half deleted.
        guard walking == 0, let apiKey = readyKey() else { return }
        let context = SharedStore.context()
        // The language the pill shows ("Tự động" is worked out off the main thread as the context
        // changes); only before that is done, here.
        let target = targetCode == Language.autoCode && pillTarget == nil ? resolvedTarget(context: context) : shownTarget
        activeTarget = target
        mode = .busy
        task?.cancel()
        // Where the time of a Dịch goes (reading the field, OpenAI, replacing the text), for the log.
        let tapped = CACurrentMediaTime()
        readDocument { [weak self] text in
            guard let self, self.mode == .busy, self.view.window != nil else { return }
            guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                self.mode = .failed(TranslateError.empty.errorDescription ?? "")
                return
            }
            let read = CACurrentMediaTime()
            let field = self.currentField
            self.task = Task { [weak self] in
                do {
                    let result = try await Translator(apiKey: apiKey)
                        .translate(text, to: target, context: context, checkMeaning: SharedStore.checkMeaning)
                    let answered = CACurrentMediaTime()
                    guard let self, !Task.isCancelled else { return }
                    // Another text field has the keyboard now: its text is not the one translated.
                    guard self.currentField == field else {
                        self.mode = .failed(Self.fieldSwitched)
                        return
                    }
                    self.original = text
                    // In the history first: hidden while the field is being replaced, the keyboard
                    // stops, and the translation is still there to copy from the app.
                    let item = HistoryItem(side: .me, source: text, translation: result.text, languageCode: target.code,
                                           meaning: result.meaning, note: result.note)
                    Self.store.async { SharedStore.addHistory(item) }
                    self.replaceDocument(with: result.text) { [weak self] in
                        TypingLog.mark("kết quả dịch")
                        TypingLog.add("⇄ Dịch \(text.count)→\(result.text.count) ký tự: đọc ô \(Int((read - tapped) * 1000))ms"
                                      + " · OpenAI \(Int((answered - read) * 1000))ms · thay ô \(Int((CACurrentMediaTime() - answered) * 1000))ms")
                        let line = ChatLine(side: .me, text: result.text, date: Date())
                        Self.store.async { SharedStore.remember(line) }
                        self?.pasteboardAtOutgoing = self?.seenPasteboard
                        self?.refreshPillTarget()
                        self?.lastOutgoing = (result, text)
                        self?.mode = .done
                        self?.info = .outgoing(result, original: text)
                    }
                } catch is CancellationError {
                } catch {
                    TypingLog.add("⇄ Dịch lỗi sau \(Int((CACurrentMediaTime() - tapped) * 1000))ms: \(error.localizedDescription)")
                    self?.mode = .failed(error.localizedDescription)
                }
            }
        }
    }

    private static let fieldSwitched = "Đã sang ô nhập khác. Bấm Dịch lại."

    private func undo() {
        // Replacing the field takes a moment and the Hoàn tác button stays where it is: a second
        // tap started a second walk, and both put the original back ("câu gốc câu gốc").
        guard walking == 0 else { return }
        panelButton.configuration?.title = "Đang lấy lại…"
        if let sent = lastOutgoing?.translation.text {
            Self.store.async { SharedStore.forgetLast(sent) }
            refreshPillTarget()
        }
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
        beginWalk()

        func finish() {
            TypingLog.add("▤ đọc ô: \(collected.count) ký tự, \(steps) bước")
            let before = proxy.documentContextBeforeInput ?? "", after = proxy.documentContextAfterInput ?? ""
            proxy.adjustTextPosition(byCharacterOffset: collected.utf16.count)
            // Back at the end, as the app sees it, before the keys held meanwhile go in there.
            afterMove(from: before, after, waitingUpTo: collected.isEmpty ? 1 : 12) { _ in
                self.endWalk()
                completion(collected)
            }
        }

        func back() {
            steps += 1
            let before = proxy.documentContextBeforeInput ?? ""
            let after = proxy.documentContextAfterInput ?? ""
            if !before.isEmpty, steps < 80 {
                collected = before + collected
                proxy.adjustTextPosition(byCharacterOffset: -before.utf16.count)
                // A field that never reports the move would hand the same text over again: what was
                // read is kept, once.
                return afterMove(from: before, after, waitingUpTo: 40) { moved in moved ? back() : finish() }
            }
            // Nothing before the cursor means the start of the field, or of a paragraph: step
            // over one character and see whether the field moved.
            proxy.adjustTextPosition(byCharacterOffset: -1)
            afterMove(from: before, after, waitingUpTo: 4) { moved in
                if moved, steps < 80 {
                    collected = "\n" + collected
                    return back()
                }
                finish()
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
            let before = proxy.documentContextBeforeInput ?? ""
            let after = proxy.documentContextAfterInput ?? ""
            if !after.isEmpty, steps < 80 {
                proxy.adjustTextPosition(byCharacterOffset: after.utf16.count)
                return afterMove(from: before, after, waitingUpTo: 40) { moved in moved ? forward() : completion() }
            }
            proxy.adjustTextPosition(byCharacterOffset: 1)
            afterMove(from: before, after, waitingUpTo: 4) { moved in
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
        beginWalk()
        func clear() {
            steps += 1
            let before = proxy.documentContextBeforeInput ?? ""
            let after = proxy.documentContextAfterInput ?? ""
            emptyRounds = before.isEmpty && after.isEmpty ? emptyRounds + 1 : 0
            guard proxy.hasText, emptyRounds < 3, steps < 80 else {
                proxy.insertText(text)
                // The field is read again before the next key.
                composer.forget()
                completion()
                return endWalk()
            }
            for _ in 0..<max(1, before.count) { proxy.deleteBackward() }
            tick(clear)
        }
        moveToEnd(clear)
    }

    /// After moving the cursor: runs `work` once the field reports other text around the cursor (the
    /// move has reached the app), or after `ticks` × 25 ms without (it did not move: an end of the
    /// field). Apps report the new place a moment later. Read at once, Zalo now and then still gave
    /// the old text, which was read twice: the message went to translation as "X X" and came back
    /// translated twice.
    private func afterMove(from before: String, _ after: String, waitingUpTo ticks: Int, _ work: @escaping (Bool) -> Void) {
        tick {
            let proxy = self.textDocumentProxy
            let moved = (proxy.documentContextBeforeInput ?? "") != before || (proxy.documentContextAfterInput ?? "") != after
            if moved || ticks <= 1 { return work(moved) }
            self.afterMove(from: before, after, waitingUpTo: ticks - 1, work)
        }
    }

    private func tick(_ work: @escaping () -> Void) {
        let generation = walkGeneration
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.025) { [weak self] in
            guard let self, generation == self.walkGeneration else { return }
            TypingLog.mark("đọc/thay ô")
            work()
        }
    }

    private func beginWalk() {
        walking += 1
        composer.forget()
        // Holding a key now must not take back text where the walk has gone.
        lastTyped = nil
    }

    /// The cursor is back at the end: the keys pressed meanwhile go in, in order.
    private func endWalk() {
        walking = max(0, walking - 1)
        guard walking == 0, !heldKeys.isEmpty else { return }
        let keys = heldKeys
        heldKeys = []
        TypingLog.add("▤ phím chờ lúc đọc/thay ô: \(keys.count)")
        for action in keys { keyboardLayoutView(keyboard, perform: action) }
    }

    // MARK: Translating the partner's messages

    /// Looks for a newly copied message. Asking the clipboard goes to another process, so it is
    /// done off the main thread, and not while the user is typing: the keys always come first.
    /// The change count and `hasStrings` never ask for paste permission; only reading the text does.
    private func checkPasteboard(waitForTyping: Bool) {
        // Typing comes first: nothing is asked while keys are being pressed.
        if waitForTyping && CACurrentMediaTime() - lastKeyAt < 1.5 { return }
        guard fullAccess, !checkingPasteboard, SharedStore.apiKey != nil else { return }
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
        TypingLog.mark("clipboard")
        seenPasteboard = changeCount
        // The user started typing while the clipboard was asked: the info line would appear or go
        // and change the keyboard's height under their thumbs. The next check (a second on) offers it.
        if CACurrentMediaTime() - lastKeyAt < 1.5 { return }
        switch info {
        case .idle, .incoming, .outgoing:
            guard fresh else { return }
            // The meaning of the message just translated stays: only something copied after it
            // takes its place.
            if case .outgoing = info, changeCount == pasteboardAtOutgoing { return }
            if SharedStore.instantIncoming, attemptedPasteboard != changeCount {
                attemptedPasteboard = changeCount
                translateCopied(automatically: true)
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
    private func translateCopied(automatically: Bool = false) {
        guard readyKey() != nil else { return }
        if case .panel = mode { mode = .normal }
        info = .incomingBusy
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            // Both ask the clipboard's process: not on the main thread.
            let changeCount = UIPasteboard.general.changeCount
            let copied = UIPasteboard.general.string?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            DispatchQueue.main.async { self?.received(copied: copied, changeCount: changeCount, automatically: automatically) }
        }
    }

    /// A password, a code, a link or an e-mail address: one word of plain letters with digits or
    /// signs in it. Copied, it is not a message, and instant mode does not send it to OpenAI by
    /// itself (a tap still translates it).
    static func looksLikeSecret(_ text: String) -> Bool {
        text.count >= 6 && text.unicodeScalars.allSatisfy { $0.isASCII && !CharacterSet.whitespacesAndNewlines.contains($0) }
            && text.contains { $0.isNumber || "@/:._-#$%&*+=\\|~^".contains($0) }
    }

    private func received(copied: String, changeCount: Int, automatically: Bool) {
        // Nothing read usually means paste was not allowed; keep offering it so it can be retried.
        guard !copied.isEmpty else {
            info = .message("Chưa đọc được tin đã sao chép. Khi iOS hỏi, chọn “Cho phép dán”.")
            return
        }
        if automatically, Self.looksLikeSecret(copied) {
            info = .copied
            return
        }
        // Whatever was copied is translated into Vietnamese: no guessing whether it already is
        // (a foreign message that names a Vietnamese person read as Vietnamese).
        if let cached = SharedStore.cachedIncoming(copied) {
            SharedStore.handledPasteboard = changeCount
            TypingLog.add("⇄ Tin đối tác \(copied.count) ký tự: đã dịch trước đó")
            Self.store.async { SharedStore.remember(ChatLine(side: .partner, text: copied, date: Date())) }
            refreshPillTarget()
            return show(incoming: copied, translation: Translation(text: cached, meaning: nil, note: nil, language: .vietnamese))
        }
        // The context as it was before this message: the message itself goes in <message>, not twice
        // (copied again, it is the context's last line already).
        Self.store.async {
            var context = SharedStore.context()
            if context.last?.side == .partner, context.last?.text == copied { context.removeLast() }
            SharedStore.remember(ChatLine(side: .partner, text: copied, date: Date()))
            DispatchQueue.main.async { [weak self] in
                self?.refreshPillTarget()
                self?.translateIncoming(copied, context: context, changeCount: changeCount)
            }
        }
    }

    /// The copy counts as handled once translated: one that failed (no network) is offered again.
    private func translateIncoming(_ text: String, context: [ChatLine], changeCount: Int) {
        guard let apiKey = SharedStore.apiKey else { return }
        info = .incomingBusy
        incomingTask?.cancel()
        let started = CACurrentMediaTime()
        incomingTask = Task { [weak self] in
            do {
                let result = try await Translator(apiKey: apiKey).translate(text, to: .vietnamese, context: context)
                TypingLog.add("⇄ Tin đối tác \(text.count)→\(result.text.count) ký tự: OpenAI \(Int((CACurrentMediaTime() - started) * 1000))ms")
                // Language detection and the history's JSON: not on the main thread, where the user
                // may already be typing the reply.
                let translation = result.text, note = result.note
                Self.store.async {
                    SharedStore.cacheIncoming(text, translation: translation)
                    SharedStore.addHistory(HistoryItem(side: .partner, source: text, translation: translation,
                                                       languageCode: LanguageDetector.language(of: text)?.code ?? "",
                                                       note: note))
                }
                guard let self, !Task.isCancelled else { return }
                SharedStore.handledPasteboard = changeCount
                self.show(incoming: text, translation: result)
            } catch is CancellationError {
            } catch {
                TypingLog.add("⇄ Tin đối tác lỗi sau \(Int((CACurrentMediaTime() - started) * 1000))ms: \(error.localizedDescription)")
                self?.info = .message(error.localizedDescription)
            }
        }
    }

    private func show(incoming source: String, translation: Translation) {
        TypingLog.mark("tin đối tác")
        lastIncoming = (source, translation)
        info = .incoming(source: source, translation: translation)
        expandIfLong(source: source, translation: translation)
    }
}

/// Lets keys play the standard keyboard click.
final class KeyboardInputView: UIInputView, UIInputViewAudioFeedback {
    var enableInputClicksWhenVisible: Bool { true }
}
