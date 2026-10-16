import UIKit

/// CI only (ci/keyboard-smoke.sh switches it on through the App Group): types through the real
/// key handling at a quick typist's pace, then holds delete, and records for each part how long
/// the main thread stays busy after every press, release and delete repeat (the handler, then
/// the layout and the Core Animation commit it causes). Also counts key layouts, late frames
/// and clipboard checks made on the main thread.
@MainActor
final class TypingBenchmark: NSObject {
    static let flagKey = "typingBenchmark"
    static let resultKey = "typingBenchmarkResult"

    /// Telex keys, part by part: a sentence, one key pressed again and again, one Vietnamese
    /// word typed again and again, and a long chat laugh.
    static let parts: [(name: String, keys: String)] = [
        ("câu", "chaof anh been em ddax guwir baos gias roofi nhes  anh xem giusp em vowis aj"),
        ("aaaa", " aaaaaaaaaaaaaaaaaaaa"),
        ("người×6", " nguwowif nguwowif nguwowif nguwowif nguwowif nguwowif"),
        ("hahaha", " hahahahahahahahahahahahahahahahahahahaha"),
    ]
    // ci/typing-benchmark.py checks the field held the expected text before delete was held.
    private static let deleteKey: Character = "\u{8}"
    private static let deleteHold: TimeInterval = 2

    private let keyboard: KeyboardLayoutView
    private let finished: (String) -> Void
    private var queue: [(part: String, key: Character)] = []
    private var busy: [String: [Double]] = [:]
    private var order: [String] = []
    private var frames: [CFTimeInterval] = []
    private var link: CADisplayLink?
    private var layoutsAtStart = 0
    private var checksAtStart = 0
    private var checkTimeAtStart: CFTimeInterval = 0

    init(keyboard: KeyboardLayoutView, finished: @escaping (String) -> Void) {
        self.keyboard = keyboard
        self.finished = finished
        for part in Self.parts {
            queue += part.keys.map { (part.name, $0) }
            order.append(part.name)
        }
        order.append("xóa giữ")
    }

    func start() {
        layoutsAtStart = KeyboardLayoutView.layoutPasses
        checksAtStart = KeyboardViewController.mainThreadPasteboardChecks
        checkTimeAtStart = KeyboardViewController.mainThreadPasteboardTime
        link = CADisplayLink(target: self, selector: #selector(tick(_:)))
        link?.add(to: .main, forMode: .common)
        step()
    }

    /// 35 ms down, 35 ms up: about 14 keys a second.
    private func step() {
        guard !queue.isEmpty else { return holdDelete() }
        let (part, character) = queue.removeFirst()
        mark(part)
        let token = keyboard.benchmarkPress(character)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.035) { [self] in
            if let token {
                mark(part)
                keyboard.benchmarkRelease(token)
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.035) { [self] in step() }
        }
    }

    /// Holding delete: letters first, then whole words, each repeat measured.
    private func holdDelete() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [self] in
            keyboard.onRepeat = { [weak self] in self?.mark("xóa giữ") }
            mark("xóa giữ")
            let token = keyboard.benchmarkPress(Self.deleteKey)
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.deleteHold) { [self] in
                if let token { keyboard.benchmarkRelease(token) }
                keyboard.onRepeat = nil
                finish()
            }
        }
    }

    /// Starts timing an event: main-thread time from now until the run loop is about to sleep,
    /// which is after Core Animation has laid out and committed whatever the event changed.
    private func mark(_ part: String) {
        let start = CACurrentMediaTime()
        let observer = CFRunLoopObserverCreateWithHandler(nil, CFRunLoopActivity.beforeWaiting.rawValue, false, 3_000_000) { [weak self] observer, _ in
            self?.busy[part, default: []].append((CACurrentMediaTime() - start) * 1000)
            CFRunLoopRemoveObserver(CFRunLoopGetMain(), observer, .commonModes)
        }
        CFRunLoopAddObserver(CFRunLoopGetMain(), observer, .commonModes)
    }

    @objc private func tick(_ link: CADisplayLink) {
        frames.append(link.timestamp)
    }

    private func finish() {
        link?.invalidate()
        link = nil
        let parts = order.map { name -> String in
            let sorted = (busy[name] ?? []).sorted()
            guard !sorted.isEmpty else { return "\(name): none" }
            let average = sorted.reduce(0, +) / Double(sorted.count)
            let p95 = sorted[min(sorted.count - 1, Int(Double(sorted.count) * 0.95))]
            return String(format: "%@: %d events avg=%.2fms p95=%.2fms max=%.2fms", name, sorted.count, average, p95, sorted.last ?? 0)
        }
        let intervals = zip(frames.dropFirst(), frames).map { ($0 - $1) * 1000 }
        let typical = intervals.isEmpty ? 0 : intervals.sorted()[intervals.count / 2]
        let late = intervals.filter { $0 > typical * 1.5 }.count
        let keys = Self.parts.reduce(0) { $0 + $1.keys.count }
        let layouts = KeyboardLayoutView.layoutPasses - layoutsAtStart
        let checks = KeyboardViewController.mainThreadPasteboardChecks - checksAtStart
        let checkTime = (KeyboardViewController.mainThreadPasteboardTime - checkTimeAtStart) * 1000
        let totals = String(format: "keys=%d layouts=%d (%.2f/key) late_frames=%d/%d frame=%.1fms clipboard_checks_on_main=%d (%.1fms)",
                            keys, layouts, Double(layouts) / Double(keys), late, intervals.count, typical, checks, checkTime)
        finished((parts + [totals]).joined(separator: "\n"))
    }
}
