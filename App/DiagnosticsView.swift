import SwiftUI
import UIKit

/// What the app can see about how iOS installed it and its keyboard: the signing profiles,
/// the entitlements each part was signed with, the App Group, and how far the keyboard got the
/// last time iOS started it. Meant to be copied and sent when the keyboard never opens.
///
/// The usual culprit: re-signing tools that sign an extension without its own profile or without
/// the "main binary" flag. iOS 26 then kills the keyboard before any of its code runs (crash log:
/// CODESIGNING, Invalid Page) and switches back to the Apple keyboard.
enum Diagnostics {
    static func report() -> String {
        var out: [String] = []
        let info = Bundle.main.infoDictionary
        out.append("MSTrans \(info?["CFBundleShortVersionString"] as? String ?? "?") (\(info?["CFBundleVersion"] as? String ?? "?"))"
                   + " · iOS \(UIDevice.current.systemVersion) · \(machine())")

        let appProfile = profile(in: Bundle.main.bundleURL)
        out.append("")
        out += describe(bundle: Bundle.main, label: "App", appTeam: team(appProfile))

        let plugins = Bundle.main.builtInPlugInsURL
        let items = plugins.flatMap { try? FileManager.default.contentsOfDirectory(at: $0, includingPropertiesForKeys: nil) } ?? []
        let appexes = items.filter { $0.pathExtension == "appex" }.sorted { $0.lastPathComponent < $1.lastPathComponent }
        if appexes.isEmpty {
            out.append("")
            out.append("✗ Không có phần mở rộng nào trong app: bàn phím đã bị bỏ khi ký/cài (khi cài, chọn giữ app extensions).")
        }
        let keyboardIDs = InstalledKeyboard.bundles.compactMap(\.bundleIdentifier)
        var keyboardSignedBadly = false
        for url in appexes {
            out.append("")
            if let bundle = Bundle(url: url) {
                let lines = describe(bundle: bundle, label: url.deletingPathExtension().lastPathComponent, appTeam: team(appProfile))
                if keyboardIDs.contains(bundle.bundleIdentifier ?? ""), lines.contains(where: { $0.hasPrefix("✗") }) {
                    keyboardSignedBadly = true
                }
                out += lines
            } else {
                out.append("✗ \(url.lastPathComponent): không đọc được")
            }
        }

        out.append("")
        out.append("App Group app đang dùng: \(Config.appGroup)")
        if let appProfile, ((appProfile["Entitlements"] as? [String: Any])?["com.apple.security.application-groups"] as? [String] ?? []).isEmpty {
            out.append("✗ Hồ sơ của app không có App Group: app và bàn phím không dùng chung dữ liệu, bàn phím sẽ hỏi dán API key riêng")
        }
        let enabled = (UserDefaults.standard.array(forKey: "AppleKeyboards") as? [String]) ?? []
        for id in keyboardIDs {
            out.append(enabled.contains(id) ? "✓ Bàn phím \(id) đã được thêm trong Cài đặt" : "✗ Bàn phím \(id) chưa có trong danh sách bàn phím")
        }
        if let stage = SharedStore.keyboardStage {
            out.append("Bàn phím chạy lần cuối: bước \(stage.stage), \(stage.at.formatted(date: .abbreviated, time: .standard))")
        } else {
            out.append("✗ Bàn phím chưa từng chạy được (hoặc không dùng chung App Group với app)")
        }
        out.append("Đã thấy truy cập đầy đủ: \(SharedStore.keyboardHasFullAccess ? "có" : "chưa")")
        if keyboardSignedBadly {
            out.append("")
            out.append("→ Kết luận: bàn phím bị ký sai khi cài nên iOS tắt nó ngay khi mở. Không phải lỗi cài đặt trên iPhone. "
                       + "Cài lại MSTrans bằng AltStore hoặc SideStore (không dùng Sideloadly, Feather).")
        }
        return out.joined(separator: "\n")
    }

    // MARK: One bundle

