import SwiftUI

/// The Hội thoại tab: what the others say comes up as Vietnamese text, read aloud too with Đọc to;
/// the Tôi nói button turns the user's Vietnamese into the partners' language, read aloud.
struct ConversationView: View {
    @State private var model = ConversationModel()
    @State private var pressedAt: Date?
    /// A tap (not a hold) started the user's turn: the next tap ends it.
    @State private var latched = false
    @State private var showingSaved = false
    @State private var showingSummary = false
    @State private var editingTopic = false
    @State private var topicDraft = ""
    @State private var enlarged: ConversationModel.Entry?
    /// The transcript keeps its last sentence in view, until the user scrolls up to read.
    @State private var following = true
    @State private var unseen = false

    private static let bottom = "bottom"

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                header
                transcript
                controls
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Hội thoại")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { moreMenu }
            }
            .sheet(isPresented: $showingSaved) { SavedMeetingsView() }
            .sheet(isPresented: $showingSummary) { SummarySheet(model: model) }
            .fullScreenCover(item: $enlarged) { entry in EnlargedTranslation(entry: entry) }
            .alert("Chủ đề cuộc họp", isPresented: $editingTopic) {
                TextField("Ví dụ: báo giá máy CNC với nhà cung cấp Thâm Quyến", text: $topicDraft)
                Button("Lưu") { model.setTopic(topicDraft) }
                Button("Hủy", role: .cancel) {}
            } message: {
                Text("Vài chữ về nội dung, đối tác, sản phẩm: giúp nghe và dịch đúng thuật ngữ, đúng ngữ cảnh. "
                     + "GPT dùng ngay; Soniox dùng từ lần bấm nghe sau. Để trống nếu không cần.")
            }
            .onChange(of: model.talking) { _, talking in
                // Ended by stopping: the next tap starts a new turn.
                if !talking, !model.starting { latched = false }
            }
        }
    }

    // MARK: Header

    private var header: some View {
        VStack(spacing: 10) {
            Picker("Lời đối phương", selection: $model.readAloud) {
                Text("Đọc to + chữ").tag(true)
                Text("Chỉ chữ").tag(false)
            }
            .pickerStyle(.segmented)
            HStack(spacing: 8) {
                Text("Tiếng của đối tác")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Spacer()
                partnerMenu
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 4)
    }

    private var partnerMenu: some View {
        Menu {
            Picker("Tiếng của đối tác", selection: $model.partnerCode) {
                Text("Tự động (theo người vừa nói)").tag(Language.autoCode)
                ForEach(Language.all, id: \.code) { Text("\($0.label) · \($0.name)").tag($0.code) }
            }
        } label: {
            HStack(spacing: 4) {
                Text(partnerTitle).lineLimit(1)
                Image(systemName: "chevron.down").font(.caption.weight(.bold))
            }
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 12)
            .frame(minHeight: 36)
            .background(Color(.secondarySystemGroupedBackground), in: Capsule())
        }
        .accessibilityLabel("Tiếng của đối tác: \(partnerTitle)")
    }

    private var partnerTitle: String {
        model.partnerCode == Language.autoCode ? "Tự động · \(model.partnerLanguage.label)" : model.partnerLanguage.label
    }

    // MARK: Transcript

    /// The others' sentences come up once they are recognised: a cough or a chair, dropped then,
    /// never flashes up as a card. Meanwhile one line at the bottom says something is coming.
    private var transcript: some View {
        let shown = model.entries.filter { $0.mine || $0.state != .recognizing }
        let pending = model.entries.count - shown.count
        return ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 10) {
                    if model.entries.isEmpty { hint }
                    ForEach(shown) { entry in
                        ConversationEntryRow(entry: entry,
                                             size: model.textSize,
                                             retry: { model.retry(entry.id) },
                                             repeatAloud: { model.repeatAloud(entry.id) },
                                             enlarge: { enlarged = entry })
                            .id(entry.id)
                    }
                    if pending > 0 {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text(pending > 1 ? "Đang nhận \(pending) câu vừa nói…" : "Đang nhận câu vừa nói…")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 4)
                    }
                    Color.clear.frame(height: 1).id(Self.bottom)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
            }
            .modifier(FollowsBottom(following: $following))
            .onChange(of: TranscriptEnd(last: shown.last, count: shown.count, pending: pending)) { _, _ in
                if following {
                    withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(Self.bottom, anchor: .bottom) }
                } else {
                    unseen = true
                }
            }
            .onChange(of: following) { _, now in
                if now { unseen = false }
            }
            .onAppear { proxy.scrollTo(Self.bottom, anchor: .bottom) }
            .overlay(alignment: .bottom) {
                if unseen, !following {
                    Button {
                        following = true
                        withAnimation(.easeOut(duration: 0.25)) { proxy.scrollTo(Self.bottom, anchor: .bottom) }
                    } label: {
                        Label("Câu mới", systemImage: "arrow.down")
                            .font(.subheadline.weight(.semibold))
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .foregroundStyle(.white)
                            .background(Color.brandGreenFill, in: Capsule())
                    }
                    .padding(.bottom, 8)
                    .transition(.opacity)
                }
            }
        }
    }

    private var hint: some View {
        Card {
            Label("Cách dùng", systemImage: "info.circle.fill")
                .font(.headline)
                .foregroundStyle(Color.brandGreen)
            Text("Bấm **nút tròn bên trái** (sóng âm) để bắt đầu nghe, rồi đặt iPhone giữa bàn. Mọi người nói gì, bạn đọc bằng tiếng Việt ở đây.")
            if model.readAloud {
                Text("**Đọc to + chữ**: bản dịch tiếng Việt còn được đọc to qua loa. Khi nhiều người nói liên tục, giọng đọc nhanh hơn và bỏ qua câu đã cũ để theo kịp; chữ vẫn hiện đủ.")
                    + Text(model.engine == .accurate ? " Lúc điện thoại đang đọc, Chính xác tạm không nghe." : "")
            } else {
                Text("**Chỉ chữ**: chỉ hiện bản dịch, không đọc. Hợp cuộc họp nhiều người nói liên tục.")
                    + Text(model.engine == .soniox ? " Micro nghe người ngồi xa rõ hơn ở chế độ này." : "")
            }
            Text("Muốn nói: **giữ nút Tôi nói** trong lúc nói (hoặc chạm một lần, nói xong chạm lần nữa). Câu của bạn được dịch sang \(model.partnerLanguage.name) và đọc to qua loa cho mọi người nghe.")
            if model.engine == .soniox {
                Text(SharedStore.apiKey == nil
                     ? "Cách dịch: Soniox, chữ và bản dịch chạy ngay khi mọi người đang nói, hết câu là đọc. Đổi trong menu ⋯."
                     : "Cách dịch: Soniox, chữ chạy ngay khi mọi người đang nói, tách câu theo từng người; hết câu GPT dịch lại cho chuẩn rồi đọc. Đổi trong menu ⋯.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if SharedStore.sonioxKey == nil {
                    NoteBox(text: "Chưa có key Soniox. Dán key trong Cài đặt › Soniox.")
                }
            } else if model.engine == .live {
                Text("Cách dịch: Dịch liền, chữ và giọng chạy ngay khi người khác đang nói; hết câu GPT dịch lại chữ cho chuẩn. Đổi trong menu ⋯.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if !model.liveSpeaksPartner {
                    NoteBox(text: "Dịch liền chưa đọc được \(model.partnerLanguage.name). Muốn nói, đổi Cách dịch sang Chính xác (menu ⋯).")
                }
            } else {
                Text("Cách dịch: Chính xác, chữ hiện sau mỗi câu, dùng từ điển thuật ngữ và ngữ cảnh cuộc họp. Đổi trong menu ⋯.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            if GoogleVoice.name(of: model.voice) != nil, SharedStore.googleKey == nil {
                NoteBox(text: "Chưa có key Google nên đang đọc bằng giọng iPhone. Dán key trong Cài đặt › Giọng Google.")
            }
            Text(model.engine == .soniox
                 ? "Chi phí \(SharedStore.apiKey == nil ? "Soniox" : "Soniox và OpenAI") với lựa chọn hiện tại: \(CostMeter.text(hourlyCost)) mỗi giờ họp."
                 : "Chi phí OpenAI với lựa chọn hiện tại: \(CostMeter.text(hourlyCost)) mỗi giờ có người nói. Khoảng lặng không tính tiền.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .font(.body)
    }

    // MARK: Controls

    private var controls: some View {
        VStack(spacing: 12) {
            if let problem = model.problem {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "exclamationmark.circle.fill").foregroundStyle(.red)
                    Text(problem).font(.subheadline)
                    Spacer(minLength: 0)
                    if model.micDenied {
                        Button("Mở Cài đặt") { openSystemSettings() }.font(.subheadline.weight(.semibold))
                    }
                    Button {
                        model.problem = nil
                    } label: {
                        Image(systemName: "xmark").font(.caption.weight(.bold)).foregroundStyle(.secondary)
                    }
                    .accessibilityLabel("Đóng thông báo")
                }
                .padding(12)
                .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            HStack(spacing: 14) {
                ListenButton(model: model)
                talkButton
            }
            StatusLine(model: model)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 8)
        .background(.bar)
    }

    private var talkDisabled: Bool { model.engine == .live && !model.liveSpeaksPartner }

    private var hourlyCost: Double {
        CostMeter.perHour(live: model.engine == .live, soniox: model.engine == .soniox,
                          translatesAgain: model.engine == .live || (model.engine == .soniox && SharedStore.apiKey != nil),
                          model: model.quality.model,
                          readAloud: model.readAloud, openAIVoice: ConversationModel.isOpenAIVoice(model.voice))
    }

    private var talkButton: some View {
        HStack(spacing: 10) {
            Image(systemName: model.talking ? "mic.fill" : "mic")
                .font(.title2.weight(.semibold))
            VStack(alignment: .leading, spacing: 2) {
                Text(model.talking ? "Đang nghe bạn…" : "Tôi nói")
                    .font(.headline)
                Text(model.talking ? (latched ? "Nói xong, chạm để dịch" : "Nói xong, thả tay để dịch")
                                   : "Giữ để nói · sang \(model.partnerLanguage.label)")
                    .font(.caption)
                    .lineLimit(1)
                    .opacity(0.9)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 18)
        .frame(maxWidth: .infinity, minHeight: 64)
        .foregroundStyle(.white)
        .background(model.talking ? Color.brandOrange : Color.brandGreenFill, in: Capsule())
        .opacity(talkDisabled ? 0.4 : 1)
        .contentShape(Capsule())
        .gesture(talkGesture, including: talkDisabled ? .none : .all)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(model.talking ? "Đang nghe bạn nói. Chạm hai lần để dịch" : "Tôi nói, sang \(model.partnerLanguage.name)")
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { model.talk(!model.talking) }
    }

    /// Hold to speak and let go to translate; or tap, speak, and tap again.
    private var talkGesture: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { _ in
                guard pressedAt == nil else { return }
                pressedAt = Date()
                // The second tap of a tapped turn ends it when the finger lifts.
                if latched, model.talking || model.starting { return }
                latched = false
                model.talk(true)
            }
            .onEnded { _ in
                let held = Date().timeIntervalSince(pressedAt ?? Date())
                pressedAt = nil
                if latched {
                    latched = false
                    model.talk(false)
                } else if held < 0.35 {
                    latched = true
                } else {
                    model.talk(false)
                }
            }
    }

    // MARK: Menu

    private var moreMenu: some View {
        Menu {
            Picker(selection: $model.engine) {
                ForEach(ConversationModel.Engine.allCases) { Text($0.title).tag($0) }
            } label: {
                Label("Cách dịch", systemImage: "bolt.horizontal.circle")
            }
            .pickerStyle(.menu)
            .disabled(model.running || model.starting)
            Picker(selection: $model.quality) {
                ForEach(ConversationModel.Quality.allCases) { Text($0.title).tag($0) }
            } label: {
                Label("Dịch lời đối phương", systemImage: "dollarsign.circle")
            }
            .pickerStyle(.menu)
            Button {
                topicDraft = model.topic
                editingTopic = true
            } label: {
                Label(model.topic.isEmpty ? "Chủ đề cuộc họp…" : "Chủ đề: \(model.topic)", systemImage: "text.bubble")
            }
            Picker(selection: $model.silence) {
                ForEach(ConversationModel.silenceChoices, id: \.self) { seconds in
                    Text(String(format: "%.1f giây", seconds).replacingOccurrences(of: ".", with: ",")).tag(seconds)
                }
            } label: {
                Label("Ngừng bao lâu là hết câu", systemImage: "pause.circle")
            }
            .pickerStyle(.menu)
            Picker(selection: $model.voice) {
                ForEach(ConversationModel.voices, id: \.id) { Text($0.name).tag($0.id) }
            } label: {
                Label("Giọng đọc", systemImage: "speaker.wave.2")
            }
            .pickerStyle(.menu)
            Picker(selection: $model.textSize) {
                ForEach(ConversationModel.TextSize.allCases) { Text($0.title).tag($0) }
            } label: {
                Label("Cỡ chữ", systemImage: "textformat.size")
            }
            .pickerStyle(.menu)
            Divider()
            Button {
                showingSummary = true
                Task { await model.summarizeCurrent() }
            } label: {
                Label("Tóm tắt cuộc họp", systemImage: "list.bullet.rectangle")
            }
            .disabled(model.meeting.lines.isEmpty)
            ShareLink(item: model.meeting.transcript) {
                Label("Chia sẻ biên bản", systemImage: "square.and.arrow.up")
            }
            .disabled(model.meeting.lines.isEmpty)
            Button {
                model.newMeeting()
            } label: {
                Label("Cuộc họp mới", systemImage: "plus.bubble")
            }
            .disabled(!model.canStartNewMeeting)
            Button {
                showingSaved = true
            } label: {
                Label("Cuộc họp đã lưu", systemImage: "folder")
            }
            Button {
                Clipboard.copyPrivately(model.speedLogText)
            } label: {
                Label("Sao chép nhật ký tốc độ", systemImage: "stopwatch")
            }
            Divider()
            Button {} label: {
                Label("Cuộc họp này: \(CostMeter.text(model.spent)) (ước tính)", systemImage: "chart.bar")
            }
            .disabled(true)
        } label: {
            Image(systemName: "ellipsis.circle")
        }
        .accessibilityLabel("Tùy chọn")
    }
}

/// Start and stop listening; the ring follows the room's loudness. Its own view, so the meter
/// (ten times a second) does not draw the whole transcript again.
struct ListenButton: View {
    let model: ConversationModel

    var body: some View {
        Button {
            if model.running { model.stop() } else { Task { await model.start() } }
        } label: {
            ZStack {
                Circle().fill(model.running ? Color.red : Color.brandGreenFill)
                Circle()
                    .stroke(Color.white.opacity(0.7), lineWidth: 3)
                    .padding(4)
                    .scaleEffect(0.8 + model.level * 0.2)
                    .opacity(model.running ? 1 : 0)
                if model.starting {
                    ProgressView().tint(.white)
                } else {
                    Image(systemName: model.running ? "stop.fill" : "waveform")
                        .font(.title2.weight(.semibold))
                        .foregroundStyle(.white)
                }
            }
            .frame(width: 64, height: 64)
            .animation(.easeOut(duration: 0.1), value: model.level)
        }
        .accessibilityLabel(model.running ? "Dừng nghe" : "Bắt đầu nghe")
    }
}

/// What is happening, with the time listened.
struct StatusLine: View {
    let model: ConversationModel

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            Text(text(at: context.date))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity)
                .lineLimit(1)
        }
    }

    private func text(at now: Date) -> String {
        if model.starting { return "Đang mở micro…" }
        guard model.running, let started = model.startedAt else {
            return model.meeting.lines.isEmpty ? "Bấm nút tròn để bắt đầu nghe"
                : "Đã dừng · \(model.meeting.lines.count) câu · bấm nút tròn để nghe tiếp"
        }
        if model.interrupted { return "Tạm dừng: micro đang bận (cuộc gọi)" }
        if model.speakingMine { return "Đang đọc câu của bạn cho đối tác…" }
        if model.speaking { return "Đang đọc bản dịch…" }
        if model.talking { return "Đang nghe bạn nói" }
        let seconds = max(0, Int(now.timeIntervalSince(started)))
        let time = String(format: "%d:%02d", seconds / 60, seconds % 60)
        return model.hearsSpeech ? "Đang nghe · \(time) · có người đang nói" : "Đang nghe · \(time)"
    }
}

