# Checkpoint — 27/10/2026

Điểm dừng để phiên mới (có thể ở tài khoản khác) làm tiếp. Mọi mã đã được push lên nhánh
`claude/funny-gates-s6pl2c` (PR #1 nháp vào `claude/stoic-hopper-f60iid`) của repo `moc-sinh/app-dichapple`.
Không có thay đổi nào chưa commit. Lịch sử trò chuyện không sang được tài khoản khác: file này là bàn giao.

## Bắt đầu phiên mới

1. Đọc hết file này, rồi `docs/HUONG-DAN.md` (phần Hội thoại) và `App/Conversation/ConversationModel.swift`.
2. Kiểm tra trên Linux trước mọi lần push (cần toolchain `swift-6.1.2-RELEASE-ubuntu24.04`):
   - Bàn phím: lệnh y như bước "Typing tests" trong `.github/workflows/ios.yml`.
   - Hội thoại: `SWIFT_BIN=<toolchain>/usr/bin bash Tests/ConversationSim/run.sh` chạy `Tests/Conversation`
     (250 kiểm tra) và mô phỏng cuộc họp (model thật, Soniox/Dịch liền/âm thanh/OpenAI giả trong
     `Tests/ConversationSim/Stubs.swift`). Thêm tình huống mới vào `Tests/ConversationSim/main.swift`.
3. Push lên `claude/funny-gates-s6pl2c` (fetch và gộp trước), xem CI ở tab Actions, gửi link IPA
   `https://github.com/moc-sinh/app-dichapple/actions/runs/<run>/artifacts/<id>` (artifact `MSTrans-ipa` của job `build`).

## Tình trạng

- **Bản mới nhất:** iPhone 1.16.0 và Android 1.5.0 (bộ thuật ngữ xuất nhập khẩu đông lạnh, hoa quả cho Soniox và GPT; xem mục
  1.16.0). Trước đó iPhone 1.15.0 và Android 1.4.0 (Tôi nói không phải chờ việc dịch của phòng, chữ gọn hơn gộp theo người
  nói, lời đối phương mặc định chỉ hiện chữ, GPT được dặn đổi số 万/亿 và tên Hán Việt; xem mục 1.15.0). Trước đó iPhone 1.14.0
  và Android 1.3.0 (bản dịch chữ theo kịp người nói: GPT dịch trước khi bản dịch của
  Soniox chậm, tiếng gửi đi không bị giữ lại, nhật ký tốc độ tách được Soniox nghe/chốt/dịch chậm; xem mục 1.14.0). Trước đó
  iPhone 1.13.0 và Android 1.2.0 (rà soát độc lập phần nghe dịch: 12 phát hiện, rồi rà soát lần hai trên
  chính các sửa: 5 phát hiện; sửa trên cả hai bản; xem mục 1.13.0). Commit 4b70f13: iOS run 77 xanh (IPA
  `https://github.com/moc-sinh/app-dichapple/actions/runs/40424082233/artifacts/12699018370`), Android run 6 xanh (release
  `android-1.2.0-6`); đã gửi người dùng cả hai link. Trước đó 1.12.1 (bản dịch tạm của Soniox gắn đúng câu theo thứ tự token, nhật ký đo độ trễ người dùng
  nhìn thấy; xem mục 1.12.1). 1.12.0 (chữ không trễ và dịch đúng ngữ cảnh hơn: GPT biết ai nói và bản dịch các câu trước, hỏi
  lại khi chậm, chủ đề cuộc họp; run 72, đã gửi link). Link IPA 1.11.0 đã gửi người dùng (run 71, lần chạy thứ 2: lần đầu
  GitHub không có máy macOS trong 15 phút nên huỷ, không phải lỗi mã). Trước đó 1.11.0 (họp nhiều người: tách câu theo
  người nói, GPT dịch lại, giọng đọc đuổi kịp, nhật ký tốc độ, chữ là chính; xem mục 1.11.0). Trước đó 1.10.1 (giọng đọc ngay khi có tiếng đầu tiên, xem mục 1.10.1); 1.10.0 (giọng Google, run 66, build
  xanh), 1.9.1 (sửa bàn phím tự tắt, run 65, cả keyboard-smoke xanh), 1.9.0 (Cách dịch Soniox, run 64) và 1.8.1 (run 62)
  đã gửi người dùng. Người dùng đã thử Soniox (1.9.0): chữ ổn, nhưng giọng đọc
  "chưa mượt, phản hồi chưa nhanh". Người dùng thấy 1.7.0 "khá ổn". Các mục bên dưới là lịch sử theo từng bản.
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

- **1.9.1: bàn phím không còn tự tắt lúc ô nhập đang được đặt lại (21/10).** Run 64 (1.9.0): bước "typing" của
  `keyboard-smoke` trượt vì bàn phím crash (EXC_BREAKPOINT, `textDidChange` → `UUID._unconditionallyBridgeFromObjectiveC`;
  luồng XPC đang ở `_didResetDocumentState`). UIKit khai báo `documentIdentifier` không bao giờ nil nhưng nó là nil
  trong lúc ô được đặt lại, và Swift đọc thẳng thì dừng chương trình. Lỗi có từ 1.6.0 (commit 2e3e655), không do
  Soniox; máy thật cũng có thể gặp. Giờ mọi chỗ đọc qua `currentField` (hỏi như một object nên nil chỉ là nil): chưa
  biết ô thì giữ ô cũ và lần đổi chữ sau mới so; Dịch đọc được nil rồi sau đó thấy ô thật thì báo "Bấm Dịch lại".
  Không có test Linux cho phần này (UIKit), `keyboard-smoke` trên CI là phép thử.

- **1.10.0: giọng Google Chirp 3 HD (21/10).** Người dùng gửi source app "My Translator" (Tauri, MIT; không vào repo,
  chỉ đọc) để tìm thêm giọng đọc. Trong đó: Edge TTS (giọng Hoài My/Nam Minh của Microsoft, miễn phí nhưng giả làm
  trình duyệt Edge và vượt token Sec-MS-GEC, không chính thức; chưa làm, đã hỏi người dùng), Google Chirp 3 HD, ElevenLabs
  (trả phí), Web Speech (như giọng iPhone). Benchmark của họ: Soniox chỉ trả bản dịch khi câu đã chốt
  (`is_final`), dịch chỉ thêm ~80 ms sau khi câu gốc chốt; giảm `max_endpoint_delay_ms` không làm nhanh hơn mà chỉ cắt
  câu vụn hơn (đo trên `stt-rt-v4`). Thêm 2 giọng **Aoede (nữ, Google)** và **Charon (nam, Google)**
  (`GoogleVoice.swift`): REST `texttospeech.googleapis.com/v1/text:synthesize`, key ở header `X-Goog-Api-Key`, voice
  `<locale>-Chirp3-HD-<tên>`, LINEAR16 24 kHz (bỏ header WAV bằng `PCM.body(ofWAV:)`). Key ở Cài đặt › Giọng Google
  (`SharedStore.googleKey`). Tiếng Trung phồn thể đọc bằng giọng `cmn-CN`; tiếng Mã Lai (không có giọng Chirp 3 HD)
  và ngôn ngữ Google từ chối (400 về voice/language, nhớ trong lần chạy) đọc bằng giọng iPhone; chưa có key Google
  thì cũng đọc giọng iPhone và màn Hội thoại nhắc dán key. Bắt đầu nghe với giọng Google thì gọi `voices.list` để mở
  sẵn kết nối và báo ngay nếu key sai/chưa bật API/chưa gắn thanh toán. Giá: miễn phí 1 triệu ký tự/tháng, sau đó
  30 USD/1 triệu (bảng giá Google, 10/2026); bộ đếm chi phí không tính giọng Google. Lỗi key sai đã thử thật
  (400 `API_KEY_INVALID`); chưa thử với key thật (người dùng chưa có key Google). Test: `Tests/Conversation` 128
  kiểm tra; mô phỏng trên Linux thêm phần giọng Google.

- **1.10.1: giọng đọc bắt đầu khi có tiếng đầu tiên (21/10).** Trước đây mỗi câu chờ làm xong cả giọng rồi mới đọc
  (giọng OpenAI ước tính 1–2 giây một câu). Giờ `SpeechServices.speak` đọc `audio/speech` dạng stream
  (`URLSession.bytes`, mỗi phần 0,1 s) và giọng iPhone đưa từng đoạn `write` ra ngay; `VoiceStream` (trong
  SpeechServices.swift) giữ tiếng đang về, `play` bắt đầu khi có 0,4 s (hoặc câu đã xong) rồi phát tiếp từng phần,
  chỉ phát mẫu trọn (byte lẻ để sang phần sau), dừng ngay khi bấm Tôi nói/Dừng. Giọng Google vẫn về một lần (REST
  không stream). Đọc lại sau Dừng chờ đủ cả câu (`whole()`). Test: `Tests/Conversation` 134 kiểm tra (thêm
  VoiceStream); mô phỏng: tiếng bắt đầu trước khi giọng làm xong (dưới 0,7 s), mọi phần phát đúng thứ tự, bấm Tôi
  nói giữa chừng thì không phát tiếp. Chưa đo trên máy thật.

- **1.11.0: họp từ 3 người trở lên (26/10).** Người dùng (dùng Soniox và Dịch liền, mức Tiết kiệm) báo bản dịch
  kém và chậm nhiều khi có từ 3 người. Nguyên nhân tìm được và cách sửa:
  1. Soniox gộp lời nhiều người, nhiều thứ tiếng vào một câu, và bản dịch Soniox đến từng mảnh không gắn với câu nào
     nên mảnh dịch câu trước rơi sang câu sau. `SonioxTranscript.splits` (bật khi có key OpenAI): bật
     `enable_speaker_diarization` cho phiên nghe phòng (phiên Tôi nói thì không), câu kết thúc khi đổi người nói, khi
     đổi thứ tiếng sau dấu câu (từ nước ngoài giữa câu như "好OK的", "cái quotation nhé" vẫn ở trong câu), và sau dấu
     chấm của câu dài từ 12 âm tiết, không chờ bản dịch của Soniox. Mảnh dịch đến muộn đi vào câu đã xong đang chờ
     (`awaiting`, tối đa 2; nhả khi bản dịch có dấu chấm hoặc 1,5 s không có mảnh mới), hiện tạm trên câu đó.
     Mỗi câu xong được GPT dịch lại (`translateAgain`: model Dịch lời đối phương cho người khác, model Cài đặt cho
     câu của mình) với 8 câu gần nhất kèm "(Người N)" (`context(before:)` lấy từ `entries`, gồm cả câu đang dịch),
     rồi mới ghi biên bản và đọc. GPT lỗi thì giữ bản Soniox; chậm quá 5 s thì đọc bản Soniox trước (`sayOnce`
     không đọc lại). Câu hiện "Người 1 · Tiếng Trung"; biên bản, tóm tắt cũng ghi (`Meeting.Line.speaker`).
  2. Dịch liền gộp mọi thứ trong 2,5 s vào một câu, phòng nói liên tục thì một câu kéo dài mấy phút.
     `LiveCaptions` (LiveTranslation.swift): cắt sau dấu chấm khi câu đã dài 3 s (sau dấu phẩy khi 10 s, bất kỳ đâu
     khi 20 s), mảnh dịch đi vào câu đã xong cho đến khi số dấu kết câu bằng câu gốc hoặc 3 s không có mảnh mới.
     Câu người khác xong thì GPT dịch lại phần chữ (giọng trực tiếp đã đọc thì thôi); câu của mình giữ như đối tác
     đã nghe.
  3. Giọng đọc tụt lại vô hạn: câu nào cũng đọc hết theo thứ tự. Giờ `AudioIO` có `AVAudioUnitTimePitch` (giữ cao độ)
     và `backlog`; câu người khác đọc nhanh 1,2× khi chậm 2,5 s hoặc có câu chờ, 1,35× khi chậm 5 s hoặc 2 câu chờ;
     chậm quá 6 s mà có câu mới hơn thì bỏ đọc (chữ vẫn hiện). Dịch liền: tiếng tồn quá 2/4 s thì 1,2/1,35×, quá 6 s
     thì bỏ bớt.
  4. `EchoGuard` so từng từ đơn: trong cuộc họp điện thoại đọc nhiều, câu của đồng nghiệp gồm từ thông dụng ("của
     chúng tôi") bị coi là tiếng vọng và mất. Giờ so cặp từ (cặp chữ Hán), câu ngắn phải trùng hết.
  5. Chính xác: lời nhắc nhận giọng chỉ còn Thuật ngữ (2 câu trước bằng tiếng Trung từng làm câu tiếng Việt bị viết
     thành chữ Hán); câu dài nhất 10 s (trước 15 s); khoảng ngừng hết câu ngắn dần sau 3 s nói, còn 0,35 s ở 8 s
     (`SpeechSegmenter.pause(after:)`); dịch song song 4 câu; lọc nói nhanh nâng lên 11 âm tiết/giây.
  6. **Nhật ký tốc độ** (`SpeedLog`, menu ⋯ › Sao chép nhật ký tốc độ): mỗi câu ghi Soniox nhận chậm bao lâu (giây
     tiếng đã gửi trừ `end_ms` của chữ đã chốt), GPT mất bao lâu, đọc sau bao lâu/nhanh bao nhiêu/bỏ câu nào; không
     ghi lời nói. 400 dòng gần nhất, mất khi tắt app.
  7. Người dùng nói rõ (26/10): **chữ là chính**, giọng đọc chỉ là một tính năng phụ; họp 4–5 người vẫn phải theo dõi
     được bản dịch chữ đầy đủ, đúng nghĩa, không trễ. Thêm: Soniox nhận tiếng **liên tục** (không qua `LiveGate`), người
     nói nhỏ/ngồi xa mà mức âm không vượt ngưỡng không còn bị bỏ, Soniox tự nhận chỗ hết câu (bỏ `finalize` khi phòng
     im); chi phí Soniox thành theo giờ họp (~0,18 USD). **Khử tiếng vọng của Apple chỉ bật khi máy vừa nghe vừa nói**
     (`echoCancelling`: Dịch liền, hoặc Soniox + Đọc to); Soniox + Chỉ chữ thì tắt để micro nghe xa hơn, và lúc máy đọc
     (Tôi nói, Đọc lại) thì phòng được gửi im lặng; đổi Đọc to/Chỉ chữ khi đang nghe thì mở lại micro
     (`AudioIO.setEchoCancelling`). Mảnh dịch Soniox chỉ đi vào câu đã xong trong 1,5–4 s sau khi câu xong (câu bị cắt
     vì đổi người thường không có dấu chấm, trước đây giữ mảnh dịch của câu sau mãi). Nói liền không dấu chấm thì cắt ở
     dấu phẩy khi đã 40 âm tiết.
  Menu "Dịch lời đối phương" bật cho mọi Cách dịch; ước tính chi phí cộng tiền GPT dịch lại. Test: `Tests/Conversation`
  175 kiểm tra. Mô phỏng trên Linux (model thật, Soniox/Dịch liền/AudioIO giả, `Tests/ConversationSim/run.sh`): 3 người nói xen
  kẽ, câu Việt, tiếng vọng, Tôi nói, GPT lỗi, GPT chậm, 8 câu dồn dập (đọc nhanh rồi bỏ câu cũ), Dịch liền cắt câu và
  gán mảnh dịch, tiếng tồn quá 6 s, câu của mình không qua GPT. **Chưa thử trên máy thật**: chờ người dùng gửi nhật
  ký tốc độ sau một cuộc họp thật.

- **1.12.0: chữ không trễ, dịch đúng ngữ cảnh (26/10, tối).** Người dùng nhắc lại: tối ưu phần dịch ra chữ không trễ,
  chất lượng bản dịch và ngữ cảnh dịch. Chưa có nhật ký tốc độ, nên chỉ sửa những chỗ đọc mã và tài liệu Soniox thấy rõ:
  1. **Ngữ cảnh GPT** (`SpokenContext`, `Translator.translateSpoken`, `Prompt.spoken(into:topic:)`/`spokenInput`): mỗi
     câu gửi kèm 8 câu trước dạng `Người 1: 原文 → bản dịch` (bản dịch chỉ khi câu đó đã xong), `<message speaker="Người 2">`
     ("User" là câu của mình, "Participant" khi không tách người nói). Lời nhắc thêm: câu có thể bị cắt giữa chừng, dịch
     đúng phần đó nối tiếp bản dịch trước, không thêm, không lặp, **không tóm tắt hay bỏ ý**; trong họp người ta nói với
     nhau, chọn xưng hô theo ai nói với ai; tên và thuật ngữ dịch giống các câu trước. Bàn phím vẫn dùng `translate` cũ.
  2. **Hỏi GPT lần hai** (`gptTranslation`): Luna chưa trả lời sau 2,5 s (Sol 5 s) thì gửi thêm một yêu cầu y hệt, lấy
     câu về trước, huỷ câu kia; chỉ báo lỗi khi cả hai lỗi. Trước đây một kết nối treo giữ "Đang dịch…" tới 90 s
     (`timeoutIntervalForRequest` của Translator). Nhật ký tốc độ ghi "GPT chưa xong sau 2,5 s: hỏi thêm lần nữa".
  3. **Mở sẵn kết nối OpenAI** (`ConversationServices.warmUp` → `Translator.warmUp`, HEAD không key, tối đa 45 s/lần) khi
     bắt đầu nghe và khi có người bắt đầu một câu, để GPT không chờ bắt tay mạng lúc câu vừa hết.
  4. **Chủ đề cuộc họp** (⋯ › Chủ đề cuộc họp, `setTopic`, lưu trong UserDefaults và `Meeting.topic`, ≤ 200 ký tự): đưa
     vào lời nhắc GPT ngay, vào `context.general` của Soniox khi bắt đầu nghe (kèm `setting` và hồ sơ Cài đặt › Về bạn:
     tài liệu Soniox "Context" nói `general` giúp cả nhận giọng lẫn dịch), vào biên bản ("Chủ đề: …") và tóm tắt.
  Tài liệu Soniox đọc được từ máy cloud (soniox.com/docs): `stt-rt-v4` giờ là bí danh của `stt-rt-v5`; endpoint detection và
  finalize làm giảm độ đúng khi tách người nói; v5 có thêm `endpoint_latency_adjustment_level`, `endpoint_sensitivity`
  (chưa dùng: `max_endpoint_delay_ms` 500 đã chặn trễ cuối câu ở 0,5 s); trường `api_key` trong tin cấu hình bị bỏ từ
  15/01/2027 (app đã gửi key ở header Bearer). Test: `Tests/Conversation` 180 kiểm tra; mô phỏng thêm: ngữ cảnh GPT có
  người nói/bản dịch/chủ đề, câu của mình là "User", GPT treo thì lần hỏi thứ hai về kịp (tắt hỏi lại thì 4 kiểm tra
  trượt), mở sẵn kết nối, Soniox nhận chủ đề, biên bản ghi chủ đề. **Chưa thử trên máy thật.**

- **1.12.1: bản dịch tạm của Soniox đúng câu (26/10, khuya).** Người dùng: "tối ưu phần dịch Soniox bằng chữ, quan trọng
  nhất". Đọc SDK chính thức của Soniox (npm `@soniox/client` 2.4.0, PyPI `soniox` 2.10.0; GitHub ngoài repo này bị chặn
  từ máy cloud): `render_tokens` của SDK in mỗi đoạn dịch dưới chữ gốc ngay trước nó; tài liệu "Speech-to-text
  translation": bản dịch "generated after their spoken tokens and follow the same sequence". Tức là Soniox gửi chữ gốc
  và bản dịch thành một dòng, chốt dần từ đầu. `SonioxTranscript` (chế độ `splits`) giờ gán theo thứ tự: mảnh dịch đã
  chốt thuộc câu có chữ gốc chốt gần nhất (`owner`, có thể là câu vừa bị cắt ở dấu chấm, giữ trong `awaiting`); mảnh
  đoán thuộc câu có chữ đoán đứng trước nó trong cùng tin, không có thì như mảnh đã chốt; chữ gốc của câu sau vừa chốt
  là câu trước đã đủ bản dịch (`released`, vẫn hiện mảnh cuối trong `previews`). Bỏ cách gán theo thời gian của 1.11.0
  (`take(now:)`, cửa sổ 1,5–4 s): khi một người bị người khác chen ngang, mảnh dịch đầu tiên của người sau bị gán vào câu
  người trước vài giây, câu người sau không có tiếng Việt và bản dịch tạm thiếu đầu (chạy mô phỏng mới trên mã cũ:
  "Thời hạn giao hàngKhông vấn đề." dính vào câu người 1, câu người 2 trống). Không có key OpenAI (`splits` tắt): như cũ.
  **Nhật ký tốc độ** đo đúng độ trễ người dùng thấy, theo giây âm thanh của phiên Soniox (`start_ms`/`end_ms` của token so
  với số giây đã gửi, `HeardTime`): dòng Soniox thêm `chữ hiện sau X s` (từ chữ đầu tiên của câu), dòng GPT thêm
  `bản dịch đầu tiên sau X s` (Soniox, hoặc GPT nếu Soniox chưa có) và `bản GPT sau X s từ lúc ngừng nói`. Chưa đổi:
  nút `endpoint_latency_adjustment_level`/`endpoint_sensitivity` của v5 (Soniox cảnh báo câu vụn hơn, tách người nói kém
  hơn); chữ đoán của người mới nói vẫn hiện tạm ở cuối thẻ người trước cho tới khi Soniox chốt chữ đầu của họ.
  Test: `Tests/Conversation` 183 kiểm tra; mô phỏng gửi bản dịch đúng thứ tự Soniox, thêm cảnh người 2 chen người 1.
- **1.10.1 đã build xanh ở run 67** (cả keyboard-smoke), link IPA đã gửi người dùng.

- **Bản Android 1.0.0 (22/10, nhánh `claude/android-apk`, tách từ d8cc27f).** Người dùng nhờ làm bản tương tự trên
  Android và gửi link APK. Mã ở `android/`:
  - `core` (Kotlin thuần, chạy test ở bất kỳ đâu): Telex/`Composer`/`TypedText` chuyển từ Swift (qua đủ bộ 9.091 case
    `Tests/Suite`), dịch OpenAI, ngữ cảnh, lịch sử, thuật ngữ, Hội thoại (`ConversationModel`, Soniox, Dịch liền, giọng
    OpenAI/Google/điện thoại, biên bản). Test: `cd android && gradle :core:test` (trong phiên cloud cần thêm init
    script trỏ Maven Central qua mirror `maven-central.storage-download.googleapis.com`, vì repo.maven.apache.org
    trả 429). Thân trả lời HTTP đọc trên luồng OkHttp (`Call.text()`), không trên luồng chính.
  - `app` (chỉ có trong build khi có Android SDK): 5 tab như iPhone (View dựng bằng code, không androidx), bàn phím
    `keyboard/MSTransKeyboard.kt` (InputMethodService: thanh Dịch, dòng báo, bảng ngữ cảnh, đọc tin đã sao chép qua
    clipboard) + `keyboard/KeyView.kt` (vẽ phím, rollover 2 ngón, giữ để chọn dấu, phím cách kéo con trỏ, Shift
    2 lần khóa hoa, xóa lặp theo từ). Bàn phím theo dõi ô bằng vị trí con trỏ chờ đợi (`expected`) trong
    `onUpdateSelection`; báo khác thì đọc lại ô như `fieldChanged` của iPhone. "Dịch bằng MSTrans" trong menu chọn
    chữ/Chia sẻ (`ProcessTextActivity`) thay cho Chạm lưng iPhone.
  - Kiểm tra biên dịch không cần SDK: dự án nhỏ trong scratchpad biên dịch mã `app` với `org.robolectric:android-all`
    (15-robolectric-13954326, compileOnly) và một `R` giả sinh từ tên tệp trong `res/` (không có trong repo).
  - CI: `.github/workflows/android.yml` (test core → `assembleRelease` → release `android-<version>-<run>` với tệp
    `MSTrans.apk`). Link cố định cho người dùng: `https://github.com/moc-sinh/app-dichapple/releases/latest/download/MSTrans.apk`.
    `ios.yml` bỏ qua các lần push chỉ đổi `android/`.
  - Ký APK bằng khóa cố định `android/app/mstrans-sideload.p12` (mật khẩu `mstrans-sideload`, alias `mstrans`) để bản
    sau cài đè bản trước; có thể thay bằng secret (`MSTRANS_KEYSTORE`…). Khóa nằm trong repo công khai: chỉ cài APK
    từ link release của repo này.
  - Chưa thử trên máy Android thật.

- **Android 1.1.0 (26/10, khuya): Hội thoại bằng iPhone 1.12.1.** Người dùng: "chỉ làm phần nghe dịch, tối ưu tốc độ
  dịch ra chữ và chất lượng bản dịch, rồi xuất cả IPA lẫn Android". Nhánh `claude/android-apk` đã được **gộp vào
  `claude/funny-gates-s6pl2c`** (merge f564a08), mọi việc Android từ đây làm trên nhánh này. Chuyển sang Kotlin
  (`android/core/.../conversation`): `SonioxTranscript` (splits, gán bản dịch theo thứ tự `owner`/`awaiting`/`released`,
  người nói, `start_ms`/`end_ms`), `configMessage` (diarization, `context.general`), `LiveCaptions`, `EchoGuard` theo cặp
  từ, `SpeechSegmenter.pause(said)` và câu dài nhất 10 s, `TranscriptFilter` 11 âm tiết/giây, `Meeting` (speaker, topic,
  `who`), `CostMeter.perHour(translatesAgain)`, `SpeedLog`, `Translator.translateSpoken`/`SpokenContext`/`Prompt.spoken`/
  `spokenInput`/`warmUp`, và `ConversationModel` viết lại theo bản Swift (GPT dịch lại câu Soniox và Dịch liền, hỏi GPT lần
  hai sau 2,5 s bằng `Channel` + `withTimeoutOrNull`, mở sẵn kết nối, chủ đề, giọng đọc nhanh/bỏ câu cũ, khử vọng chỉ khi
  máy vừa nghe vừa nói). `AudioPort` thêm `setEchoCancelling`, `backlog`, `setRate`; `AndroidAudio`: đổi nguồn micro khi
  đang nghe (VOICE_COMMUNICATION + AEC ↔ VOICE_RECOGNITION), tốc độ đọc bằng `PlaybackParams` (giữ cao độ). Màn
  `ConversationScreen`: "Người N · …", ⋯ › Chủ đề cuộc họp (hộp nhập), ⋯ › Sao chép nhật ký tốc độ, Dịch lời đối phương cho
  mọi Cách dịch, lời hướng dẫn và chi phí như iPhone, biên bản ghi `line.who`. Test: `ConversationPartsTest` (các kiểm tra
  của iPhone), `ConversationSonioxTest` (máy chủ Soniox giả bằng MockWebServer + model thật: 3 người, chen ngang, GPT treo,
  Tôi nói, nhật ký; đưa cách gán theo thời gian cũ vào thì trượt). **Chạy được trên máy cloud**: JDK 21, Gradle
  `/opt/gradle-8.14.3`, init script trỏ Maven Central qua `maven-central.storage-download.googleapis.com` (thêm cả cho
  `buildscript`), Android SDK tải từ dl.google.com (cmdline-tools → `platforms;android-35`, `build-tools;35.0.0`) nên
  build được cả `:app:assembleRelease` tại chỗ (không có KVM nên không chạy máy ảo). CI Android run 4 xanh, phát hành
  `android-1.1.0-4`.

- **1.13.0 + Android 1.2.0: rà soát độc lập phần nghe dịch (26/10, đêm).** Người dùng: "kiểm tra thật kĩ, chỉ phần nghe
  dịch, tối ưu tốc độ ra chữ và bản dịch đúng nghĩa, rồi xuất IPA lẫn Android". Một trợ lý rà soát đọc toàn bộ đường đi của
  chữ (Soniox, Dịch liền, GPT) và viết 13 tình huống chạy trên model thật với stub; 12 phát hiện, mỗi cái kiểm chứng lại trước
  khi sửa. Đã sửa (iPhone và Android giống nhau):
  1. **Tiếng vọng xoá nhầm câu đối tác**: `EchoGuard` ghi lại *lúc* máy đọc (`spoke`/`stopped`), coi là tiếng vọng chỉ khi câu
     được nói trong lúc máy đọc hoặc 1,5 s sau (`isEcho(from:to:)`); chỉ ghi tiếng mà phiên nghe phòng thật sự nghe (Soniox:
     câu của đối phương khi Đọc to; câu của người dùng thì phòng nhận im lặng). Giờ của câu Soniox đổi sang giờ máy bằng
     `saidTime` (số giây đã gửi). Trước đây "好的，下周一确认订单" ngay sau khi máy đọc "我们下周一确认订单" bị xoá.
  2. **Phòng bị điếc khi giữ Tôi nói**: phiên Soniox của phòng giờ nghe cả lượt của người dùng; `MyVoiceFilter` bỏ giọng người
     dùng (trong lượt, chỉ giữ giọng đã biết là đối tác: từng nói tiếng khác tiếng Việt ngoài lượt). Khi máy đọc câu của người
     dùng, phòng vẫn nhận im lặng (để giọng máy không hiện lên rồi biến mất).
  3. **Thứ tiếng của câu là của chữ đầu**: giờ là thứ tiếng có nhiều âm tiết nhất (`syllables`, `majority`), cả cho Tự động.
  4. **Mất mạng mất tiếng**: mỗi bên giữ 15 s âm thanh đã gửi (`SoundTrail`), phiên mới gửi lại từ chữ cuối đã chốt
     (`settledEnd`, `finishSettled`), mở lại ngay rồi 0,5/1/2/3/5/5/5 s; âm thanh lúc chưa có phiên nằm chờ trong trail.
  5. **Bấm dừng mất mấy chữ cuối**: gửi nốt đoạn âm thanh cuối, `finalize` cả hai phiên, chờ `<fin>` (tối đa 2 s,
     `draining`), rồi mới đóng; Cuộc họp mới chờ xong việc này.
  6. **Bản dịch tạm bị lặp** ("ĐượcĐược rồi."): câu kết thúc ngay ở `<end>`/`<fin>`, bản chờ dịch chỉ giữ phần đã chốt.
  7. **GPT lỗi nhanh làm đóng băng bản dịch Soniox còn thiếu**: hỏi lại một lần sau 1,5 s, trong lúc đó vẫn nhận tiếp bản dịch
     Soniox; lỗi lần hai thì giữ bản Soniox đầy đủ.
  8. **Câu bị cắt ở mỗi chỗ ngừng 0,5 s và ở một chữ gán nhầm người**: `<end>` không có dấu kết câu (. ? ! 。 ？ ！) là chỗ
     ngừng (`ended`), người đó nói tiếp thì cùng câu, không thì model kết thúc sau 1,2 s không có chữ mới (`pauseEnds`, đo từ
     lúc chữ đổi lần cuối), người khác nói thì kết thúc ngay; vài chữ của người khác giữa câu được giữ (`switching`) tới khi họ
     nói đủ 3 âm tiết, người cũ nói tiếp thì chữ đó ở lại câu cùng bản dịch của nó.
  9. **Prompt**: lời người khác dùng prompt cuộc họp riêng (`Prompt.meeting`, không còn "tin đối tác viết cho người dùng"); bỏ
     câu "never repeat what an earlier translation already says", thay bằng dịch lại đầy đủ số, giá, ngày, cam kết được nhắc lại.
  10. **Thả Tôi nói mất tiếng cuối**: 0,35 s sau khi thả vẫn gửi cho phiên của người dùng (`mineTail`), rồi mới `finalize`
      (Dịch liền: 0,35 s tiếng thật rồi im lặng tới 1,5 s).
  11. **Dịch liền**: cổng gửi tiếng mở cả với giọng nhỏ ở xa (`audible`: trên nền 6 dB, tối thiểu −58 dBFS, 3 khung liền, giữ
      0,3 s); câu kết thúc bằng dấu chấm được GPT dịch sau 0,8 s im lặng thay vì 2,5 s.
  12. **Trễ không cần thiết**: iPhone chỉ dựng chữ biên bản khi bấm chia sẻ (`MinutesText`), cuộn có hiệu ứng chỉ khi có câu
      mới; yêu cầu GPT thứ hai (sau 2,5 s) đi qua kết nối riêng (`Translator.spare`, Android `Http.spare`).
  Chưa làm, có lý do: để bộ nhận dạng chữ ghi đè thứ tiếng Soniox nghe (dễ nhầm tiếng Nhật toàn chữ Hán); theo dõi cuộn tay trên
  iOS 17 (`FollowsBottom` chỉ chạy iOS 18+, không thử được nếu không có máy); đuôi 0,35 s cho Chính xác (lượt nói giữ trên hàng
  đợi âm thanh, chỉ phần trong bộ đệm micro có thể thiếu). Test: `Tests/Conversation` 214 kiểm tra (bản cũ hỏng 17 kiểm tra mới,
  gồm "ĐượcĐược rồi."); mô phỏng thêm tiếng vọng đúng lúc máy đọc, đối tác xác nhận được giữ, câu ngừng sau dấu phẩy được nối,
  câu kết thúc sau 1,2 s, GPT lỗi rồi hỏi lại, lượt Tôi nói (phòng vẫn nghe, lọc giọng, đuôi 0,35 s), mất kết nối giữa câu,
  dừng khi Soniox chưa trả xong; thử đột biến từng sửa chính đều bị bắt. Android: cùng kiểm tra trong `ConversationPartsTest`,
  `ConversationSonioxTest` (máy chủ Soniox giả), 26 test Kotlin qua, APK build tại chỗ.
  **Rà soát lần hai** (trợ lý độc lập soát chính các sửa trên, tái hiện bằng mô phỏng): 5 phát hiện, sửa cả hai bản trước khi
  gửi link:
  1. Một chữ tiếng Việt dưới số của đối tác trong lượt người dùng ("vâng", hay chữ đầu của người dùng bị gán nhầm số) làm đối
     tác thành "người dùng" tới hết buổi → `MyVoiceFilter` đếm âm tiết từng giọng: là đối tác khi đã nói ≥ 3 âm tiết tiếng khác
     ngoài lượt và nhiều hơn số âm tiết tiếng Việt giọng đó nói trong lượt.
  2. Phiên phòng mở lại thì Soniox đánh số người nói lại → `newSession()` xoá các số đã biết (giữ các lượt).
  3. Hết 1,2 s ngừng mà chữ nói tiếp còn là chữ đoán → câu kết thúc kèm chữ đoán, chốt xong chữ đó lặp ở câu sau → không kết
     thúc chỗ ngừng khi còn chữ đoán (`hasGuess`; vẫn có mốc 8 s).
  4. Bấm Tôi nói ngay sau câu hỏi của đối tác: phòng nghe cả giọng người dùng nên Soniox không gửi `<end>`, câu hỏi chờ tới 8 s
     → bấm nút kết thúc câu của phòng như `<end>` (`endHere`), trong lượt câu của phòng kết thúc sau 1,2 s không có chữ mới;
     không có key OpenAI (bản dịch của Soniox đến sau `<end>`) thì phòng nhận im lặng trong lượt như cũ.
  5. Chữ đoán bị Soniox rút lại (tin có danh sách token rỗng, trước bị bỏ qua) nằm lại trên thẻ rồi thành câu ma sau 8 s →
     tin rỗng được báo khi đang có chữ đoán, thẻ không còn chữ thì bỏ.
  Nhỏ: giờ câu Soniox tính từ lúc gửi đoạn âm thanh cuối (`sonioxSentAt`, câu chốt lúc đang chờ sau Dừng không lệch); Dịch liền
  chỉ dùng mức "nghe thấy" khi máy không đọc (tiếng vọng còn sót); iOS theo dõi `draining` để nút Cuộc họp mới bật lại. Mô phỏng
  và test Kotlin thêm đủ 5 cảnh; đột biến từng sửa đều bị bắt. Lưu ý cho mô phỏng: micro thật gửi âm thanh liên tục, cảnh nào
  cần giờ của câu thì phải `feed` trong lúc chờ.

- **1.14.0 + Android 1.3.0: bản dịch chữ theo kịp người nói (27/10).** Người dùng (sau 1.13.0): "phần dịch giọng ra chữ không
  theo kịp người nói", vẫn chưa gửi nhật ký tốc độ. Đọc lại cả đường đi micro → Soniox → màn hình và tài liệu Soniox
  (`soniox.com/docs/llms-full.txt` tải được từ máy cloud): bản dịch của Soniox đến sau chữ gốc nó đã **chốt** ("generated after
  their spoken tokens"); chữ chốt dần, hoặc ở `<end>`/`finalize`; tham số `max_non_final_tokens_duration_ms` (giữ chữ chưa chốt
  tối đa bao lâu) có trong API nhưng không có trong tài liệu (chỉ có thông báo lỗi; bài best-practices cũ khuyên đặt 6000 cho phòng
  ồn để tách người nói tốt hơn, tức mặc định thấp hơn, có thể 4000): trong phòng ồn nhiều người, bản dịch Soniox có thể chậm vài
  giây sau chữ gốc. Chưa đặt tham số này (không có tài liệu, có thể làm kém tách người nói); `endpoint_latency_adjustment_level`/
  `endpoint_sensitivity` cũng chưa dùng. Soniox có máy chủ Nhật (`stt-rt.jp.soniox.com`) nhưng cần project riêng có vùng Nhật
  (người dùng phải xin ở console) nên chưa làm. Đã sửa (iPhone và Android như nhau):
  1. **GPT dịch trước** (`ahead`, `translateAhead`): câu của phòng đang nói, chữ đầu tiên chưa có bản dịch đã hiện quá 1,2 s
     (`aheadWait`, tính theo giờ máy từ lúc chữ hiện, `seen`), thì hỏi GPT Luna (luôn Luna, nhanh) dịch cả câu tới lúc đó (cả chữ
     đoán); đang hiện bản GPT thì hỏi tiếp ngay khi nói thêm; Soniox `<end>` không dấu chấm (ngừng giữa câu) thì hỏi ngay. Một câu
     hỏi một lúc, tối đa 12 lần một câu, mỗi lần 4 s (`aheadPatience`, quá thì bỏ và hỏi lại). Hiện bản nào dịch tới xa hơn trong
     câu (`SonioxTranscript.Sentence.coveredMs`: mảnh dịch phủ tới chữ đứng trước nó trong luồng); đã hiện bản GPT thì giữ tới khi
     Soniox vượt quá 1,5 s (`aheadMargin`), để chữ không nhảy qua lại. Hết câu mà chữ đúng như lần hỏi gần nhất (và câu dịch bằng
     Luna) thì câu trả lời đó là bản dịch cuối, hiện ngay (`aheadTranslation`, chờ nếu đang về); GPT cuối lỗi hai lần thì giữ bản
     dịch trực tiếp đi xa nhất (`keptTranslation`). Ước tính chi phí thêm một lần Luna mỗi câu (~0,08 USD/giờ).
  2. **Tiếng gửi đi không bị giữ**: `Listener` gửi cả phần micro vừa đưa (≥ 20 ms), không cắt thành từng 0,1 s (phần lẻ từng chờ
     0,1 s cho phần sau, tức trễ tới 0,1 s mỗi lần); `LiveGate` tính theo số mẫu (0,5 s trước, 3 s sau) thay vì số khúc.
  3. **Bớt vẽ lại**: `update` chỉ ghi khi câu thật sự đổi (Soniox gửi lại chữ đoán y hệt trong mỗi tin).
  4. **Nhật ký tốc độ**: `SonioxEvent.processed` (`final_audio_proc_ms`, `total_audio_proc_ms` có trong mọi tin): mỗi nửa phút một dòng
     "Soniox nửa phút: nghe chậm TB/tối đa (tiếng đã gửi trừ phần Soniox đã nghe: mạng, Soniox), chốt chậm TB/tối đa (chữ đoán chờ
     chốt), GPT dịch trước N lần, trả lời TB". Mỗi câu thêm "chữ chậm tối đa" (chữ hiện sau lời nói bao lâu), "bản dịch Soniox chậm
     tối đa", "GPT dịch trước chậm tối đa", "hỏi GPT dịch trước N lần", và "GPT Luna đã dịch trước, dùng ngay". **Đọc nhật ký**: nghe
     chậm lớn = mạng/upload (384 kbps PCM 24 kHz; có thể chuyển 16 kHz hoặc nén); chốt chậm lớn = Soniox giữ chữ đoán lâu (thử
     `max_non_final_tokens_duration_ms`, có tự quay lại nếu Soniox từ chối); bản dịch Soniox chậm mà chốt nhanh = Soniox dịch theo cụm;
     GPT dịch trước chậm = mạng tới OpenAI.
  Test: `Tests/Conversation` 234 kiểm tra (độ phủ bản dịch, `processed`, `LiveGate` khúc 0,05 s); mô phỏng thêm: tiếng lẻ gửi ngay,
  Soniox dịch chậm → GPT dịch trước rồi hỏi tiếp, Soniox vượt ít không giành lại, hết câu dùng ngay câu trả lời (không hỏi lần hai),
  Soniox dịch kịp → không hỏi GPT trước, ngừng giữa câu → dịch sẵn, GPT dịch trước treo → bỏ sau 4 s và hỏi lại; bỏ từng sửa (gửi
  0,1 s, không dịch trước, không dùng lại, không bỏ câu treo, không giữ bản GPT, không hỏi lúc ngừng) đều làm mô phỏng trượt.
  Android: cùng kiểm tra trong `ConversationPartsTest`, `ConversationSonioxTest.translatesAhead` (máy chủ Soniox giả), 27 test qua.
  **Chưa thử trên máy thật.** Không đổi gì cho Dịch liền ngoài `LiveGate` (chữ của nó đã hiện ngay khi OpenAI gửi).
  Commit 0700047: iOS run 78 xanh (IPA `https://github.com/moc-sinh/app-dichapple/actions/runs/40477337976/artifacts/12720529179`),
  Android run 7 xanh (release `android-1.3.0-7`); đã gửi người dùng cả hai link.

