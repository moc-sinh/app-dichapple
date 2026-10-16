import UIKit

/// CI only (ci/keyboard-smoke.sh switches it on through the App Group): types a Vietnamese
/// sentence through the real key handling at a quick typist's pace, and records how long the main
/// thread stays busy after each press and release (the handler, then the layout and the Core
/// Animation commit it causes), how many key layouts ran, and how many frames came late.
@MainActor
final class TypingBenchmark: NSObject {
    static let flagKey = "typingBenchmark"
    static let resultKey = "typingBenchmarkResult"
    /// Telex keys for "Chào anh bên em đã gửi báo giá rồi nhé. Anh xem giúp em với ạ".
    static let keys = Array("chaof anh been em ddax guwir baos gias roofi nhes  anh xem giusp em vowis aj")

    private let keyboard: KeyboardLayoutView
    private let finished: (String) -> Void
    private var index = 0
    private var busy: [Double] = []
    private var frames: [CFTimeInterval] = []
    private var link: CADisplayLink?
    private var layoutsAtStart = 0
    private var checksAtStart = 0
    private var checkTimeAtStart: CFTimeInterval = 0

    init(keyboard: KeyboardLayoutView, finished: @escaping (String) -> Void) {
        self.keyboard = keyboard
        self.finished = finished
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
        guard index < Self.keys.count else { return finish() }
        let character = Self.keys[index]
        index += 1
        var token: AnyObject?
        measure { token = keyboard.benchmarkPress(character) }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.035) { [self] in
            if let token { measure { keyboard.benchmarkRelease(token) } }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.035) { [self] in step() }
        }
    }

    /// Main-thread time from the event until the run loop is about to sleep, which is after
    /// Core Animation has laid out and committed whatever the event changed.
    private func measure(_ work: () -> Void) {
        let start = CACurrentMediaTime()
        work()
        let observer = CFRunLoopObserverCreateWithHandler(nil, CFRunLoopActivity.beforeWaiting.rawValue, false, 3_000_000) { [weak self] observer, _ in
            self?.busy.append((CACurrentMediaTime() - start) * 1000)
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
        let sorted = busy.sorted()
        func percentile(_ p: Double) -> Double {
            sorted.isEmpty ? 0 : sorted[min(sorted.count - 1, Int(Double(sorted.count) * p))]
        }
        let average = sorted.isEmpty ? 0 : sorted.reduce(0, +) / Double(sorted.count)
        let intervals = zip(frames.dropFirst(), frames).map { ($0 - $1) * 1000 }
        let typical = intervals.isEmpty ? 0 : intervals.sorted()[intervals.count / 2]
        let late = intervals.filter { $0 > typical * 1.5 }.count
        let layouts = KeyboardLayoutView.layoutPasses - layoutsAtStart
        let checks = KeyboardViewController.mainThreadPasteboardChecks - checksAtStart
        let checkTime = (KeyboardViewController.mainThreadPasteboardTime - checkTimeAtStart) * 1000
        let keys = Double(Self.keys.count)
        finished(String(format: "keys=%d events=%d busy_avg=%.2fms p50=%.2fms p95=%.2fms max=%.2fms "
                        + "layouts=%d (%.2f/key) late_frames=%d/%d frame=%.1fms clipboard_checks_on_main=%d (%.1fms)",
                        Self.keys.count, sorted.count, average, percentile(0.5), percentile(0.95), sorted.last ?? 0,
                        layouts, Double(layouts) / keys, late, intervals.count, typical, checks, checkTime))
    }
}
