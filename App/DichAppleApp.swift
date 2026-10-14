import ReplayKit
import SwiftUI

@main
struct DichAppleApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

struct ContentView: View {
    @AppStorage(SharedStore.Key.apiKey, store: SharedStore.defaults) private var apiKey = ""
    @AppStorage(SharedStore.Key.model, store: SharedStore.defaults) private var model = TranslationModel.sol.rawValue
    @AppStorage(SharedStore.Key.targetCode, store: SharedStore.defaults) private var targetCode = Language.autoCode
    @AppStorage(SharedStore.Key.tone, store: SharedStore.defaults) private var tone = Tone.polite.rawValue
    @AppStorage(SharedStore.Key.checkMeaning, store: SharedStore.defaults) private var checkMeaning = true
    @AppStorage(SharedStore.Key.gender, store: SharedStore.defaults) private var gender = 0
    @AppStorage(SharedStore.Key.addressing, store: SharedStore.defaults) private var addressing = ""
    @AppStorage(SharedStore.Key.profile, store: SharedStore.defaults) private var profile = ""
    @AppStorage(SharedStore.Key.glossary, store: SharedStore.defaults) private var glossary = ""
    @AppStorage(SharedStore.Key.autoIncoming, store: SharedStore.defaults) private var autoIncoming = true
    @AppStorage(SharedStore.Key.useScreen, store: SharedStore.defaults) private var useScreen = true
    @AppStorage(SharedStore.Key.haptics, store: SharedStore.defaults) private var haptics = true
    @State private var testResult = ""
    @State private var testing = false
    @State private var tryText = ""
    @State private var keyboardReady = SharedStore.keyboardHasFullAccess
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            Form {
                setupSection
                Section("Thử ngay") {
                    TextField("Gõ tiếng Việt, đổi sang bàn phím Dịch Apple rồi bấm Dịch", text: $tryText, axis: .vertical)
                        .lineLimit(2...5)
                }
                openAISection
                outgoingSection
                aboutSection
                incomingSection
                Section {
                    Toggle("Rung khi gõ", isOn: $haptics)
                } header: {
                    Text("Bàn phím")
                } footer: {
                    Text("Giữ một nguyên âm để chọn dấu. Giữ phím cách rồi kéo để di chuyển con trỏ. Chạm 2 lần Shift để khóa chữ hoa. Chạm 2 lần phím cách để thêm dấu chấm.")
                }
                Section {
                    Button("Xóa ngữ cảnh đang nhớ", role: .destructive) { SharedStore.clearContext() }
                }
            }
            .navigationTitle("Dịch Apple")
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { keyboardReady = SharedStore.keyboardHasFullAccess }
        }
    }

    private var setupSection: some View {
        Section {
            Step(done: keyboardReady, text: "Cài đặt › Cài đặt chung › Bàn phím › Bàn phím › Thêm bàn phím mới › Dịch Apple")
            Step(done: keyboardReady, text: "Chạm Dịch Apple, bật “Cho phép truy cập đầy đủ”")
            Step(done: !apiKey.isEmpty, text: "Dán API key OpenAI ở mục bên dưới")
            Button("Mở Cài đặt") {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
        } header: {
            Text("Cài bàn phím")
        } footer: {
            Text("Bàn phím cần truy cập đầy đủ để gửi câu bạn bấm Dịch tới OpenAI. Không câu nào được gửi nếu bạn không bấm Dịch hoặc không bật tự dịch tin đến.")
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
            Button(testing ? "Đang kiểm tra…" : "Kiểm tra kết nối") { test() }
                .disabled(apiKey.isEmpty || testing)
            if !testResult.isEmpty { Text(testResult).font(.footnote) }
        } header: {
            Text("OpenAI")
        } footer: {
            Text("“Chuẩn nhất” dùng GPT-6.1 Sol: bản dịch tự nhiên và chính xác nhất, khoảng 0,003 USD mỗi tin. “Nhanh” dùng GPT-6 Luna, rẻ hơn khoảng 20 lần. Key chỉ lưu trên iPhone này.")
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
            Text("“Tự động” trả lời bằng đúng ngôn ngữ đối tác đang dùng. Kiểm tra nghĩa cho bạn đọc lại bằng tiếng Việt câu đối tác sẽ nhận, ngay trên bàn phím.")
        }
    }

    private var aboutSection: some View {
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
            TextField("Thuật ngữ, mỗi dòng một cặp. Ví dụ: 主轴 = trục chính", text: $glossary, axis: .vertical)
                .lineLimit(2...8)
        } header: {
            Text("Về bạn")
        } footer: {
            Text("Giúp bản dịch xưng hô đúng, dùng đúng tiểu từ lịch sự (tiếng Thái, tiếng Nhật…) và đúng thuật ngữ ngành của bạn.")
        }
    }

    private var incomingSection: some View {
        Section {
            Toggle("Tự dịch tin mới của đối tác", isOn: $autoIncoming)
            Toggle("Đọc màn hình làm ngữ cảnh", isOn: $useScreen)
            if useScreen {
                HStack {
                    Text("Bật đọc màn hình")
                    Spacer()
                    BroadcastButton().frame(width: 44, height: 44)
                }
            }
        } header: {
            Text("Tin đối tác gửi")
        } footer: {
            Text("Khi mở bàn phím, bản dịch tin mới nhất của đối tác hiện ngay ở dòng trên cùng: lấy từ màn hình nếu đang bật đọc màn hình, hoặc từ tin bạn vừa sao chép. Đọc màn hình chạy trên máy, không lưu ảnh. Muốn đọc tin mà không mở bàn phím: trong app Phím tắt, tạo phím tắt có thao tác “Dịch tin đã sao chép”, rồi gán vào Cài đặt › Trợ năng › Cảm ứng › Chạm vào mặt sau.")
        }
    }

    private func test() {
        testing = true
        testResult = ""
        let translator = Translator(apiKey: apiKey, model: TranslationModel(rawValue: model) ?? .sol)
        Task {
            do {
                let result = try await translator.translate("Anh gửi giúp em báo giá trước thứ Sáu nhé.", to: Language.named("en") ?? Language.all[0], checkMeaning: true)
                testResult = "Kết nối ổn: \(result.text)" + (result.meaning.map { "\nNghĩa: \($0)" } ?? "")
            } catch {
                testResult = error.localizedDescription
            }
            testing = false
        }
    }
}

