import SwiftUI

/// Translate anything here; the keyboard does the same inside chat apps.
struct TranslateView: View {
    var showSettings: () -> Void

    private enum Direction { case outgoing, incoming }

    @AppStorage(SharedStore.Key.targetCode, store: SharedStore.defaults) private var targetCode = Language.autoCode
    @AppStorage(SharedStore.Key.checkMeaning, store: SharedStore.defaults) private var checkMeaning = true
    @Environment(\.scenePhase) private var scenePhase
    @FocusState private var editing: Bool

    @State private var direction = Direction.outgoing
    @State private var text = ""
    @State private var result: Translation?
    @State private var resultSide = ChatLine.Side.me
    @State private var resultLanguage = ""
    @State private var error: String?
    @State private var busy = false
    @State private var task: Task<Void, Never>?
    @State private var copied = false
    @State private var setup = SetupStatus.current

    init(showSettings: @escaping () -> Void) {
        self.showSettings = showSettings
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    if !setup.done { setupCard }
                    directionBar
                    inputCard
                    if busy { busyCard }
                    if let error { errorCard(error) }
                    if let result { resultCard(result) }
                    if result == nil && error == nil && !busy { tipCard }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(Color(.systemGroupedBackground))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) { BrandTitle() }
            }
        }
        .onAppear { setup = .current }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { setup = .current }
        }
    }

    // MARK: Direction

    private var directionBar: some View {
        HStack(spacing: 10) {
            if direction == .outgoing {
                fixedSide("Tiếng Việt")
                swapButton
                targetMenu
            } else {
                fixedSide("Tiếng đối tác")
                swapButton
                fixedSide("Tiếng Việt")
            }
        }
    }

    private func fixedSide(_ title: String) -> some View {
        Text(title)
            .font(.subheadline.weight(.semibold))
            .lineLimit(1)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private var targetMenu: some View {
        Menu {
            Picker("Dịch sang", selection: $targetCode) {
                Text("Tự động theo đối tác").tag(Language.autoCode)
                ForEach(Language.all, id: \.code) { Text("\($0.label) · \($0.name)").tag($0.code) }
            }
        } label: {
            HStack(spacing: 4) {
                Text(targetTitle).lineLimit(1)
                Image(systemName: "chevron.down").font(.caption.weight(.bold))
            }
            .font(.subheadline.weight(.semibold))
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .contentShape(Rectangle())
        }
        .accessibilityLabel("Dịch sang: \(targetTitle)")
    }

    private var swapButton: some View {
        Button {
            withAnimation(.snappy) { direction = direction == .outgoing ? .incoming : .outgoing }
            result = nil
            error = nil
        } label: {
            Image(systemName: "arrow.left.arrow.right")
                .font(.body.weight(.semibold))
                .frame(width: 44, height: 44)
                .background(Color(.secondarySystemGroupedBackground), in: Circle())
        }
        .accessibilityLabel(direction == .outgoing ? "Đổi sang dịch tin của đối tác" : "Đổi sang dịch tin bạn gửi")
    }

    private var targetTitle: String {
        targetCode == Language.autoCode ? "Tự động · \(resolvedTarget.label)" : resolvedTarget.label
    }

    /// "Tự động" answers in the partner's language, as on the keyboard.
    private var resolvedTarget: Language {
        if targetCode == Language.autoCode {
            return LanguageDetector.partnerLanguage(in: SharedStore.context())
                ?? Language.named(SharedStore.lastPartnerCode) ?? Language.all[0]
        }
        return Language.named(targetCode) ?? Language.all[0]
    }

    // MARK: Input

    private var inputCard: some View {
        Card {
            ZStack(alignment: .topLeading) {
                if text.isEmpty {
                    Text(direction == .outgoing ? "Gõ tin bằng tiếng Việt…" : "Dán tin của đối tác…")
                        .foregroundStyle(.tertiary)
                        .padding(.top, 8)
                        .padding(.leading, 5)
                        .accessibilityHidden(true)
                }
                TextEditor(text: $text)
                    .focused($editing)
                    .scrollContentBackground(.hidden)
                    .frame(minHeight: 112)
                    .accessibilityLabel(direction == .outgoing ? "Tin bạn muốn gửi" : "Tin của đối tác")
            }
            HStack(spacing: 10) {
                // The system paste button reads the clipboard without asking for permission.
                PasteButton(payloadType: String.self) { strings in
                    guard let pasted = strings.first else { return }
                    Task { @MainActor in
                        text = pasted
                        result = nil
                        error = nil
                    }
                }
                .labelStyle(.iconOnly)
                .buttonBorderShape(.circle)
                if !text.isEmpty {
                    Button {
                        text = ""
                        result = nil
                        error = nil
                    } label: {
                        Image(systemName: "xmark")
                    }
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.circle)
                    .accessibilityLabel("Xóa chữ")
                }
                Spacer()
                Button(action: translate) {
                    Label("Dịch", systemImage: "translate")
                        .fontWeight(.semibold)
                        .padding(.horizontal, 6)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.capsule)
                .tint(.brandGreenFill)
                .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || busy)
            }
        }
    }

    // MARK: Result

    private var busyCard: some View {
        Card {
            HStack(spacing: 12) {
                ProgressView()
                Text(direction == .outgoing ? "Đang dịch sang \(resolvedTarget.name)…" : "Đang dịch sang tiếng Việt…")
                    .foregroundStyle(.secondary)
                Spacer()
                Button("Hủy") {
                    task?.cancel()
                    busy = false
                }
            }
        }
    }

    private func errorCard(_ message: String) -> some View {
        Card(accent: .red) {
            Label(message, systemImage: "exclamationmark.circle.fill")
                .foregroundStyle(.red)
            if SharedStore.apiKey == nil {
                Button("Dán API key trong Cài đặt", action: showSettings)
                    .buttonStyle(.bordered)
            }
        }
    }

    private func resultCard(_ result: Translation) -> some View {
        Card(accent: resultSide.color) {
            HStack(spacing: 8) {
                SideTag(side: resultSide, language: resultLanguage)
                Spacer()
                ShareLink(item: result.text) {
                    Image(systemName: "square.and.arrow.up")
                }
                .buttonStyle(.bordered)
                .buttonBorderShape(.circle)
                .accessibilityLabel("Chia sẻ bản dịch")
                Button {
                    Clipboard.copy(result.text)
                    copied = true
                    Task {
                        try? await Task.sleep(for: .seconds(1.5))
                        copied = false
                    }
                } label: {
                    Label(copied ? "Đã chép" : "Sao chép", systemImage: copied ? "checkmark" : "doc.on.doc")
                }
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
                .sensoryFeedback(.success, trigger: copied) { _, new in new }
            }
            Text(result.text)
                .font(.title3)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let meaning = result.meaning {
                VStack(alignment: .leading, spacing: 4) {
                    Label("Đối tác sẽ hiểu là", systemImage: "checkmark.circle.fill")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Color.brandGreen)
                    Text(meaning)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                }
            }
            if let note = result.note { NoteBox(text: note) }
        }
    }

    // MARK: Guidance

    private var setupCard: some View {
        Card(accent: .brandGold) {
            Label("Còn \(setup.remaining) bước để dùng bàn phím", systemImage: "wrench.and.screwdriver.fill")
                .font(.headline)
            Text(setup.keyboardReady
                 ? "Dán API key OpenAI để bàn phím dịch được."
                 : "Thêm bàn phím MSTrans, bật “Cho phép truy cập đầy đủ”" + (setup.hasKey ? "." : " và dán API key OpenAI."))
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Button("Hoàn tất cài đặt", action: showSettings)
                .buttonStyle(.borderedProminent)
                .tint(.brandGreenFill)
        }
    }

    private var tipCard: some View {
        Card {
            Label("Dùng ngay trong Zalo, WeChat, Messenger", systemImage: "keyboard")
                .font(.subheadline.weight(.semibold))
            Text("Chạm quả địa cầu để chuyển sang bàn phím MSTrans, gõ tiếng Việt rồi bấm Dịch. Tin của đối tác hiện bằng tiếng Việt ở dòng trên cùng của bàn phím.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }

    // MARK: Translating

    private func translate() {
        let source = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !source.isEmpty else { return }
        guard let apiKey = SharedStore.apiKey else {
            error = TranslateError.noKey.errorDescription
            return
        }
        editing = false
        let outgoing = direction == .outgoing
        let target = outgoing ? resolvedTarget : Language.vietnamese
        let sourceLanguage = outgoing ? nil : LanguageDetector.language(of: source)
        let context = SharedStore.context()
        busy = true
        error = nil
        result = nil
        task?.cancel()
        task = Task {
            do {
                let translation = try await Translator(apiKey: apiKey)
                    .translate(source, to: target, context: context, checkMeaning: outgoing && checkMeaning)
                guard !Task.isCancelled else { return }
                let item = HistoryItem(side: outgoing ? .me : .partner, source: source, translation: translation.text,
                                       languageCode: outgoing ? target.code : sourceLanguage?.code ?? "",
                                       meaning: translation.meaning, note: translation.note)
                SharedStore.addHistory(item)
                resultSide = item.side
                resultLanguage = item.languageName
                result = translation
            } catch is CancellationError {
            } catch {
                self.error = error.localizedDescription
            }
            busy = false
        }
    }
}