/// One sentence: the Vietnamese to read (or, for the user's own, what the partners heard).
struct ConversationEntryRow: View {
    let entry: ConversationModel.Entry
    var size: ConversationModel.TextSize = .medium
    var retry: () -> Void
    var repeatAloud: () -> Void
    var enlarge: () -> Void

    var body: some View {
        Card(accent: accent) {
            HStack(spacing: 6) {
                Label(who, systemImage: entry.mine ? "person.wave.2.fill" : "person.2.fill")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(accent)
                Spacer()
                Text(entry.at, style: .time)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            content
        }
        .contentShape(Rectangle())
        .onTapGesture {
            if entry.mine, !entry.translation.isEmpty { enlarge() }
        }
    }

    @ViewBuilder private var content: some View {
        let main = entry.translation.isEmpty ? entry.text : entry.translation
        let original = entry.translation.isEmpty ? "" : entry.text
        if !main.isEmpty {
            Text(main)
                .font(entry.mine ? mainFont.weight(.medium) : mainFont)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        if !original.isEmpty {
            Text(original)
                .font(originalFont)
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
        }
        switch entry.state {
        case .recognizing:
            progress(entry.mine ? "Đang nhận câu của bạn…" : "Đang nghe…")
        case .translating:
            progress(entry.mine ? "Đang dịch sang \(Meeting.languageName(entry.target))…" : "Đang dịch…")
        case .hearing:
            EmptyView()
        case .failed(let message):
            HStack {
                Label(message, systemImage: "exclamationmark.circle.fill")
                    .font(.subheadline)
                    .foregroundStyle(.red)
                Spacer()
                Button("Thử lại", action: retry)
                    .font(.subheadline.weight(.semibold))
            }
        case .done:
            if !entry.translation.isEmpty {
                HStack {
                    Button(action: repeatAloud) {
                        Label("Đọc lại", systemImage: "speaker.wave.2.fill")
                    }
                    Spacer()
                    if entry.mine {
                        Button(action: enlarge) {
                            Label("Hiện to", systemImage: "arrow.up.left.and.arrow.down.right")
                        }
                    }
                }
                .font(.subheadline.weight(.semibold))
                .buttonStyle(.borderless)
                .tint(entry.mine ? Color.brandGreen : Color.brandOrange)
            }
        }
    }

    private var mainFont: Font {
        switch size {
        case .medium: return .title3
        case .large: return .title2
        case .extraLarge: return .title
        }
    }

    private var originalFont: Font {
        switch size {
        case .medium: return .subheadline
        case .large: return .body
        case .extraLarge: return .title3
        }
    }

    private func progress(_ text: String) -> some View {
        HStack(spacing: 8) {
            ProgressView()
            Text(text).font(.subheadline).foregroundStyle(.secondary)
        }
    }

    private var who: String {
        if entry.mine { return "Tôi → \(Meeting.languageName(entry.target))" }
        let speaker = entry.speaker.isEmpty ? "" : "Người \(entry.speaker) · "
        if entry.language.isEmpty { return speaker + "Đang nghe" }
        return speaker + Meeting.languageName(entry.language)
    }

    /// Green: the user. Orange: a partner. Grey: someone speaking Vietnamese.
    private var accent: Color {
        if entry.mine { return .brandGreen }
        return entry.isVietnamese ? .secondary : .brandOrange
    }
}

/// The user's translation in large type, to show the partner across the table.
struct EnlargedTranslation: View {
    let entry: ConversationModel.Entry
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack {
                Text(Meeting.languageName(entry.target))
                    .font(.headline)
                    .foregroundStyle(.secondary)
                Spacer()
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark.circle.fill").font(.title).foregroundStyle(.secondary)
                }
                .accessibilityLabel("Đóng")
            }
            Spacer()
            Text(entry.translation)
                .font(.system(size: 44, weight: .semibold))
                .minimumScaleFactor(0.3)
                .frame(maxWidth: .infinity, alignment: .leading)
            Spacer()
            Text(entry.text)
                .font(.body)
                .foregroundStyle(.secondary)
        }
        .padding(24)
        .background(Color(.systemBackground))
        .contentShape(Rectangle())
        .onTapGesture { dismiss() }
    }
}

