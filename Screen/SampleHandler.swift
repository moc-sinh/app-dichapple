import ReplayKit
import Vision

/// Reads chat text from the screen while the user broadcasts to Dịch Apple.
/// Kept light for the ~50 MB extension limit: it reads at most one frame every
/// 1.5 s, only while the translation keyboard is open, and only when the
/// screen changed. Nothing but the recognised text of the last few messages
/// leaves this process, and only into the shared App Group.
final class SampleHandler: RPBroadcastSampleHandler {
    private let queue = DispatchQueue(label: "dichapple.screen.ocr", qos: .utility)
    private let lock = NSLock()
    private var busy = false
    private var lastRun = Date.distantPast
    private var lastSignature: [UInt8] = []
    private var languages: [String] = []

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        SharedStore.screenActive = true
        let code = SharedStore.targetCode == Language.autoCode ? SharedStore.lastPartnerCode : SharedStore.targetCode
        let partner = Language.named(code)?.ocrCode ?? "zh-Hans"
        let wanted = [partner, "vi-VT", "vi-VN", "en-US"]
        let supported = (try? VNRecognizeTextRequest().supportedRecognitionLanguages()) ?? []
        var picked: [String] = []
        for code in wanted where supported.contains(code) && !picked.contains(code) { picked.append(code) }
        languages = picked
    }

    override func broadcastFinished() {
        SharedStore.screenActive = false
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleBufferType: RPSampleBufferType) {
        guard sampleBufferType == .video, Date().timeIntervalSince(lastRun) >= 1.5 else { return }
        lastRun = Date()
        // Context only matters while the user is typing with the translation keyboard.
        guard SharedStore.keyboardVisible, SharedStore.useScreen, claim() else { return }
        guard let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return release() }

        let signature = Self.signature(of: buffer)
        if signature == lastSignature { return release() }
        lastSignature = signature

        let raw = (CMGetAttachment(sampleBuffer, key: RPVideoSampleOrientationKey as CFString, attachmentModeOut: nil) as? NSNumber)?.uint32Value
        let orientation = raw.flatMap { CGImagePropertyOrientation(rawValue: $0) } ?? .up
        let languages = self.languages
        queue.async { [weak self] in
            defer { self?.release() }
            let lines = Self.recognize(buffer, orientation: orientation, languages: languages)
            if !lines.isEmpty { SharedStore.saveScreen(lines) }
        }
    }

    private func claim() -> Bool {
        lock.lock(); defer { lock.unlock() }
        if busy { return false }
        busy = true
        return true
    }

    private func release() {
        lock.lock(); busy = false; lock.unlock()
    }

    /// Cheap fingerprint of the frame: a coarse grid of luma samples.
    private static func signature(of buffer: CVPixelBuffer) -> [UInt8] {
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        let planar = CVPixelBufferIsPlanar(buffer)
        guard let base = planar ? CVPixelBufferGetBaseAddressOfPlane(buffer, 0) : CVPixelBufferGetBaseAddress(buffer) else { return [] }
        let width = planar ? CVPixelBufferGetWidthOfPlane(buffer, 0) : CVPixelBufferGetWidth(buffer)
        let height = planar ? CVPixelBufferGetHeightOfPlane(buffer, 0) : CVPixelBufferGetHeight(buffer)
        let rowBytes = planar ? CVPixelBufferGetBytesPerRowOfPlane(buffer, 0) : CVPixelBufferGetBytesPerRow(buffer)
        let step = planar ? 1 : 4
        let pixels = base.assumingMemoryBound(to: UInt8.self)
        var out: [UInt8] = []
        out.reserveCapacity(24 * 48)
        for gy in 0..<48 {
            let y = gy * height / 48 + height / 96
            for gx in 0..<24 {
                let x = gx * width / 24 + width / 48
                out.append(pixels[y * rowBytes + x * step] & 0xF0)
            }
        }
        return out
    }

    private static func recognize(_ buffer: CVPixelBuffer, orientation: CGImagePropertyOrientation, languages: [String]) -> [ChatLine] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = false
        request.minimumTextHeight = 0.012
        if !languages.isEmpty { request.recognitionLanguages = languages }
        // Skip the status bar and header at the top, and the keyboard at the bottom (normalised, origin bottom-left).
        request.regionOfInterest = CGRect(x: 0, y: 0.40, width: 1, height: 0.50)
        let handler = VNImageRequestHandler(cvPixelBuffer: buffer, orientation: orientation, options: [:])
        guard (try? handler.perform([request])) != nil, let results = request.results else { return [] }
        return chatLines(from: results)
    }

    /// Groups recognised text into chat bubbles: left half is the partner, right half is the user.
    private static func chatLines(from results: [VNRecognizedTextObservation]) -> [ChatLine] {
        let pieces = results
            .compactMap { o -> (String, CGRect)? in
                guard let text = o.topCandidates(1).first?.string.trimmingCharacters(in: .whitespaces),
                      text.count >= 2 else { return nil }
                return (text, o.boundingBox)
            }
            .sorted { $0.1.maxY > $1.1.maxY }

        var lines: [ChatLine] = []
        var previous: CGRect?
        let now = Date()
        for (text, box) in pieces {
            let side: ChatLine.Side = box.midX < 0.5 ? .partner : .me
            if let p = previous, let last = lines.last, last.side == side, p.minY - box.maxY < 0.03 {
                lines[lines.count - 1].text += " " + text
            } else {
                lines.append(ChatLine(side: side, text: text, date: now))
            }
            previous = box
        }
        return Array(lines.suffix(Config.contextLimit))
    }
}