- **1.15.0 + Android 1.4.0: Tôi nói không chờ phòng, chữ gọn hơn (27/10).** Người dùng (dùng Android 1.3.0, "khá ổn"): bấm Tôi nói
  "hay bị trễ do app chạy dịch tiếng của mọi người"; không cần phần đọc lại trong văn bản dịch (nghĩ tới Chỉ chữ); muốn chữ dịch
  "thông minh hơn", nhỏ hơn, đọc được nhiều nội dung; tối ưu tốc độ và chất lượng, nhất là tiếng Anh và tiếng Trung. Chưa có nhật ký
  tốc độ mới, nên chỉ sửa những gì đọc mã thấy chắc chắn:
  1. **Android xếp hàng yêu cầu**: OkHttp mặc định chỉ chạy 5 yêu cầu cùng lúc tới một máy chủ (`Dispatcher.maxRequestsPerHost`),
     phần còn lại chờ. Họp đông: GPT dịch lại câu của phòng, hỏi thêm khi chậm, dịch trước, giọng đọc (Đọc to) chiếm đủ 5, câu
     Tôi nói (GPT rồi giọng) phải chờ. Giờ 32 (`Http.MAX_REQUESTS_PER_HOST`; chung một kết nối HTTP/2). `HttpTest`: 8 yêu cầu chậm
     đang chạy, yêu cầu thứ 9 về ngay (mã cũ: chờ 1,3 s). iPhone (URLSession, HTTP/2) không bị.
  2. **Câu của bạn đọc trước** (cả hai app, khi bật Đọc to lời đối phương): câu của phòng chờ đọc trong lúc câu của bạn còn đang
     nghe/nhận/dịch (`mineSettling`), và bị bỏ (chỉ hiện chữ) nếu có câu của bạn được giao đọc sau nó (`mineQueued`, đếm ngay lúc
     `say`, không phải lúc vào hàng: đếm lúc vào hàng thì câu phòng có thể chen lên trước, mô phỏng đã bắt được). Nhật ký: "bỏ đọc:
     câu của bạn đọc trước". Trước đây câu phòng dịch xong trong lúc bạn nói được đọc trước, câu của bạn chờ sau.
  3. **Lời đối phương mặc định chỉ hiện chữ**: khoá mới `conversation.readOthersAloud` (mặc định tắt, bỏ lựa chọn cũ); nút chọn
     Đọc to + chữ / Chỉ chữ ở đầu màn hình chuyển vào **⋯ › Đọc to lời đối phương** (dành chỗ cho chữ).
  4. **Chữ gọn**: `ConversationModel.blocks` gộp câu liền nhau của một người (cùng `mine`, cùng người nói Soniox, cùng tiếng, cách
     < 60 s, tối đa 6 câu; câu phòng không có người nói — Chính xác, Dịch liền — đứng riêng). Khung gọn (lề 10, bo 14), tên và giờ
     chữ nhỏ một lần; bỏ hàng nút Đọc lại / Hiện to trên từng câu: **giữ ngón tay** trên câu để Đọc lại, Hiện to, Chép bản dịch,
     Chép câu gốc; chạm câu của bạn vẫn Hiện to. Bản dịch tạm (chưa xong) mờ 70%, xong thì đậm; không còn dòng "Đang dịch…" khi đã có
     chữ. Cỡ chữ: thêm **Nhỏ** (15), Vừa thành 17 (trước 20), Lớn 20, Rất lớn 22; câu gốc 13. **⋯ › Hiện câu gốc** bật/tắt.
     `TextSize` lưu theo số cũ (Android trước lưu theo thứ tự, giờ `code`; Nhỏ = 3).
  5. **Lời dặn GPT** (`Prompt.incoming`, `Prompt.meeting`: `intoVietnamese` / `INTO_VIETNAMESE`): đổi số đúng đơn vị đếm tiếng Trung
     (5万 = 50 nghìn, 100万 = 1 triệu, 1亿 = 100 triệu, 10亿 = 1 tỷ), k/million/billion, viết số kiểu Việt (2,5 triệu), 元/块 = tệ,
     毛/角, 斤 = "cân (0,5 kg)"; tên người, địa danh Trung Quốc đọc Hán Việt. Sang tiếng Trung (`guidance` zh, zh-Hant): 1 triệu = 100万…;
     sang tiếng Anh: nghìn/triệu/tỷ, viết số kiểu Anh. `Prompt.spoken`: tiếng Trung nghe nhầm thường là chữ đồng âm, chọn theo ngữ cảnh.
  Test: `Tests/Conversation` 239 kiểm tra (lời dặn); mô phỏng thêm: gộp câu, câu của bạn đọc ngay trước câu phòng dịch trong lượt
  của bạn (giọng 2,5 s). Android 30 test (`HttpTest`, `ConversationSonioxTest.userWordsGoFirst` với âm phát 1,5 s, gộp câu, lời
  dặn; `ConversationModelTest` giờ bật Đọc to rõ ràng); bỏ ưu tiên câu của bạn thì `userWordsGoFirst` trượt. `:app:compileDebugKotlin`
  qua. Giao diện iPhone chỉ biên dịch được trên CI. **Chưa thử trên máy thật.**
  Commit 40359bd: Android run 8 xanh (release `android-1.4.0-8`, đã gửi link APK). iOS run 79 bị huỷ 3 lần: GitHub báo "Due to
  capacity constraints, jobs targeting macOS arm64 runners may experience longer queue times" / "The job was not acquired by
  Runner of type hosted even after multiple attempts" (không có máy macOS trong 15 phút, chưa chạy bước nào; không phải lỗi mã).

