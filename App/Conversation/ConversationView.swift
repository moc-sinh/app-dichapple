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
    @State private var enlarged: ConversationModel.Entry?

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
            .onChange(of: model.talking) { _, talking in
                // Ended by itself (two minutes) or by stopping: the next tap starts a new turn.
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

    private var transcript: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 10) {
                    if model.entries.isEmpty { hint }
                    ForEach(model.entries) { entry in
                        ConversationEntryRow(entry: entry,
                                             retry: { model.retry(entry.id) },
                                             repeatAloud: { model.repeatAloud(entry.id) },
                                             enlarge: { enlarged = entry })
                            .id(entry.id)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
            }
            .onChange(of: model.entries.last) { _, last in
                guard let last else { return }
                withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(last.id, anchor: .bottom) }
            }
        }
    }

    private var hint: some View {
        Card {
            Label("Cách dùng", systemImage: "info.circle.fill")
                .font(.headline)
                .foregroundStyle(Color.brandGreen)
            Text("Bấm **Bắt đầu nghe** rồi đặt iPhone giữa bàn. Mọi người nói gì, bạn đọc bằng tiếng Việt ở đây.")
            if model.readAloud {
                Text("**Đọc to + chữ**: bản dịch tiếng Việt còn được đọc to qua loa. Hợp nói chuyện 1–1: lúc điện thoại đang đọc, app tạm không nghe.")
            } else {
                Text("**Chỉ chữ**: chỉ hiện bản dịch, không đọc. Hợp cuộc họp nhiều người nói liên tục.")
            }
            Text("Muốn nói: **giữ nút Tôi nói** trong lúc nói (hoặc chạm một lần, nói xong chạm lần nữa). Câu của bạn được dịch sang \(model.partnerLanguage.name) và đọc to qua loa cho mọi người nghe.")
            if model.engine == .live {
                Text("Cách dịch: Dịch liền, chữ chạy ngay khi người khác đang nói. Tốn hơn, khoảng 0,034 USD mỗi phút nghe.")
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
            .disabled(model.running || model.meeting.lines.isEmpty)
            Button {
                showingSaved = true
            } label: {
                Label("Cuộc họp đã lưu", systemImage: "folder")
            }
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
            return model.meeting.lines.isEmpty ? "Chưa nghe" : "Đã dừng · \(model.meeting.lines.count) câu trong biên bản"
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
                .font(entry.mine ? Font.title3.weight(.medium) : Font.title3)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        if !original.isEmpty {
            Text(original)
                .font(.subheadline)
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

    private func progress(_ text: String) -> some View {
        HStack(spacing: 8) {
            ProgressView()
            Text(text).font(.subheadline).foregroundStyle(.secondary)
        }
    }

    private var who: String {
        if entry.mine { return "Tôi → \(Meeting.languageName(entry.target))" }
        if entry.language.isEmpty { return "Đang nghe" }
        return Meeting.languageName(entry.language)
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
            Section("Biên bản") {
                ForEach(Array(meeting.lines.enumerated()), id: \.offset) { _, line in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(line.mine ? "Tôi" : Meeting.languageName(line.language))
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
            meeting.summary = try await ConversationModel.summarize(meeting)
            MeetingStore.save(meeting)
        } catch {
            problem = error.localizedDescription
        }
    }
}