/// The current meeting's summary.
struct SummarySheet: View {
    let model: ConversationModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if model.summarizing {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("Đang tóm tắt cuộc họp…").foregroundStyle(.secondary)
                        }
                    } else if let summary = model.meeting.summary {
                        Text(summary).textSelection(.enabled)
                    } else if let problem = model.problem {
                        Label(problem, systemImage: "exclamationmark.circle.fill").foregroundStyle(.red)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
            }
            .navigationTitle("Tóm tắt")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Xong") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    if let summary = model.meeting.summary {
                        ShareLink(item: summary) { Image(systemName: "square.and.arrow.up") }
                    }
                }
            }
        }
    }
}

/// Meetings kept on the phone, newest first.
struct SavedMeetingsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var meetings: [Meeting] = []
    @State private var loaded = false

    var body: some View {
        NavigationStack {
            List {
                if loaded, meetings.isEmpty {
                    Text("Chưa có cuộc họp nào được lưu.").foregroundStyle(.secondary)
                }
                ForEach(meetings) { meeting in
                    NavigationLink {
                        MeetingDetailView(meeting: meeting)
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(meeting.title).font(.headline)
                            Text("\(meeting.length) · \(meeting.lines.count) câu")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                            if let first = meeting.lines.first {
                                Text(first.mine || first.translation.isEmpty ? first.text : first.translation)
                                    .font(.subheadline)
                                    .lineLimit(1)
                            }
                        }
                    }
                }
                .onDelete { offsets in
                    for index in offsets { MeetingStore.delete(meetings[index]) }
                    meetings.remove(atOffsets: offsets)
                }
            }
            .navigationTitle("Cuộc họp đã lưu")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Xong") { dismiss() } }
            }
            .task {
                meetings = await Task.detached { MeetingStore.all() }.value
                loaded = true
            }
        }
    }
}