private struct Step: View {
    let done: Bool
    let text: String

    var body: some View {
        Label {
            Text(text)
        } icon: {
            Image(systemName: done ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(done ? Color.green : Color.secondary)
        }
        .accessibilityValue(done ? "Đã xong" : "Chưa xong")
    }
}

/// The system button that starts the screen broadcast into the DichScreen extension.
private struct BroadcastButton: UIViewRepresentable {
    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let picker = RPSystemBroadcastPickerView(frame: CGRect(x: 0, y: 0, width: 44, height: 44))
        picker.preferredExtension = Self.screenExtensionID()
        picker.showsMicrophoneButton = false
        return picker
    }

    func updateUIView(_ uiView: RPSystemBroadcastPickerView, context: Context) {}

    /// The broadcast extension's real bundle ID (sideloading tools may rename it).
    private static func screenExtensionID() -> String? {
        guard let plugins = Bundle.main.builtInPlugInsURL,
              let items = try? FileManager.default.contentsOfDirectory(at: plugins, includingPropertiesForKeys: nil) else { return nil }
        for url in items where url.pathExtension == "appex" {
            guard let bundle = Bundle(url: url),
                  let ext = bundle.object(forInfoDictionaryKey: "NSExtension") as? [String: Any],
                  ext["NSExtensionPointIdentifier"] as? String == "com.apple.broadcast-services-upload" else { continue }
            return bundle.bundleIdentifier
        }
        return nil
    }
}
