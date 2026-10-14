import ReplayKit
import SwiftUI

@main
struct DichAppleApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

struct ContentView: View {
    @AppStorage(SharedStore.Key.targetCode, store: SharedStore.defaults) private var targetCode = "zh"
    @AppStorage(SharedStore.Key.profile, store: SharedStore.defaults) private var profile = ""
    @AppStorage(SharedStore.Key.glossary, store: SharedStore.defaults) private var glossary = ""
    @AppStorage(SharedStore.Key.useScreen, store: SharedStore.defaults) private var useScreen = true
    @AppStorage(SharedStore.Key.apiKey, store: SharedStore.defaults) private var apiKey = ""
    @State private var testResult = ""
    @State private var testing = false
    @State private var tryText = ""
    @State private var keyboardReady = SharedStore.keyboardHasFullAccess
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Step(done: keyboardReady, text: "Cài đặt › Chung › Bàn phím › Bàn phím › Thêm bàn phím mới › Dịch Apple")
                    Step(done: keyboardReady, text: "Chạm Dịch Apple, bật “Cho phép truy cập đầy đủ”")
                    Step(done: !apiKey.isEmpty, text: "Dán API key OpenAI ở mục bên dưới")
                    Button("Mở Cài đặt") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                } header: {
                    Text("Cài bàn phím")
                } footer: {
                    Text("Bàn phím cần truy cập đầy đủ để gửi câu bạn bấm Dịch tới OpenAI. Không câu nào được gửi nếu bạn không bấm.")
                }

                Section("Thử ngay") {
                    TextField("Gõ tiếng Việt, đổi sang bàn phím Dịch Apple rồi bấm Dịch", text: $tryText, axis: .vertical)
                        .lineLimit(2...5)
                }

                Section {
                    SecureField("sk-...", text: $apiKey)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    LabeledContent("Model", value: Config.model)
                    Button(testing ? "Đang kiểm tra…" : "Kiểm tra kết nối") { test() }
                        .disabled(apiKey.isEmpty || testing)
                    if !testResult.isEmpty { Text(testResult).font(.footnote) }
                } header: {
                    Text("OpenAI")
                } footer: {
                    Text("Key chỉ lưu trên iPhone này, trong vùng dữ liệu chung của app và bàn phím.")
                }

                Section("Tin đi dịch sang") {
                    Picker("Ngôn ngữ", selection: $targetCode) {
                        ForEach(Language.targets, id: \.code) { Text($0.label).tag($0.code) }
                    }
                    .pickerStyle(.segmented)
                }

                Section {
                    Toggle("Dùng chữ trên màn hình làm ngữ cảnh", isOn: $useScreen)
                    if useScreen {
                        HStack {
                            Text("Bật đọc màn hình")
                            Spacer()
                            BroadcastButton().frame(width: 44, height: 44)
                        }
                    }
                } header: {
                    Text("Tự đọc ngữ cảnh")
                } footer: {
                    Text("Chạm nút ghi rồi chọn Bắt đầu phát. Khi bàn phím Dịch Apple đang mở, app đọc chữ trong khung chat ngay trên máy để hiểu ngữ cảnh. Không lưu ảnh màn hình.")
                }

                Section {
                    TextField("Ví dụ: Tôi là người mua, xưng em, gọi đối tác là anh", text: $profile, axis: .vertical)
                        .lineLimit(1...4)
                    TextField("Thuật ngữ, mỗi dòng một cặp. Ví dụ: 主轴 = trục chính", text: $glossary, axis: .vertical)
                        .lineLimit(2...6)
                    Button("Xóa ngữ cảnh đang nhớ", role: .destructive) { SharedStore.clearContext() }
                } header: {
                    Text("Về bạn và thuật ngữ")
                }
            }
            .navigationTitle("Dịch Apple")
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { keyboardReady = SharedStore.keyboardHasFullAccess }
        }
    }

    private func test() {
        testing = true
        testResult = ""
        Task {
            do {
                let output = try await Translator(apiKey: apiKey).translate("Xin chào, rất vui được làm việc với anh.", to: Language.target("en"))
                testResult = "Kết nối ổn: \(output)"
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