struct MeetingDetailView: View {
    @State var meeting: Meeting
    @State private var summarizing = false
    @State private var problem: String?

    var body: some View {
        List {
            Section("Tóm tắt") {
                if let summary = meeting.summary {
                    Text(summary).textSelection(.enabled)
                } else if summarizing {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("Đang tóm tắt…").foregroundStyle(.secondary)
                    }
                } else {
                    Button("Tóm tắt cuộc họp này") { Task { await summarize() } }
                }
                if let problem {
                    Text(problem).foregroundStyle(.red)
                }
            }
            if let cost = meeting.cost {
                Section {
                    Text("Chi phí OpenAI ước tính: \(CostMeter.text(cost.dollars))")
                        .foregroundStyle(.secondary)
                }
            }
            Section("Biên bản") {
                ForEach(Array(meeting.lines.enumerated()), id: \.offset) { _, line in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(line.who)
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(line.mine ? Color.brandGreen : Color.brandOrange)
                            Spacer()
                            Text(line.at, style: .time).font(.caption).foregroundStyle(.secondary)
                        }
                        Text(line.translation.isEmpty ? line.text : line.translation)
                        if !line.translation.isEmpty {
                            Text(line.text).font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                    .textSelection(.enabled)
                }
            }
        }
        .navigationTitle(meeting.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                ShareLink(item: meeting.transcript) { Image(systemName: "square.and.arrow.up") }
            }
        }
    }

    private func summarize() async {
        summarizing = true
        problem = nil
        defer { summarizing = false }
        do {
            let summary = try await ConversationModel.summarize(meeting)
            meeting.summary = summary.text
            var cost = meeting.cost ?? CostMeter()
            cost.used(summary.usage, by: summary.model)
            meeting.cost = cost
            MeetingStore.save(meeting)
        } catch {
            problem = error.localizedDescription
        }
    }
}

/// What the transcript's end shows: when it changes, the transcript follows it down.
private struct TranscriptEnd: Equatable {
    let last: ConversationModel.Entry?
    let count: Int
    let pending: Int
}

/// Follows the transcript's end while it is in view; stops once the user scrolls up to read
/// (and starts again when they scroll back down). Before iOS 18 it always follows.
private struct FollowsBottom: ViewModifier {
    @Binding var following: Bool
    @State private var userScrolling = false

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content
                .onScrollPhaseChange { _, phase in
                    userScrolling = phase == .tracking || phase == .interacting || phase == .decelerating
                }
                .onScrollGeometryChange(for: Bool.self) { geometry in
                    geometry.visibleRect.maxY >= geometry.contentSize.height - 80
                } action: { _, atBottom in
                    if atBottom {
                        following = true
                    } else if userScrolling {
                        following = false
                    }
                }
        } else {
            content
        }
    }
}
