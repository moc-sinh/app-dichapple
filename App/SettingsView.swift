import SwiftUI

struct SettingsView: View {
    @AppStorage(SharedStore.Key.apiKey, store: SharedStore.defaults) private var apiKey = ""
    @AppStorage(SharedStore.Key.model, store: SharedStore.defaults) private var model = TranslationModel.sol.rawValue
    @AppStorage(SharedStore.Key.targetCode, store: SharedStore.defaults) private var targetCode = Language.autoCode
    @AppStorage(SharedStore.Key.tone, store: SharedStore.defaults) private var tone = Tone.polite.rawValue
    @AppStorage(SharedStore.Key.checkMeaning, store: SharedStore.defaults) private var checkMeaning = true
    @AppStorage(SharedStore.Key.gender, store: SharedStore.defaults) private var gender = 0
    @AppStorage(SharedStore.Key.addressing, store: SharedStore.defaults) private var addressing = ""
    @AppStorage(SharedStore.Key.profile, store: SharedStore.defaults) private var profile = ""
    @AppStorage(SharedStore.Key.autoIncoming, store: SharedStore.defaults) private var autoIncoming = true
    @AppStorage(SharedStore.Key.haptics, store: SharedStore.defaults) private var haptics = true
    @AppStorage(SharedStore.keepHistoryKey, store: SharedStore.defaults) private var keepHistory = true
    @Environment(\.scenePhase) private var scenePhase
    @State private var setup = SetupStatus.current
    @State private var testResult: (ok: Bool, text: String)?
    @State private var testing = false
    @State private var contextCleared = false