- **1.16.0 + Android 1.5.0: thuật ngữ đông lạnh, hoa quả (27/10).** Người dùng: tối ưu tốc độ và chất lượng chữ dịch **trên Luna
  và Soniox** (không dùng Sol), nhất là nói chuyện kinh doanh **xuất nhập khẩu đồ đông lạnh và hoa quả**, tiếng Anh và tiếng Trung.
  1. `Trade` (`App/Conversation/Trade.swift`, `core/conversation/Trade.kt`, cùng một danh sách): 81 thuật ngữ Việt / Trung / Anh
     (hàng đông lạnh, IQF, mạ băng, cont lạnh, tôm thẻ/sú, HOSO/HLSO/PD, cá tra, mực, chân gà; sầu riêng Monthong/Musang King,
     thanh long, chanh leo, mít ≠ dứa, măng cụt…; vận đơn, kiểm dịch thực vật, C/O, mã số vùng trồng, GACC; chính ngạch 正关,
     tiểu ngạch 边贸, 压车, Hữu Nghị, Bằng Tường, Pò Chài 浦寨, Đông Hưng, Hà Khẩu; cọc, L/C, CIF/FOB, MOQ…), xếp theo mức cần.
     Danh sách sinh từ một bảng Python trong scratchpad, không vào repo: sửa thì sửa cả hai tệp.
  2. GPT: `SpokenContext.domain` = `Trade.prompt` (mô tả ngành, 柜/件/箱/一票, giá theo kg/tấn, giữ mã CIF/IQF/16/20…, rồi mỗi
     thuật ngữ một dòng "冻品 / frozen goods = hàng đông lạnh") nối sau instructions của `translateSpoken` (cả dịch trước, dịch
     cuối, Tôi nói). Instructions vượt 1.024 token nên OpenAI cache phần đầu giống nhau của mọi câu: nhanh hơn, rẻ hơn độ dài.
  3. Soniox: `general` thêm `domain`; `SonioxEvent.glossary(text, intoVietnamese, extra:)` thêm thuật ngữ của tiếng đối tác (zh
     hoặc en; tiếng khác không có) sau Thuật ngữ của người dùng: phòng (vào tiếng Việt) nghe từ tiếng đối tác, phiên Tôi nói nghe
     từ tiếng Việt; cặp dịch bỏ trùng (của người dùng thắng). Bỏ giới hạn 60 từ, thay bằng ngân sách ký tự (`termsRoom` 2.000,
     `pairsRoom` 4.500, đã tính phần JSON): cả bộ vừa (cấu hình ~5–6 nghìn ký tự, Soniox giới hạn ~10.000 ký tự / 8.000 token);
     Thuật ngữ rất dài thì giữ dòng đầu, không bao giờ quá giới hạn (trước đây cặp dịch không giới hạn).
  4. Menu ⋯ › **Thuật ngữ đông lạnh, hoa quả** (khoá `conversation.trade`, mặc định bật; Soniox áp dụng từ lần bấm nghe sau).
  Test: `Tests/Conversation` 250 kiểm tra (Trade.swift vào lệnh CI và `run.sh`), mô phỏng (phiên Soniox nhận thuật ngữ, GPT nhận
  `domain`); Android 30 test (`ConversationPartsTest`, `ConversationSonioxTest` kiểm cấu hình gửi Soniox giả). **Chưa thử trên máy
  thật**; chưa đo được thuật ngữ giúp bao nhiêu: cần biên bản có đánh dấu câu sai.
  Commit f573bbf: Android run 9 xanh (release `android-1.5.0-9`, đã gửi link APK). iOS run 80 bị huỷ 2 lần nữa (tổng 5 lần,
  ~2,5 giờ, cùng thông báo thiếu máy macOS arm64), nên job `build` chuyển sang `macos-15-intel` (job này chỉ build cho iPhone,
  không cần máy arm64; `keyboard-smoke` dựng simulator arm64 nên vẫn ở `macos-15`). Khi GitHub hết thiếu máy có thể trả về
  `macos-15` (nhanh hơn).