    private static func describe(bundle: Bundle, label: String, appTeam: String?) -> [String] {
        var out = ["[\(label)] \(bundle.bundleIdentifier ?? "?")"]
        let ext = bundle.object(forInfoDictionaryKey: "NSExtension") as? [String: Any]
        if let ext {
            out.append("  Loại: \(ext["NSExtensionPointIdentifier"] as? String ?? "?") · lớp chính: \(ext["NSExtensionPrincipalClass"] as? String ?? "?")")
            if let attributes = ext["NSExtensionAttributes"] as? [String: Any], !attributes.isEmpty {
                out.append("  Thuộc tính: " + attributes.keys.sorted().map { "\($0)=\(attributes[$0] ?? "")" }.joined(separator: ", "))
            }
        }

        let executableName = bundle.object(forInfoDictionaryKey: "CFBundleExecutable") as? String ?? ""
        let executable = bundle.bundleURL.appendingPathComponent(executableName)
        let size = (try? executable.resourceValues(forKeys: [.fileSizeKey]))?.fileSize
        out.append(size.map { "  Tệp chạy: \(executableName), \($0 / 1024) KB" } ?? "✗ Thiếu tệp chạy \(executableName)")

        switch mainBinaryFlag(executable: executable) {
        case true?: out.append("  Cờ chữ ký main binary: có")
        case false?: out.append("✗ Chữ ký thiếu cờ main binary: iOS 26 tắt phần này ngay khi mở (CODESIGNING · Invalid Page)")
        case nil: out.append("? Không đọc được chữ ký của tệp chạy")
        }

        let signed = signedEntitlements(executable: executable)
        let isExtension = bundle.bundleURL.pathExtension == "appex"
        guard let provisioning = profile(in: bundle.bundleURL) else {
            #if targetEnvironment(simulator)
            out.append("  Không có hồ sơ ký (bình thường trên máy ảo)")
            if let signed { out.append("  Quyền đã ký: " + summary(signed)) }
            return out
            #endif
            out.append(isExtension
                       ? "✗ Không có hồ sơ ký riêng (embedded.mobileprovision): công cụ cài đã ký phần này không đúng, iOS sẽ không chạy nó"
                       : "✗ Không có hồ sơ ký (embedded.mobileprovision)")
            if let signed {
                out.append("  Quyền đã ký: " + summary(signed))
                let signedID = signed["application-identifier"] as? String ?? "?"
                if isExtension, !signedID.hasSuffix("." + (bundle.bundleIdentifier ?? "")) {
                    out.append("✗ Ký bằng mã của app chính (\(signedID)), không phải mã của phần này")
                }
            }
            return out
        }
        let profileEntitlements = provisioning["Entitlements"] as? [String: Any] ?? [:]
        let expiry = provisioning["ExpirationDate"] as? Date
        let profileTeam = team(provisioning)
        out.append("  Hồ sơ: \(provisioning["Name"] as? String ?? "?") · team \(profileTeam ?? "?")"
                   + (expiry.map { " · hết hạn \($0.formatted(date: .abbreviated, time: .shortened))" } ?? ""))
        if let expiry, expiry < Date() { out.append("✗ Hồ sơ ký đã hết hạn: làm mới app trong AltStore") }
        if let appTeam, let profileTeam, profileTeam != appTeam { out.append("✗ Khác team với app (\(profileTeam) ≠ \(appTeam))") }

        let profileID = profileEntitlements["application-identifier"] as? String ?? "?"
        out.append("  App ID trong hồ sơ: \(profileID)")
        if !matches(profileID, bundle.bundleIdentifier ?? "") {
            out.append("✗ App ID của hồ sơ không khớp mã gói \(bundle.bundleIdentifier ?? "?")")
        }
        let profileGroups = profileEntitlements["com.apple.security.application-groups"] as? [String] ?? []
        out.append("  App Group trong hồ sơ: " + (profileGroups.isEmpty ? "(không có)" : profileGroups.joined(separator: ", ")))

        guard let signed else {
            out.append("? Không đọc được quyền đã ký trong tệp chạy")
            return out
        }
        out.append("  Quyền đã ký: " + summary(signed))
        let signedID = signed["application-identifier"] as? String ?? "?"
        if signedID != profileID && !matches(profileID, String(signedID.drop { $0 != "." }.dropFirst())) {
            out.append("✗ App ID đã ký (\(signedID)) khác hồ sơ (\(profileID))")
        }
        let signedGroups = signed["com.apple.security.application-groups"] as? [String] ?? []
        let missing = signedGroups.filter { !profileGroups.contains($0) }
        if !missing.isEmpty {
            out.append("✗ Ký với App Group hồ sơ không cho phép: \(missing.joined(separator: ", ")) — iOS sẽ không chạy phần này")
        }
        if missing.isEmpty && signedID != "?" { out.append("✓ Chữ ký và hồ sơ khớp nhau") }
        return out
    }

