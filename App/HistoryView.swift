import SwiftUI

/// Everything translated with the keyboard or in the app, newest first.
struct HistoryView: View {
    private enum Filter: String, CaseIterable, Identifiable {
        case all = "Tất cả", sent = "Đã gửi", received = "Đã nhận", starred = "Đánh dấu"
        var id: Self { self }
    }

    @AppStorage(SharedStore.keepHistoryKey, store: SharedStore.defaults) private var keepHistory = true
    @Environment(\.scenePhase) private var scenePhase
    @State private var items: [HistoryItem] = []
    @State private var query = ""
    @State private var filter = Filter.all
    @State private var selected: HistoryItem?
    @State private var confirmClear = false

    private var shown: [HistoryItem] {
        items.filter { item in
            let kept = switch filter {
            case .all: true
            case .sent: item.side == .me
            case .received: item.side == .partner
            case .starred: item.starred
            }
            return kept && (query.isEmpty
                || item.source.localizedCaseInsensitiveContains(query)
                || item.translation.localizedCaseInsensitiveContains(query))
        }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker("Lọc", selection: $filter) {
                        ForEach(Filter.allCases) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())
                }
                if shown.isEmpty {
                    emptyState
                        .listRowBackground(Color.clear)
                } else {
                    Section {
                        ForEach(shown) { item in
                            Button { selected = item } label: { HistoryRow(item: item) }
                                .foregroundStyle(.primary)
                                .swipeActions(edge: .trailing) {
                                    Button(role: .destructive) { delete(item) } label: { Label("Xóa", systemImage: "trash") }
                                }
                                .swipeActions(edge: .leading) {
                                    Button { toggleStar(item) } label: {
                                        Label(item.starred ? "Bỏ đánh dấu" : "Đánh dấu", systemImage: item.starred ? "star.slash" : "star.fill")
                                    }
                                    .tint(.brandGold)
                                }
                                .contextMenu {
                                    Button { Clipboard.copy(item.translation) } label: { Label("Chép bản dịch", systemImage: "doc.on.doc") }
                                    Button { Clipboard.copy(item.source) } label: { Label("Chép tin gốc", systemImage: "doc.on.doc") }
                                    Button { toggleStar(item) } label: {
                                        Label(item.starred ? "Bỏ đánh dấu" : "Đánh dấu", systemImage: item.starred ? "star.slash" : "star")
                                    }
                                    Button(role: .destructive) { delete(item) } label: { Label("Xóa", systemImage: "trash") }
                                }
                        }
                    } footer: {
                        if !keepHistory {
                            Text("Đang tắt lưu lịch sử: tin mới không được lưu. Bật lại trong Cài đặt.")
                        }
                    }
                }
            }
            .searchable(text: $query, prompt: "Tìm trong lịch sử")
            .navigationTitle("Lịch sử")
            .toolbar {
                if !items.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            Button(role: .destructive) { confirmClear = true } label: {
                                Label("Xóa lịch sử", systemImage: "trash")
                            }
                        } label: {
                            Label("Thêm", systemImage: "ellipsis.circle")
                        }
                    }
                }
            }
            .confirmationDialog("Xóa lịch sử?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("Xóa, giữ tin đã đánh dấu", role: .destructive) { clear() }
            } message: {
                Text("Không hoàn tác được.")
            }
            .sheet(item: $selected) { item in
                HistoryDetail(item: item, onChange: replace, onDelete: { delete(item) })
            }
        }
        .onAppear(perform: reload)
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { reload() }
        }
    }

    @ViewBuilder private var emptyState: some View {
        if !query.isEmpty {
            ContentUnavailableView.search(text: query)
        } else if items.isEmpty {
            ContentUnavailableView {
                Label("Chưa có bản dịch", systemImage: "clock.arrow.circlepath")
            } description: {
                Text(keepHistory
                     ? "Tin bạn dịch bằng bàn phím hoặc trong app sẽ hiện ở đây."
                     : "Lưu lịch sử đang tắt. Bật trong Cài đặt để giữ các bản dịch.")
            }
        } else {
            ContentUnavailableView {
                Label(filter == .starred ? "Chưa đánh dấu tin nào" : "Không có tin nào", systemImage: filter == .starred ? "star" : "tray")
            } description: {
                Text(filter == .starred ? "Vuốt sang phải trên một tin để đánh dấu, giữ lại các câu hay dùng." : "Thử bộ lọc khác.")
            }
        }
    }

    private func reload() {
        items = SharedStore.history()
    }

    private func replace(_ item: HistoryItem) {
        guard let index = items.firstIndex(where: { $0.id == item.id }) else { return }
        items[index] = item
        SharedStore.updateHistory(item)
    }

    private func toggleStar(_ item: HistoryItem) {
        var changed = item
        changed.starred.toggle()
        replace(changed)
    }

    private func delete(_ item: HistoryItem) {
        items.removeAll { $0.id == item.id }
        SharedStore.removeHistory(id: item.id)
    }

    private func clear() {
        items.removeAll { !$0.starred }
        SharedStore.saveHistory(items)
    }
}

/// Vietnamese first (what the user reads), the other language under it.
struct HistoryRow: View {
    let item: HistoryItem

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Capsule()
                .fill(item.side.color)
                .frame(width: 4)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    SideTag(side: item.side, language: item.languageName)
                    Spacer(minLength: 4)
                    if item.starred {
                        Image(systemName: "star.fill")
                            .font(.caption)
                            .foregroundStyle(Color.brandGold)
                            .accessibilityLabel("Đã đánh dấu")
                    }
                    Text(item.date, format: .relative(presentation: .named))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Text(item.vietnamese)
                    .lineLimit(3)
                Text(item.foreign)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

struct HistoryDetail: View {
    @State var item: HistoryItem
    var onChange: (HistoryItem) -> Void
    var onDelete: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(item.source).textSelection(.enabled)
                    Button { Clipboard.copy(item.source) } label: { Label("Chép", systemImage: "doc.on.doc") }
                } header: {
                    Text(item.side == .me ? "Bạn viết" : "Đối tác viết" + (item.languageName.isEmpty ? "" : " · \(item.languageName)"))
                }
                Section {
                    Text(item.translation).textSelection(.enabled)
                    Button { Clipboard.copy(item.translation) } label: { Label("Chép", systemImage: "doc.on.doc") }
                } header: {
                    Text(item.side == .me ? "Đã dịch sang \(item.languageName.isEmpty ? "ngôn ngữ đối tác" : item.languageName)" : "Bản dịch tiếng Việt")
                }
                if let meaning = item.meaning {
                    Section("Đối tác sẽ hiểu là") { Text(meaning).textSelection(.enabled) }
                }
                if let note = item.note {
                    Section { NoteBox(text: note).listRowInsets(EdgeInsets()) }
                }
                Section {
                    Button(role: .destructive) {
                        onDelete()
                        dismiss()
                    } label: {
                        Label("Xóa khỏi lịch sử", systemImage: "trash")
                    }
                }
            }
            .navigationTitle(item.date.formatted(date: .abbreviated, time: .shortened))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        item.starred.toggle()
                        onChange(item)
                    } label: {
                        Label(item.starred ? "Bỏ đánh dấu" : "Đánh dấu", systemImage: item.starred ? "star.fill" : "star")
                    }
                    .tint(.brandGold)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Xong") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
