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
    @AppStorage(SharedStore.Key.instantIncoming, store: SharedStore.defaults) private var instantIncoming = false
    @AppStorage(SharedStore.Key.haptics, store: SharedStore.defaults) private var haptics = true
    @AppStorage(SharedStore.Key.typesOnTouchDown, store: SharedStore.defaults) private var typesOnTouchDown = true
    @AppStorage(SharedStore.Key.keySounds, store: SharedStore.defaults) private var keySounds = true
    @AppStorage(SharedStore.Key.keyPopups, store: SharedStore.defaults) private var keyPopups = false
    @AppStorage(SharedStore.Key.keyboardSize, store: SharedStore.defaults) private var keyboardSize = KeyboardSize.medium.rawValue
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
                zaloSection
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
                    detail: "Cài đặt › MSTrans › Dán từ ứng dụng khác › Cho phép: iOS không hỏi lại, và bạn bật được “Dịch ngay khi mở bàn phím” ở mục Tin đối tác gửi.")
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
        // A partner message copied again is translated again, with these details.
        .onChange(of: gender) { _, _ in SharedStore.clearIncomingCache() }
        .onChange(of: addressing) { _, _ in SharedStore.clearIncomingCache() }
        .onChange(of: profile) { _, _ in SharedStore.clearIncomingCache() }
    }

    private var incomingSection: some View {
        Section {
            Toggle("Dịch ngay khi mở bàn phím", isOn: $instantIncoming)
            if instantIncoming {
                Button("Cho phép dán trong Cài đặt iPhone", action: openSystemSettings)
            }
        } header: {
            Text("Tin đối tác gửi")
        } footer: {
            if instantIncoming {
                Text("Sao chép tin rồi chạm vào ô trả lời: bản dịch hiện ngay ở dòng trên cùng của bàn phím, không cần chạm thêm. Để iOS không hỏi mỗi lần, đặt Cài đặt iPhone › MSTrans › Dán từ ứng dụng khác › Cho phép.")
            } else {
                Text("Nhấn giữ tin của đối tác › Sao chép, rồi chạm vào ô trả lời: dòng trên cùng của bàn phím hiện “Có tin vừa sao chép · chạm để dịch”, chạm một lần. Bật “Dịch ngay” để bỏ cả cái chạm đó.")
            }
        }
    }

    /// An app opened before MSTrans was installed keeps its old list of keyboards until it restarts.
    /// The copy-and-Back-Tap shortcut works everywhere, even where the keyboard can't be used.
    private var zaloSection: some View {
        Section {
            StepRow(done: nil, title: "Không thấy MSTrans khi giữ quả địa cầu",
                    detail: "App đã mở từ trước khi cài MSTrans (hay gặp với Zalo) vẫn giữ danh sách bàn phím cũ. Vuốt lên từ cạnh dưới, gạt app đó đi rồi mở lại. Vẫn chưa có thì khởi động lại iPhone.")
            StepRow(done: nil, title: "1. Tạo phím tắt",
                    detail: "App Phím tắt › + › thêm thao tác “Dịch tin đã sao chép” của MSTrans › Xong.")
            StepRow(done: nil, title: "2. Gán vào chạm lưng iPhone",
                    detail: "Cài đặt › Trợ năng › Cảm ứng › Chạm vào mặt sau › Chạm hai lần › chọn phím tắt vừa tạo.")
            StepRow(done: nil, title: "Đọc tin đối tác",
                    detail: "Giữ tin › Sao chép › gõ hai lần vào lưng iPhone: bản dịch tiếng Việt hiện lên.")
            StepRow(done: nil, title: "Trả lời",
                    detail: "Gõ tiếng Việt bằng bàn phím iPhone › chọn hết › Cắt › gõ hai lần vào lưng iPhone › giữ ô chat › Dán.")
            Button("Mở app Phím tắt", action: openShortcutsApp)
        } header: {
            Text("Zalo và các app khác")
        } footer: {
            Text("Chạm lưng iPhone dịch được ở mọi app, cả khi không mở bàn phím: tin của đối tác được dịch sang tiếng Việt, câu tiếng Việt của bạn được dịch sang tiếng đối tác và chép sẵn để dán.")
        }
    }

    private var keyboardSection: some View {
        Section {
            Toggle("Rung khi gõ", isOn: $haptics)
            Toggle("Âm thanh khi gõ", isOn: $keySounds)
            Toggle("Ra chữ ngay khi chạm phím", isOn: $typesOnTouchDown)
            Toggle("Chữ nổi khi bấm phím", isOn: $keyPopups)
            Picker("Cỡ bàn phím", selection: $keyboardSize) {
                ForEach(KeyboardSize.allCases, id: \.rawValue) { Text($0.label).tag($0.rawValue) }
            }
        } header: {
            Text("Bàn phím")
        } footer: {
            Text("Âm thanh khi gõ: tiếng click của MSTrans, tắt được ở đây (gạt nút im lặng của iPhone cũng tắt). Ra chữ ngay khi chạm: chữ vào ô nhập lúc ngón tay vừa chạm phím, không chờ nhấc tay, nên gõ nhanh thấy chữ hiện tức thì. Tắt để giống bàn phím Apple (chữ vào khi nhấc tay, trượt sang phím khác trước khi nhấc để đổi chữ). Chữ nổi khi bấm phím: chữ to hiện phía trên phím đang bấm, như bàn phím Apple khi bật Xem trước ký tự; tắt thì phím chỉ sáng lên, không che các phím bên cạnh. Cỡ bàn phím: mặc định mỗi phím nằm đúng chỗ như bàn phím iPhone, nên ngón tay quen tay bấm trúng; hai cỡ còn lại cho phím cao hơn. Đổi xong, mở lại bàn phím là thấy. Giữ một nguyên âm để chọn dấu. Giữ phím cách rồi kéo để di chuyển con trỏ. Chạm 2 lần Shift để khóa chữ hoa, 2 lần phím cách để thêm dấu chấm. Chạm logo MSTrans trên bàn phím để xem tin đối tác, câu vừa dịch và ngữ cảnh.")
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
            Text("Ngữ cảnh là vài tin gần nhất gửi kèm để dịch đúng xưng hô; tự hết hạn sau 45 phút. Lịch sử và key chỉ lưu trên iPhone này; OpenAI không lưu tin vào tài khoản của bạn.")
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
        } footer: {
            Text("Bộ gõ Telex: TelexCore của VietTelex, © 2026 Phil Trinh, dùng theo giấy phép MIT (github.com/ptrinh/viettelex). Vị trí phím theo số đo bàn phím iPhone của VietTelex.")
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
        let tested = apiKey
        let translator = Translator(apiKey: apiKey.trimmingCharacters(in: .whitespacesAndNewlines),
                                    model: TranslationModel(rawValue: model) ?? .sol)
        Task {
            let outcome: (ok: Bool, text: String)
            do {
                let result = try await translator.translate("Anh gửi giúp em báo giá trước thứ Sáu nhé.",
                                                            to: Language.named("en") ?? Language.all[0], checkMeaning: true)
                outcome = (true, "Kết nối ổn: \(result.text)")
            } catch {
                outcome = (false, error.localizedDescription)
            }
            // A key pasted meanwhile is not the one this answer is about.
            if apiKey == tested { testResult = outcome }
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