## Việc tiếp theo (theo thứ tự)

0. Gửi link IPA 1.16.0 (đã gửi APK 1.5.0; IPA 1.15.0 chưa từng build được vì GitHub thiếu máy macOS arm64, job `build` giờ
   chạy trên `macos-15-intel`). Người dùng muốn Soniox + Luna (không Sol), ngành đông lạnh, hoa quả, tiếng Trung và Anh.
   Trước đó: gửi link IPA 1.15.0 và APK 1.4.0. Người dùng thử: Tôi nói có còn trễ không (nhật ký: dòng "GPT Sol X s" của câu Tôi nói,
   "bỏ đọc: câu của bạn đọc trước"), chữ gọn có dễ đọc không. Nếu Tôi nói vẫn chậm: câu của bạn dịch bằng model của Cài đặt
   (mặc định Sol, suy luận "low", 2–4 s); có thể cho Tôi nói dùng Luna hoặc dịch trước trong lúc giữ nút — hỏi người dùng trước
   (đổi chất lượng câu đối tác nghe). Phương án chưa làm, đã đề xuất: Sol chỉ cho câu có số/giá/ngày/cam kết, Luna cho phần còn lại.
   Trước đó đã gửi link IPA 1.14.0 và APK 1.3.0. Người dùng thử họp thật rồi gửi nhật ký tốc độ: đọc dòng "Soniox nửa phút" và các dòng câu
   (xem cách đọc ở mục 1.14.0) trước khi sửa tiếp. Trước đó đã gửi link IPA 1.13.0 và APK 1.2.0. Người dùng ghi Chủ đề cuộc họp rồi thử trong cuộc họp thật 4–5 người (khuyên **Soniox + Chỉ chữ**) rồi gửi nhật ký
   tốc độ (⋯ › Sao chép nhật ký tốc độ, ngay sau buổi họp vì nó mất khi tắt app): đọc nó trước khi sửa tiếp, đừng
   đoán. Mỗi câu có các dòng cùng mã 4 ký tự: `Soniox · Người N · zh · 18 âm tiết · nhận chậm X s · chữ hiện sau X s`
   (Soniox chậm), `GPT Luna X s · bản dịch đầu tiên sau X s · bản GPT sau X s từ lúc ngừng nói` (đây là độ trễ người
   dùng thấy), `GPT chưa xong sau 2,5 s: hỏi thêm lần nữa`, `đọc sau X s` / `bỏ đọc` (giọng). **Ưu tiên của người dùng: chữ dịch đầy đủ,
   đúng nghĩa, không trễ; giọng đọc là tính năng phụ.** Hướng còn lại nếu nhật ký cho thấy cần: Dịch liền vẫn qua
   `LiveGate` (chỉ gửi khi có người nói, vì 2 USD/giờ) nên người nói nhỏ có thể bị sót; Soniox phân biệt người nói
   theo thời gian thực đôi khi nhầm (câu bị cắt giữa chừng, GPT vẫn có ngữ cảnh); người dùng đang để Tiết kiệm (Luna),
   Chuẩn nhất (Sol) dịch sát nghĩa hơn nhưng thêm ~2 USD/giờ.