    private static func summary(_ entitlements: [String: Any]) -> String {
        entitlements.keys.sorted().map { key in
            let value = entitlements[key]
            if let list = value as? [String] { return "\(key)=[\(list.joined(separator: ","))]" }
            return "\(key)=\(value ?? "")"
        }.joined(separator: "; ")
    }

    /// "TEAM.com.example.*" or "TEAM.com.example.app" against a bundle ID.
    private static func matches(_ applicationIdentifier: String, _ bundleID: String) -> Bool {
        guard let dot = applicationIdentifier.firstIndex(of: ".") else { return false }
        let pattern = applicationIdentifier[applicationIdentifier.index(after: dot)...]
        if pattern == "*" { return true }
        if pattern.hasSuffix("*") { return bundleID.hasPrefix(pattern.dropLast()) }
        return pattern == bundleID
    }

    private static func team(_ profile: [String: Any]?) -> String? {
        (profile?["TeamIdentifier"] as? [String])?.first
    }

    // MARK: Reading the bundle

    private static func profile(in bundleURL: URL) -> [String: Any]? {
        guard let data = try? Data(contentsOf: bundleURL.appendingPathComponent("embedded.mobileprovision")) else { return nil }
        return plists(in: data).first { $0["Entitlements"] != nil }
    }

    /// The entitlements blob that codesign embeds in the executable as an XML plist.
    private static func signedEntitlements(executable: URL) -> [String: Any]? {
        guard let data = try? Data(contentsOf: executable, options: .mappedIfSafe) else { return nil }
        return plists(in: data).first { $0["application-identifier"] != nil || $0["get-task-allow"] != nil }
    }

    /// CS_EXECSEG_MAIN_BINARY in the executable's code directory. Apple's tools set it on every
    /// app and extension executable; iOS 26 refuses to run an extension without it.
    private static func mainBinaryFlag(executable: URL) -> Bool? {
        guard let data = try? Data(contentsOf: executable, options: .mappedIfSafe) else { return nil }
        func u32(_ at: Int, bigEndian: Bool) -> UInt32? {
            guard at >= 0, at + 4 <= data.count else { return nil }
            let raw = data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: at, as: UInt32.self) }
            return bigEndian ? UInt32(bigEndian: raw) : UInt32(littleEndian: raw)
        }
        // A universal binary: look at its first slice.
        var base = 0
        if u32(0, bigEndian: true) == 0xCAFE_BABE {
            guard let offset = u32(16, bigEndian: true) else { return nil }
            base = Int(offset)
        }
        guard u32(base, bigEndian: false) == 0xFEED_FACF, let commands = u32(base + 16, bigEndian: false) else { return nil }
        var at = base + 32
        for _ in 0..<commands {
            guard let command = u32(at, bigEndian: false), let size = u32(at + 4, bigEndian: false), size > 0 else { return nil }
            if command == 0x1D {  // LC_CODE_SIGNATURE
                guard let offset = u32(at + 8, bigEndian: false) else { return nil }
                let blob = base + Int(offset)
                guard u32(blob, bigEndian: true) == 0xFADE_0CC0, let count = u32(blob + 8, bigEndian: true) else { return nil }
                for index in 0..<Int(count) {
                    guard let type = u32(blob + 12 + index * 8, bigEndian: true),
                          let entry = u32(blob + 16 + index * 8, bigEndian: true) else { return nil }
                    // The code directory, or an alternate one for another hash type.
                    guard type == 0 || (0x1000..<0x1005).contains(type) else { continue }
                    let directory = blob + Int(entry)
                    guard u32(directory, bigEndian: true) == 0xFADE_0C02,
                          let version = u32(directory + 8, bigEndian: true) else { continue }
                    guard version >= 0x20400, let flags = u32(directory + 84, bigEndian: true) else { return false }
                    return flags & 1 != 0
                }
                return nil
            }
            at += Int(size)
        }
        return nil
    }

    private static func plists(in data: Data) -> [[String: Any]] {
        let open = Data("<?xml".utf8)
        let close = Data("</plist>".utf8)
        var found: [[String: Any]] = []
        var from = data.startIndex
        while let start = data.range(of: open, in: from..<data.endIndex),
              let end = data.range(of: close, in: start.upperBound..<data.endIndex) {
            if let plist = try? PropertyListSerialization.propertyList(from: data.subdata(in: start.lowerBound..<end.upperBound),
                                                                        format: nil) as? [String: Any] {
                found.append(plist)
            }
            from = end.upperBound
        }
        return found
    }

    private static func machine() -> String {
        var system = utsname()
        uname(&system)
        return withUnsafeBytes(of: &system.machine) { bytes in
            String(decoding: bytes.prefix { $0 != 0 }, as: UTF8.self)
        }
    }
}

