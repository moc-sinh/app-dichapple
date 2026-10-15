import SwiftUI

struct GlossaryTerm: Identifiable, Equatable {
    var id = UUID()
    var source: String
    var target: String
}

/// Stored as text, one term per line ("主轴 = trục chính"), which is what the translator reads.
enum Glossary {
    static func parse(_ text: String) -> [GlossaryTerm] {
        text.split(whereSeparator: \.isNewline).compactMap { line in
            let parts = line.split(separator: "=", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
            guard let source = parts.first, !source.isEmpty else { return nil }
            return GlossaryTerm(source: source, target: parts.count > 1 ? parts[1] : "")
        }
    }

    static func format(_ terms: [GlossaryTerm]) -> String {
        terms.map { $0.target.isEmpty ? $0.source : "\($0.source) = \($0.target)" }.joined(separator: "\n")
    }
}

/// Product names, trade terms and names that must always be translated the same way.
struct GlossaryView: View {
    @AppStorage(SharedStore.Key.glossary, store: SharedStore.defaults) private var glossary = ""
    @State private var terms: [GlossaryTerm] = []
    @State private var editing: GlossaryTerm?
    @State private var query = ""

    private var shown: [GlossaryTerm] {
        guard !query.isEmpty else { return terms }
        return terms.filter { $0.source.localizedCaseInsensitiveContains(query) || $0.target.localizedCaseInsensitiveContains(query) }
    }

    var body: some View {
        NavigationStack {
            List {
                if terms.isEmpty {
                    ContentUnavailableView {
                        Label("Chưa có thuật ngữ", systemImage: "character.book.closed")
                    } description: {
                        Text("Thêm tên sản phẩm, thuật ngữ ngành, tên riêng… Mọi bản dịch, cả trên bàn phím, sẽ luôn dùng đúng cách dịch bạn chọn.")
                    } actions: {
                        Button("Thêm thuật ngữ") { editing = GlossaryTerm(source: "", target: "") }
                            .buttonStyle(.borderedProminent)
                            .tint(.brandGreenFill)
                    }
                    .listRowBackground(Color.clear)
                } else if shown.isEmpty {
                    ContentUnavailableView.search(text: query)
                        .listRowBackground(Color.clear)
                } else {
                    Section {
                        ForEach(shown) { term in
                            Button { editing = term } label: { TermRow(term: term) }
                                .foregroundStyle(.primary)
                        }
                        .onDelete { offsets in
                            let ids = Set(offsets.map { shown[$0].id })
                            terms.removeAll { ids.contains($0.id) }
                            save()
                        }
                    } footer: {
                        Text("\(terms.count) thuật ngữ, dùng cho cả tin bạn gửi và tin đối tác.")
                    }
                }
            }
            .searchable(text: $query, prompt: "Tìm thuật ngữ")
            .navigationTitle("Thuật ngữ")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { editing = GlossaryTerm(source: "", target: "") } label: {
                        Label("Thêm thuật ngữ", systemImage: "plus")
                    }
                }
            }
            .sheet(item: $editing) { term in
                TermEditor(term: term, isNew: !terms.contains { $0.id == term.id }) { saved in
                    if let index = terms.firstIndex(where: { $0.id == saved.id }) {
                        terms[index] = saved
                    } else {
                        terms.insert(saved, at: 0)
                    }
                    save()
                }
            }
        }
        .onAppear { terms = Glossary.parse(glossary) }
    }

    private func save() {
        glossary = Glossary.format(terms)
    }
}

struct TermRow: View {
    let term: GlossaryTerm

    var body: some View {
        HStack(spacing: 10) {
            Text(term.source)
                .fontWeight(.medium)
            Image(systemName: "arrow.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.brandGreen)
                .accessibilityHidden(true)
            Text(term.target.isEmpty ? "Giữ nguyên" : term.target)
                .foregroundStyle(term.target.isEmpty ? .secondary : .primary)
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(term.target.isEmpty ? "\(term.source), giữ nguyên" : "\(term.source) dịch là \(term.target)")
    }
}

struct TermEditor: View {
    @State var term: GlossaryTerm
    let isNew: Bool
    var onSave: (GlossaryTerm) -> Void
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Ví dụ: 主轴, spindle, CNC", text: $term.source)
                        .focused($focused)
                        .autocorrectionDisabled()
                } header: {
                    Text("Từ hoặc cụm từ")
                }
                Section {
                    TextField("Ví dụ: trục chính", text: $term.target)
                        .autocorrectionDisabled()
                } header: {
                    Text("Luôn dịch thành")
                } footer: {
                    Text("Để trống nếu muốn giữ nguyên, ví dụ tên thương hiệu.")
                }
            }
            .navigationTitle(isNew ? "Thêm thuật ngữ" : "Sửa thuật ngữ")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Hủy") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Lưu") {
                        term.source = term.source.trimmingCharacters(in: .whitespacesAndNewlines)
                        term.target = term.target.trimmingCharacters(in: .whitespacesAndNewlines)
                        onSave(term)
                        dismiss()
                    }
                    .disabled(term.source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .onAppear { focused = isNew }
        }
        .presentationDetents([.medium])
    }
}