1. Đang chờ người dùng chọn giọng (Google / Edge / chưa thêm); giọng Google đã có sẵn trong 1.10.x. Nếu chọn Edge:
   chưa làm, cần nói rõ rủi ro (không chính thức) trước.
2. Người dùng thử giọng Google với key thật, và tiếp tục thử Soniox: chữ có chạy ngay không, lỗi gì hiện ra. Rồi
   phần 1.8.1: micro cách 1–2 m, Dịch liền với key OpenAI, giọng iPhone, cuộn theo câu mới, giữ Tôi nói lâu.
3. Gửi link IPA dạng `https://github.com/moc-sinh/app-dichapple/actions/runs/<run>/artifacts/<id>`
   (job `build` xong là có artifact `MSTrans-ipa`; `keyboard-smoke` chạy sau, chụp màn Hội thoại để xem).
4. Nếu người dùng muốn: soát nốt các phần "chưa soát kỹ" ở mục 1.5.0 (ngoài Hội thoại).
5. Android: người dùng cài APK (link release mới nhất) và thử Hội thoại (Soniox + Chỉ chữ, họp 4–5 người), gửi nhật ký
   tốc độ như trên iPhone; rồi bàn phím (bật trong Cài đặt › Bàn phím ảo), Dịch, tin đã sao chép.

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
- Muốn biết bàn phím có khựng thì đọc nhật ký gõ người dùng gửi, đừng đoán. Hội thoại cũng vậy: đọc nhật ký tốc độ.
- Hội thoại: người dùng dùng Soniox và Dịch liền (OpenAI), mức Dịch lời đối phương = Tiết kiệm (Luna), họp 4–5
  người. Chữ dịch là chính (đủ, đúng nghĩa, không trễ); giọng đọc là tính năng phụ. Ngành: xuất nhập khẩu đồ đông lạnh
  (thủy sản, thịt) và hoa quả, đối tác nói tiếng Trung và tiếng Anh. Muốn tối ưu trên Soniox + Luna, không dùng Sol.
- Build xong gửi link IPA dạng `https://github.com/moc-sinh/app-dichapple/actions/runs/<run>/artifacts/<id>`.
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
