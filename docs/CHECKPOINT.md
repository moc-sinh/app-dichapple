# Checkpoint — 17/10/2026

Điểm dừng để phiên mới làm tiếp. Mọi mã đã được push lên nhánh `claude/funny-gates-s6pl2c`
(PR #1 nháp vào `claude/stoic-hopper-f60iid`). Không có thay đổi nào chưa commit.

## Tình trạng

- **Bản mới nhất đã build:** 1.3.1 (run 46) —
  https://github.com/moc-sinh/app-dichapple/actions/runs/38931720644/artifacts/12059641042
  Sửa lỗi bản dịch bị lặp 2 lần (đọc ô nhập trước khi Zalo kịp báo con trỏ đã dời) và bỏ phần
  "Lưu ý" + không chép lại tin gốc trong bảng dịch.
- **Mã sau 1.3.1 (1.4.0, giờ gộp vào 1.5.0), CHƯA có IPA nào được gửi cho người dùng:**
  6 commit từ phiên kia (7df07ad → b0a9b24): phím mất/lẫn quanh lúc gửi, rollover 2 ngón, Dịch;
  ⌫ sau chữ lạc lấy lại từ tiếng Việt; gõ nhanh gấp 3; benchmark CI đo lại khi máy CI khựng;
  CI in cảnh báo trình biên dịch. Run 52 của CI xanh, không có cảnh báo trong mã app.
- **Rà soát trước 1.5.0 (17/10, đọc tay, không dùng trợ lý):** đã soát `KeyboardViewController`
  (toàn bộ: vòng đời, luồng Dịch, đọc/thay ô, clipboard), `Composer` + `TypedText`, `Translator`
  (phần đổi từ 1.3.1), `SharedStore`, `History`, `TypingLog`, phần đổi của `KeyboardLayoutView`
  (rollover, Shift/Caps, bảng dấu, trackpad, xóa lặp) và CI (`ios.yml`, `keyboard-smoke.sh`,
  `typing-benchmark.py`). Sửa 2 lỗi:
  1. Bấm "Hoàn tác" hai lần trong lúc bàn phím còn đang thay chữ (nút không đổi trạng thái) chạy
     hai lượt thay chồng nhau → câu gốc bị chèn 2 lần; Dịch bấm trong lúc đó cũng đọc ô đang bị xóa
     dở. Giờ `undo()`/`translateDraft()` bỏ qua khi còn đang đi con trỏ, nút đổi thành "Đang lấy lại…".
  2. Lời nhắc OpenAI chưa nói rõ chữ trong `<message>` chỉ để dịch: tin đối tác chứa câu lệnh có thể
     bị model làm theo thay vì dịch. Đã thêm một dòng cho cả hai chiều.
  Đã xem và thấy ổn: timer/closure đều `[weak self]` hoặc được invalidate khi ẩn; mọi vòng đi con trỏ
  dừng khi ẩn (`walkGeneration`); log/snapshot/cache đều có giới hạn; đếm xóa luôn ≥ 1, không âm;
  `adjustTextPosition` dùng UTF-16 nhất quán với `TypedText.move`; retry chỉ khi lỗi mạng/5xx, timeout
  không retry; `status: incomplete` bị coi là lỗi; schema strict hợp lệ cả hai dạng; clipboard chỉ đọc
  `.string` khi người dùng bấm, còn lại chỉ `changeCount`/`hasStrings`; "Lưu lịch sử" tắt thì không
  ghi history và cache; benchmark CI đo lại chỉ khi CPU bàn phím nhỏ, lần 2 chậm vẫn fail.
  **Chưa soát kỹ:** `KeyboardLayoutView` phần bố cục/hit-test cũ (đã có Tests/Keys), các màn SwiftUI
  trong `App/`, `TranslateIntent`. API key vẫn nằm trong UserDefaults của App Group (chưa chuyển Keychain).

## Việc tiếp theo (theo thứ tự)

1. Chờ CI của bản 1.5.0, gửi link IPA dạng
   `https://github.com/moc-sinh/app-dichapple/actions/runs/<run>/artifacts/<id>`
   (job `build` xong là có artifact `MSTrans-ipa`; `keyboard-smoke` chạy sau, không ảnh hưởng file cài).
2. Nếu còn hạn mức: soát nốt các phần "chưa soát kỹ" ở trên, mỗi lần 1 trợ lý hoặc tự đọc; mỗi phát
   hiện phải được đọc lại trong mã trước khi sửa.
3. Nghe người dùng dùng thử 1.5.0 rồi đọc nhật ký gõ họ gửi.

## Quy tắc đã thống nhất với người dùng

- Trả lời tiếng Việt, ngắn gọn, dễ hiểu.
- Không ghi tên hay mã model AI vào commit, PR hay code. Commit kết thúc bằng dòng Co-Authored-By
  theo hướng dẫn của phiên.
- Trước khi push phải chạy test. Trên Linux cần toolchain Swift 6.1.2 (tải bản
  `swift-6.1.2-RELEASE-ubuntu24.04`); lệnh y như bước "Typing tests" trong
  `.github/workflows/ios.yml` (Tests/main.swift, Tests/Suite, Tests/Keys). Tests/Language cần
  NaturalLanguage nên chỉ chạy được trên CI.
- Sau khi build xong gửi link IPA. Người dùng cài bằng AltStore (Apple ID miễn phí), dùng Windows,
  không có Mac; iPhone Pro Max, iOS 26, chat chủ yếu Zalo và WeChat.
- Muốn biết bàn phím có khựng thì đọc nhật ký gõ người dùng gửi, đừng đoán.
- Dùng ít trợ lý nền; nhiều trợ lý song song làm hết hạn mức rất nhanh.
- Chỉ một phiên làm việc trên nhánh này một lúc; trước khi push luôn `git fetch` và gộp.

## Đề bài rà soát (tiếng Anh, dùng lại cho trợ lý)

Context for every auditor:

MSTrans is a personal iPhone app + custom keyboard extension (Swift; UIKit keyboard, SwiftUI app).
Linux cannot compile UIKit/SwiftUI, but pure Swift (Keyboard/Composer.swift, Keyboard/TelexCore/*,
Keyboard/KeyMetrics.swift, Shared/Config.swift, Shared/History.swift, Tests/*) compiles with swiftc.
Read-only audit: never modify repository files; scratch files outside the repo.

- Keyboard/: Composer.swift drives the vendored VietTelex TelexEngine (TelexCore/, MIT, passes its
  9,091-case suite — treat as correct unless proven otherwise) and keeps its own copy of the field
  (TypedText) because hosts report documentContext late. KeyboardViewController applies edits via
  textDocumentProxy (UTF-16 offsets) and owns the "Dịch" bar: readDocument walks the cursor back
  chunk by chunk, moveToEnd forward, 25 ms ticks, afterMove waits for the field to change; partner
  messages (clipboard or field) are translated into Vietnamese and shown in a panel. TypingLog records
  timing lines; TypingBenchmark is driven by CI. KeyboardLayoutView draws keys (KeyMetrics = Apple's
  geometry), handles multi-touch, long-press accent row, shift/caps, 123/ABC, space trackpad, delete
  repeat.
- Shared/: Translator (OpenAI Responses API, user's key, json_schema strict), SharedStore (App Group
  UserDefaults: settings, key, context lines, cache, history; background 'store' queue), History,
  Config, LanguageDetector.
- App/: SwiftUI settings app (SettingsView, TranslateView, HistoryView, GlossaryView,
  DiagnosticsView, TranslateIntent).
- CI: .github/workflows/ios.yml, ci/keyboard-smoke.sh, ci/typing-benchmark.py.
- New code to check hardest: `git diff 2e61f47..HEAD` (commits 7df07ad, 496b33e, a49d2ef, 98dd629,
  95ad06e, b0a9b24) plus 2e61f47, a3b0d72, 95acfa3, 33bdf5f. Commit messages are claims to verify.

Dimensions (one auditor each, ≤8 findings, most severe first, each with file:line, severity
[crash / data-loss / security / wrong-behaviour / perf / minor], exact scenario, quoted evidence,
minimal fix, confidence; plus a "checked OK" list):

1. **lifecycle** — retain cycles in asyncAfter chains (tick/afterMove/readDocument/moveToEnd),
   Task, URLSession, timers, CFRunLoopObserver, observers; work continuing after hide (walk moving the
   cursor in the next app, translation landing elsewhere, repeat timer, queued keys); state reset on
   show/hide and textDidChange/selectionDidChange; unbounded growth (log, queues, caches, context).
2. **startup-perf** — main-thread work init→viewDidAppear (UserDefaults, JSON decoding, pasteboard,
   key views, Auto Layout, warm-up); per-key work (edits, TypedText comparisons, logging, store writes,
   pasteboard checks, pill refresh, layout); anything O(text) or O(history) per key or > 8 ms.
3. **crash-safety** — force unwraps, indexing, Character vs UTF-16 counts for adjustTextPosition /
   deleteBackward (emoji, NFD hosts), wrong delete counts, Int(CGFloat NaN), zero bounds, tick-chain
   caps, old saved data casts, UIKit/UIPasteboard off main (496b33e), store queue vs main.
4. **composer-sync** — TypedText vs field: emptied field taken, reports compared while typing
   (a49d2ef), ⏎ as Send, rollover order, trackpad moves, keys queued during Dịch, accent row closed by
   another key, ⌫ after stray letter, word taken from field, uou+w, deleteWord, double space, literal
   mode after 32-key overflow, host autocorrect/stickers/voice text, cursor mid-word, field with text
   at open. Build a swiftc harness (see Tests/main.swift sequence() and the 400-chat simulation).
5. **touch-layout** — multi-touch orders, touchesCancelled, cross-key lift, 70 ms lit minimum,
   long press (open/close/10 pt cancel/other key), delete repeat, trackpad enter/exit, shift & caps
   rules, page switch while held, landscape, Home-button phones, extra height, edge hit areas; stuck
   state machines and wrong-key taps.
6. **translation-flow** — exact text sent (afterMove with repeated lines), exact replacement & undo
   (UTF-16, emoji, trailing newline), cancellation and late responses, two in flight, retry never
   applying twice nor retrying timeouts, strict schema validity in both shapes, non-JSON / incomplete /
   empty output, request body per model (reasoning.effort, max_output_tokens, store, text.format),
   cache key completeness and invalidation, context lines and Cuộc mới, error messages, bar state.
7. **privacy-data** — API key location and leaks (log, diagnostics export, errors), what TypingLog
   records, history-off respected (cache, context, log, app), UIPasteboard reads only on user action
   after changeCount/hasStrings and thread safety, prompt-injection wording for message and context,
   UserDefaults key consistency app/keyboard, old History decoding, settings actually read by the
   keyboard, unbounded App Group data.
8. **ci-tests** — can smoke pass while broken ("Text: match", || true, set -e, pipefail), does the
   95ad06e re-measure rule hide regressions, does the warnings step work, claims in 7df07ad/98dd629
   vs tests covering them, Linux test file sets vs project.yml, flaky steps, artifact contents.