    var body: some View {
        NavigationStack {
            Form {
                setupSection
                openAISection
                outgoingSection
                aboutYouSection
                incomingSection
                keyboardSection
                dataSection
                aboutSection
            }
            .navigationTitle("Cài đặt")
        }
        .onAppear(perform: refresh)
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { refresh() }
        }
        .onChange(of: apiKey) { _, _ in
            setup = .current
            testResult = nil
        }
    }

    // MARK: Sections

    private var setupSection: some View {
        Section {
            StepRow(done: setup.keyboardAdded || setup.keyboardReady, title: "Thêm bàn phím MSTrans",
                    detail: "Cài đặt › Cài đặt chung › Bàn phím › Bàn phím › Thêm bàn phím mới › MSTrans")
            StepRow(done: setup.keyboardReady, title: "Bật “Cho phép truy cập đầy đủ”",
                    detail: "Chạm MSTrans trong danh sách bàn phím rồi bật. Cần để gửi câu bạn bấm Dịch tới OpenAI.")
            StepRow(done: setup.hasKey, title: "Dán API key OpenAI", detail: "Ở mục OpenAI ngay bên dưới.")
            StepRow(done: nil, title: "Cho phép dán (nên bật)",
                    detail: "Lần đầu dịch tin đã sao chép, chọn “Cho phép dán”. Để không bị hỏi lại: Cài đặt › MSTrans › Dán từ ứng dụng khác › Cho phép.")
            Button("Mở Cài đặt iPhone", action: openSystemSettings)
            NavigationLink {
                DiagnosticsView()
            } label: {
                Label("Chẩn đoán bàn phím", systemImage: "stethoscope")
            }
        } header: {
            Text("Bắt đầu")
        } footer: {
            Text("Bước 2 được đánh dấu xong sau khi bạn mở bàn phím MSTrans lần đầu. Bàn phím không hiện? Mở Chẩn đoán bàn phím và gửi báo cáo.")
        }
    }

    private var openAISection: some View {
        Section {
            SecureField("sk-...", text: $apiKey)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Picker("Chế độ dịch", selection: $model) {
                ForEach(TranslationModel.allCases, id: \.rawValue) { Text($0.label).tag($0.rawValue) }
            }
            Button(action: test) {
                HStack {
                    Text(testing ? "Đang kiểm tra…" : "Kiểm tra kết nối")
                    if testing { Spacer(); ProgressView() }
                }
            }
            .disabled(apiKey.isEmpty || testing)
            if let testResult {
                Label(testResult.text, systemImage: testResult.ok ? "checkmark.circle.fill" : "xmark.octagon.fill")
                    .font(.footnote)
                    .foregroundStyle(testResult.ok ? Color.brandGreen : .red)
            }
        } header: {
            Text("OpenAI")
        } footer: {
            Text("“Chuẩn nhất” dùng GPT-6.1 Sol, khoảng 0,003 USD mỗi tin. “Nhanh” dùng GPT-6 Luna, rẻ hơn khoảng 20 lần. Key chỉ lưu trên iPhone này.")
        }
    }

    private var outgoingSection: some View {
        Section {
            Picker("Dịch sang", selection: $targetCode) {
                Text("Tự động theo đối tác").tag(Language.autoCode)
                ForEach(Language.all, id: \.code) { Text("\($0.label) · \($0.name)").tag($0.code) }
            }
            Picker("Giọng văn", selection: $tone) {
                ForEach(Tone.allCases, id: \.rawValue) { Text($0.label).tag($0.rawValue) }
            }
            .pickerStyle(.segmented)
            Toggle("Kiểm tra nghĩa (dịch ngược)", isOn: $checkMeaning)
        } header: {
            Text("Tin bạn gửi")
        } footer: {
            Text("“Tự động” trả lời đúng ngôn ngữ đối tác đang dùng. Kiểm tra nghĩa cho bạn đọc lại bằng tiếng Việt câu đối tác sẽ nhận.")
        }
    }

    private var aboutYouSection: some View {
        Section {
            Picker("Giới tính", selection: $gender) {
                Text("Không nêu").tag(0)
                Text("Nam").tag(1)
                Text("Nữ").tag(2)
            }
            .pickerStyle(.segmented)
            TextField("Xưng hô, ví dụ: tôi xưng em, gọi đối tác là anh", text: $addressing, axis: .vertical)
                .lineLimit(1...3)
            TextField("Về bạn, ví dụ: Minh, phụ trách mua hàng, nhập máy CNC từ Trung Quốc", text: $profile, axis: .vertical)
                .lineLimit(1...4)
        } header: {
            Text("Về bạn")
        } footer: {
            Text("Giúp bản dịch xưng hô đúng và dùng đúng tiểu từ lịch sự (tiếng Thái, tiếng Nhật…). Thuật ngữ ngành: thêm ở tab Thuật ngữ.")
        }
    }

    private var incomingSection: some View {
        Section {
            Toggle("Mời dịch tin vừa sao chép", isOn: $autoIncoming)
        } header: {
            Text("Tin đối tác gửi")
        } footer: {
            Text("Nhấn giữ tin của đối tác › Sao chép, rồi chạm vào ô trả lời: dòng trên cùng của bàn phím hiện “Có tin vừa sao chép · chạm để dịch”. Khi tắt, dùng nút “Tin copy” trên thanh dịch.")
        }
    }

    private var keyboardSection: some View {
        Section {
            Toggle("Rung khi gõ", isOn: $haptics)
        } header: {
            Text("Bàn phím")
        } footer: {
            Text("Giữ một nguyên âm để chọn dấu. Giữ phím cách rồi kéo để di chuyển con trỏ. Chạm 2 lần Shift để khóa chữ hoa, 2 lần phím cách để thêm dấu chấm. Chạm logo MSTrans trên bàn phím để xem ngữ cảnh đang dùng.")
        }
    }

    private var dataSection: some View {
        Section {
            Toggle("Lưu lịch sử dịch", isOn: $keepHistory)
            Button(contextCleared ? "Đã xóa ngữ cảnh" : "Xóa ngữ cảnh đang nhớ") {
                SharedStore.clearContext()
                contextCleared = true
            }
            .disabled(contextCleared)
        } header: {
            Text("Dữ liệu")
        } footer: {
            Text("Ngữ cảnh là vài tin gần nhất gửi kèm để dịch đúng xưng hô; tự hết hạn sau 45 phút. Lịch sử và key chỉ lưu trên iPhone này; OpenAI không lưu nội dung.")
        }
    }

    private var aboutSection: some View {
        Section {
            HStack(spacing: 14) {
                Image("Logo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 44, height: 44)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(Brand.name).font(.headline)
                    Text("Phiên bản \(Self.version) · Mộc Sinh").font(.subheadline).foregroundStyle(.secondary)
                }
            }
            .accessibilityElement(children: .combine)
        }
    }

    private static var version: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(short) (\(build))"
    }

    // MARK: Actions

    private func refresh() {
        setup = .current
        contextCleared = false
    }

    private func test() {
        testing = true
        testResult = nil
        let translator = Translator(apiKey: apiKey.trimmingCharacters(in: .whitespacesAndNewlines),
                                    model: TranslationModel(rawValue: model) ?? .sol)
        Task {
            do {
                let result = try await translator.translate("Anh gửi giúp em báo giá trước thứ Sáu nhé.",
                                                            to: Language.named("en") ?? Language.all[0], checkMeaning: true)
                testResult = (true, "Kết nối ổn: \(result.text)")
            } catch {
                testResult = (false, error.localizedDescription)
            }
            testing = false
        }
    }
}

/// One setup step. `done == nil` is advice we cannot check from the app.
struct StepRow: View {
    let done: Bool?
    let title: String
    let detail: String

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(detail).font(.footnote).foregroundStyle(.secondary)
            }
        } icon: {
            Image(systemName: done == true ? "checkmark.circle.fill" : (done == nil ? "info.circle" : "circle"))
                .foregroundStyle(done == true ? Color.brandGreen : Color.secondary)
        }
        .accessibilityElement(children: .combine)
        .accessibilityValue(done == true ? "Đã xong" : (done == nil ? "" : "Chưa xong"))
    }
}