struct DiagnosticsView: View {
    @State private var report = ""
    @State private var copied = false
    @State private var typingLog: [String] = []
    @State private var typingLogCopied = false

    var body: some View {
        List {
            Section {
                Text("Mở một ô nhập bất kỳ, chọn bàn phím MSTrans một lần (dù nó không hiện), rồi quay lại đây và bấm Sao chép báo cáo để gửi cho người hỗ trợ. Dòng ✗ là chỗ có lỗi.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Button {
                    report = Diagnostics.report()
                    Clipboard.copy(report)
                    copied = true
                } label: {
                    Label(copied ? "Đã sao chép báo cáo" : "Sao chép báo cáo", systemImage: copied ? "checkmark" : "doc.on.doc")
                }
                if !report.isEmpty {
                    ShareLink(item: report) { Label("Chia sẻ báo cáo", systemImage: "square.and.arrow.up") }
                }
            }
            Section("Báo cáo") {
                Text(report.isEmpty ? "Đang đọc…" : report)
                    .font(.system(.footnote, design: .monospaced))
                    .textSelection(.enabled)
            }
            Section {
                Text("Khi gõ bị sai hay mất chữ: gõ tiếp đến khi lỗi hiện ra, quay lại đây bấm Sao chép nhật ký gõ rồi gửi cho người hỗ trợ. Nhật ký ghi từng phím, chữ bàn phím đã sửa và chữ app báo lại, nên có đoạn cuối những gì vừa gõ bằng MSTrans. Nó chỉ nằm trên iPhone này.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Button {
                    let info = Bundle.main.infoDictionary
                    let header = "Nhật ký gõ MSTrans \(info?["CFBundleShortVersionString"] as? String ?? "?") · iOS \(UIDevice.current.systemVersion)"
                    Clipboard.copy(([header] + typingLog).joined(separator: "\n"))
                    typingLogCopied = true
                } label: {
                    Label(typingLogCopied ? "Đã sao chép nhật ký gõ" : "Sao chép nhật ký gõ (\(typingLog.count) dòng)",
                          systemImage: typingLogCopied ? "checkmark" : "keyboard")
                }
                .disabled(typingLog.isEmpty)
                Button(role: .destructive) {
                    SharedStore.typingLog = []
                    typingLog = []
                    typingLogCopied = false
                } label: {
                    Label("Xóa nhật ký gõ", systemImage: "trash")
                }
                .disabled(typingLog.isEmpty)
            } header: {
                Text("Nhật ký gõ phím")
            }
        }
        .navigationTitle("Chẩn đoán bàn phím")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            report = Diagnostics.report()
            typingLog = SharedStore.typingLog
        }
    }
}
