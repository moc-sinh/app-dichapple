# Checkpoint — 21/10/2026

Điểm dừng để phiên mới làm tiếp. Mọi mã đã được push lên nhánh `claude/funny-gates-s6pl2c`
(PR #1 nháp vào `claude/stoic-hopper-f60iid`). Không có thay đổi nào chưa commit.

## Tình trạng

- **Bản mới nhất:** 1.9.0 (Cách dịch Soniox, xem mục 1.9.0); 1.8.1 (run 62) đã gửi người dùng. Người dùng
  thấy 1.7.0 "khá ổn"; chưa thử 1.8.x/1.9.0 trên máy thật. Các mục bên dưới là lịch sử theo từng bản.
- **Mã sau 1.3.1 (1.4.0, đã gộp vào 1.5.0 và gửi người dùng):**
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

- **Nhật ký gõ bản 1.5.0 (54) từ iPhone (17/10):** mỗi phím dưới 2 ms, không mất phím, không
  "bàn phím bận" lúc gõ; mở bàn phím mất 26–48 ms. Vấn đề duy nhất: gõ lạc một phím trong từ Việt
  ("đưng" + d, "đu" + q) làm cả từ lật thành phím thô ("dduwngd") và phải xóa cả từ. **1.5.1** sửa:
  chữ lạc hiện sau từ ("đưngd"), ⌫ một lần là về "đưng" (`Composer.stray`, `isStrayLetterTypo`:
  chỉ khi các phím không thể là tiếng Anh — dd đầu từ, uw, aa, j trước phụ âm; gõ dấu cách thì vẫn
  về phím thô như trước, nên bộ 9.091 case và các sàn không đổi).

- **Rà soát production 1.6.0 (17/10, tối):** soát lại chữ lạc 1.5.1 (fuzz 200.000 lượt gõ ngẫu nhiên có
  ⌫/cách/xuống dòng: chữ trong ô luôn khớp bản sao của bàn phím, không crash) và các phần còn lại,
  mỗi phát hiện đọc lại trong mã trước khi sửa. Đã sửa:
  1. Ô email/link/tên đăng nhập/mã/số thẻ/số điện thoại (`keyboardType`, `textContentType`) gõ chữ thô,
     không Telex (`plainTyping`); ô chỉ nhận số mở sẵn trang số. Ô tên-hoặc-số vẫn có Telex.
  2. Nhật ký gõ: chữ số luôn thành `#`, ô ở mục 1 chỉ ghi `•`; lưu nhật ký theo một hàng đợi (bản cũ
     không ghi đè bản mới). Chép nhật ký/báo cáo ra bộ nhớ tạm chỉ trên máy này, hết hạn sau 15 phút.
  3. Dịch: kết quả không thay vào ô khác nếu đã sang ô nhập khác (`documentIdentifier`; khi đó báo
     "Bấm Dịch lại"); lịch sử lưu trước khi thay ô (bàn phím bị ẩn giữa chừng vẫn còn bản dịch trong app).
  4. Tin đối tác: chỉ đánh dấu "đã xử lý" khi dịch xong (lỗi mạng thì dòng "chạm để dịch" hiện lại);
     dòng "Nghĩa" sau khi Dịch không bị bản sao chép cũ đè; Dịch ngay không tự gửi thứ trông như mật
     khẩu/mã/link/email; tin chép lại không vào ngữ cảnh 2 lần.
  5. OpenAI: báo đúng lỗi hết tiền (insufficient_quota), khu vực (VPN), model, key sai; model từ chối
     thì nói rõ; đã yêu cầu JSON mà nhận chữ khác thì báo lỗi, không bao giờ chèn JSON thô vào ô chat.
  6. Lịch sử: một mục hỏng không làm mất cả danh sách. Bàn phím theo `.light` của app như bàn phím Apple.
  7. App: sửa chữ sau khi dịch thì bỏ kết quả cũ (không chép nhầm bản dịch của câu trước), đổi chiều
     hủy yêu cầu đang chạy; "Kiểm tra kết nối" không hiện kết quả của key cũ; Phím tắt tự dừng sau
     25 giây kèm lời báo (iOS cắt ở ~30 giây), và chạy lại trên bản dịch vừa chép thì không coi đó là
     tin đối tác.
  **Còn lại, cố ý chưa làm:** API key vẫn ở UserDefaults App Group (Keychain dùng chung cần
  keychain-access-groups, dễ hỏng khi AltStore ký lại); timer kiểm tra clipboard dùng target (đã
  invalidate khi ẩn, không rò thực tế); nhật ký gõ vẫn ghi khi tắt "Lưu lịch sử" (cần để chẩn đoán).

- **1.7.0: tab Hội thoại (20/10).** Người dùng muốn: khi chạy, lời mọi người hiện thành chữ tiếng Việt;
  chế độ **Đọc to + chữ** (đọc bản dịch qua loa) hoặc **Chỉ chữ**; nút **Tôi nói** (giữ hoặc chạm 2 lần)
  dịch câu của họ sang tiếng đối tác và đọc to bằng giọng OpenAI. Cách dịch trong menu ⋯: **Chính xác**
  (cắt câu theo khoảng lặng `SpeechSegmenter`, nhận giọng `gpt-transcribe` → dự phòng `gpt-4o-transcribe`,
  dịch bằng `Translator` với prompt "spoken", 3 câu song song, giữ đúng thứ tự) hoặc **Dịch liền**
  (`gpt-realtime-translate` qua WebSocket, một phiên → tiếng Việt cho người khác, một phiên → tiếng đối tác
  khi giữ nút). Đọc to: `gpt-4o-mini-tts` giọng marin/cedar, PCM 24 kHz. Chống tiếng vọng: Chính xác tạm
  không nghe khi điện thoại đọc (và chờ người nói ngừng mới đọc bản dịch của họ), Dịch liền bật khử vọng của
  Apple; thêm `EchoGuard` so chữ. Lọc câu "ảo" (`TranscriptFilter`, lấy từ VietNote). Luôn dùng micro + loa
  của iPhone. Biên bản tự lưu (Documents/Meetings), tóm tắt bằng `Translator.write`. Ý tưởng lấy từ 2 bộ
  người dùng gửi (Personal Translator V2: giao thức dịch trực tiếp, VAD + ngưỡng lặng; VietNote: lọc câu ảo,
  chống vọng theo chữ); mã viết lại bằng Swift. Test: `Tests/Conversation` (CI) + 2 mô phỏng cuộc họp trên
  Linux với dịch vụ giả (không vào repo). **Chưa thử trên máy thật**: độ nhạy micro trong phòng họp, Dịch
  liền với key của người dùng.

- **1.8.0: soát và tiết kiệm cho Hội thoại (20/10, tối).** Sửa **Đọc lại**: trước đây không đọc gì khi đã
  Dừng nghe, khi đang ở Chỉ chữ, hoặc khi phòng còn người nói (chờ 10 giây rồi bỏ); giờ đọc ngay ở mọi trường hợp
  (sau Dừng: `AudioIO.playAlone`, chỉ loa). Giọng đã làm được giữ (`VoiceCache`, 12 MB) nên đọc lại không trả tiền
  lần nữa. Giọng OpenAI được dặn đúng ngôn ngữ (đỡ giọng Anh khi đọc tiếng Việt). Phần chữ: câu của người khác chỉ
  hiện khi đã nhận ra chữ (không còn thẻ "Đang nghe…" hiện lên rồi biến mất vì tiếng ho), một dòng chờ ở cuối; tự
  cuộn theo câu mới trừ khi người dùng kéo lên đọc (iOS 18+, nút "Câu mới"); menu **Cỡ chữ**.
  Chi phí (giá 10/2026: gpt-transcribe 0,0045 USD/phút, Sol 2/10 USD mỗi triệu token, Luna 0,1/0,5,
  gpt-4o-mini-tts ~0,015 USD/phút, gpt-realtime-translate 0,034 USD/phút): trước đây Chính xác + Sol + đọc to
  ~3,4 USD/giờ, đắt hơn cả Dịch liền. Giờ **Dịch lời đối phương** mặc định Luna (Tiết kiệm), câu Tôi nói vẫn dùng
  model của Cài đặt; **Giọng iPhone** miễn phí (`AppleVoice`, AVSpeechSynthesizer.write → PCM 24 kHz, đi chung
  đường phát/chống vọng); Dịch liền không gửi tiếng lúc phòng im (`LiveGate`: 0,5 s trước, 3 s sau lời nói; chưa
  chắc OpenAI có tính tiền khoảng lặng hay không, gửi ít đi thì không thiệt). `CostMeter` cộng chi phí theo token
  OpenAI báo về và theo phút, lưu trong `Meeting.cost`, hiện ở menu ⋯ và Cuộc họp đã lưu.
  Test: `Tests/Conversation` 86 kiểm tra (thêm cache, gate, chi phí, usage). Mô phỏng cuộc họp trên Linux với dịch
  vụ giả và AudioIO giả (20 kiểm tra, không vào repo). Trên Linux, Tests/Conversation cần thay NaturalLanguage
  bằng bản giả; Swift 6.1.2 lấy từ mirror.gcr.io (download.swift.org bị chặn).
  **Chưa thử trên máy thật:** giọng iPhone đọc ra có đủ (AVSpeechSynthesizer.write), cuộn theo câu mới.

- **1.8.1: rà riêng phần dịch Hội thoại (20/10, khuya).** Người dùng chỉ muốn rà phần này. Đọc tay hết
  `App/Conversation`, sửa:
  1. Giữ **Tôi nói** quá 2 phút: lượt tự kết thúc ở `Listener` nhưng nút vẫn "đang nghe bạn", phần nói sau đó bị
     coi là lời người khác, thả tay không làm gì. Giờ không còn giới hạn: lượt dài được giao từng đoạn ≤ 20 giây,
     cắt ở chỗ lặng nhất trong 6 giây cuối (`SpeechSegmenter.split`, dùng chung với cắt câu dài), đoạn đầu được
     dịch ngay trong lúc còn nói; chỉ báo "Chưa nghe thấy bạn nói" khi cả lượt không có tiếng.
  2. Đọc to sai thứ tự: 3 câu dịch song song, câu dịch xong trước được đọc trước. Giờ câu nào cũng chờ (tối đa
     8 giây) các câu nói trước nó cùng phía (của bạn / của người khác) dịch xong (`waitForEarlier`). Câu của bạn
     xếp hàng nhận giọng theo đúng thứ tự (trước đây chèn lên đầu nên lượt dài bị đảo).
  3. Phòng nói liên tục: mỗi câu chờ khoảng lặng 10 giây nối tiếp nhau, giọng tụt lại rất xa. Giờ hạn 10 giây tính
     từ lúc câu dịch xong, quá hạn thì chỉ hiện chữ.
  4. Bấm Tôi nói lúc điện thoại đang đọc: 0,3 giây âm cũ đưa vào câu của bạn là giọng của chính điện thoại. Giờ bỏ.
  5. **Cuộc họp mới** bấm ngay sau Dừng làm mất câu cuối đang nhận/dịch (và vẫn trả tiền nhận giọng), bấm lúc đang
     tóm tắt thì bản tóm tắt ghi vào cuộc họp mới. Giờ chờ các câu cuối và bản tóm tắt (`canStartNewMeeting`).
  6. Dịch liền rớt kết nối: OpenAI báo đóng 2 lần (sự kiện `session.closed` rồi socket) nên mở 2 phiên mới, một
     phiên bị bỏ mà không đóng. Giờ phiên rớt được đóng và bỏ ngay, chỉ mở lại một lần. `LiveSession` giờ xử lý
     mọi thứ trên main queue (trước đây đụng `task`/`closing` từ hàng đợi của URLSession).
  7. Bỏ mã thừa: `AudioIO.playingAlone`, `PCM.int16([Float])`, `SpeechSegmenter.level`, `LiveGate.isOpen`.
  Test: `Tests/Conversation` 88 kiểm tra. Mô phỏng cuộc họp trên Linux (không vào repo, xem mục 1.8.0) 43 kiểm tra,
  thêm 6 cảnh cho các lỗi trên; chạy lại trên mã cũ thì trượt 10 kiểm tra, đúng các lỗi đã sửa.
  **Biết mà chưa làm:** Dừng ngay khi người khác vừa nói xong ở Dịch liền thì mất 1–2 giây bản dịch cuối (phiên bị
  đóng trước khi chữ về); Chính xác đọc liền các câu đang chờ trong lúc tạm không nghe (đúng như hướng dẫn ghi).

- **1.9.0: Cách dịch Soniox (21/10).** Người dùng thấy Dịch liền (OpenAI, ~2 USD/giờ) quá đắt, muốn rẻ mà vẫn
  nhanh như app VietNote. So sánh (tài liệu "Dịch giọng nói rẻ hơn cho Hội thoại" trên claude.ai): Soniox nhận giọng +
  dịch trực tiếp ~0,12–0,18 USD/giờ, chữ ra theo từng từ khi đang nói. Thêm Cách dịch thứ ba **Soniox: dịch liền,
  rẻ nhất** (`Engine.soniox`), key riêng ở Cài đặt › Soniox (`SharedStore.sonioxKey`); không có key OpenAI thì
  tự dùng giọng iPhone (tóm tắt vẫn cần key OpenAI). `SonioxTranslation.swift`: `SonioxSession` (WebSocket
  `wss://stt-rt.soniox.com/transcribe-websocket`, key ở header Bearer và trong tin cấu hình, model `stt-rt-v5`, lỗi
  400 về model thì tự thử `stt-rt-v4`; PCM16 24 kHz, `one_way` dịch sang một ngôn ngữ, hết câu sau 500 ms im,
  từ điển thuật ngữ → `context.terms` + `translation_terms`, keepalive khi không gửi tiếng 4 s), `SonioxTranscript`
  (gom token: chữ đã chắc + chữ đoán, hết câu ở `<end>`/`<fin>`, chờ bản dịch về; câu mới bắt đầu thì câu cũ xong).
  Giống Dịch liền: 2 phiên (người khác → tiếng Việt, Tôi nói → tiếng đối tác), `LiveGate` không gửi lúc phòng im,
  khử vọng của Apple. Để nhanh nhất: chữ và bản dịch hiện ngay từng từ; phòng vừa im (gate đóng) thì gửi
  `finalize`; thả nút Tôi nói thì `finalize` luôn (không chờ khoảng lặng); hết câu là đọc ngay (giọng được làm sẵn
  trong lúc người dùng còn nói, đọc khi thả nút). Giọng điện thoại đọc tiếng Việt nghe lại bị bỏ bằng `EchoGuard`.
  Chi phí ước tính 0,003 USD/phút gửi (`CostMeter.Rate.sonioxPerMinute`, chưa đối chiếu hóa đơn thật).
  Test: `Tests/Conversation` 112 kiểm tra (thêm phần Soniox). Mô phỏng trên Linux (không vào repo: bản sao
  ConversationModel bỏ UIKit, AudioIO/AppleVoice giả, `SonioxSession.open` không mở mạng): câu đối tác hiện từng từ
  rồi đọc, tiếng vọng bị bỏ, người Việt trong phòng chỉ hiện, Tôi nói đọc đúng thứ tự sau khi thả nút, Dừng giữ câu
  dở. **Chưa thử với Soniox thật** (máy chủ cloud không vào được soniox.com): định dạng tin nhắn lấy theo SDK chính
  thức `@soniox/client` 2.4.0 và plugin LiveKit trên npm.

## Việc tiếp theo (theo thứ tự)

1. Người dùng thử 1.9.0 trên iPhone thật với key Soniox: chữ có chạy ngay không, tiếng Việt có chuẩn không, lỗi
   gì hiện ra (nếu Soniox từ chối tin cấu hình thì thông báo hiện nguyên văn). Rồi thử tiếp phần 1.8.1: micro cách
   1–2 m, Dịch liền với key OpenAI, giọng iPhone, cuộn theo câu mới, giữ Tôi nói lâu.
2. Gửi link IPA dạng `https://github.com/moc-sinh/app-dichapple/actions/runs/<run>/artifacts/<id>`
   (job `build` xong là có artifact `MSTrans-ipa`; `keyboard-smoke` chạy sau, chụp màn Hội thoại để xem).
3. Nếu người dùng muốn: soát nốt các phần "chưa soát kỹ" ở mục 1.5.0 (ngoài Hội thoại).

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
