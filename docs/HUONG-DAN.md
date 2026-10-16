# Cài và dùng MSTrans (không cần Mac, không cần tài khoản trả phí)

Dùng cá nhân thì chỉ cần **Apple ID miễn phí** và một **máy tính Windows** (hoặc Mac). GitHub tự build file cài `.ipa`; **AltStore** ký file đó bằng Apple ID của bạn rồi cài vào iPhone.

> **Không dùng Sideloadly hay Feather để cài MSTrans.** Trên iOS 26, hai công cụ này ký lại phần bàn phím thiếu cờ “main binary” và không kèm hồ sơ ký riêng. iOS tắt bàn phím ngay khi mở (nhật ký lỗi ghi `CODESIGNING · Invalid Page`) rồi quay về bàn phím Apple, dù app vẫn mở được. AltStore và SideStore ký đúng.

## Giới hạn của Apple ID miễn phí

- App hết hạn sau **7 ngày**, phải làm mới trong AltStore (máy tính chạy AltServer cùng mạng Wi-Fi).
- Tối đa **3 app** cài kiểu này đang hoạt động trên một máy (AltStore tính là 1) và **10 mã ứng dụng mỗi 7 ngày**. MSTrans dùng 2 mã (app và bàn phím).
- Không dùng được TestFlight.

## 1. Lấy file cài

GitHub › repo `app-dichapple` › **Actions** › **iOS** › chọn lần chạy mới nhất có dấu xanh › mục **Artifacts** › tải **MSTrans-ipa**. File tải về là `.zip`, giải nén ra `MSTrans.ipa`.

## 2. Cài AltStore

1. Trên Windows, cài **iTunes** và **iCloud** bản tải từ trang Apple (không dùng bản Microsoft Store).
2. Tải **AltServer** (AltStore Classic) tại <https://altstore.io>, cài rồi mở. AltServer nằm ở khay hệ thống, góc dưới bên phải.
3. Cắm iPhone bằng cáp, mở khóa, chọn **Tin cậy**. Trong iTunes, chọn iPhone, bật **Đồng bộ với iPhone này qua Wi-Fi** để sau này làm mới không cần cáp.
4. Bấm biểu tượng AltServer › **Install AltStore** › chọn iPhone, nhập Apple ID. Nên dùng một Apple ID phụ.
5. Trên iPhone: bật **Chế độ nhà phát triển** (Cài đặt › Quyền riêng tư & Bảo mật › Chế độ nhà phát triển, rồi khởi động lại máy) và tin cậy Apple ID (Cài đặt › Cài đặt chung › **VPN & Quản lý thiết bị** › chọn Apple ID › **Tin cậy**).

## 3. Cài MSTrans

1. Đã cài MSTrans hoặc Dịch Apple bằng Sideloadly: **xóa app đó trước** (giữ biểu tượng › Xóa app), rồi khởi động lại iPhone.
2. Chép `MSTrans.ipa` vào iPhone, vào app **Tệp** (ví dụ qua iCloud Drive, hoặc gửi file cho chính mình rồi chọn Lưu vào Tệp).
3. Mở **AltStore** › tab **My Apps** › nút **+** góc trên › chọn `MSTrans.ipa`. Máy tính chạy AltServer phải bật, cùng mạng Wi-Fi.
4. Nếu AltStore hỏi có gỡ app extension không: chọn **giữ lại**. Bàn phím chính là app extension.

SideStore cũng ký đúng (dùng bản phát hành từ tháng 9/2026 trở đi) và làm mới được không cần máy tính, nhưng cài lần đầu phức tạp hơn: <https://sidestore.io>.

## 4. Thiết lập trong app

Mở MSTrans › tab **Cài đặt**, mục **Bắt đầu** có sẵn từng bước:

1. Thêm bàn phím MSTrans: Cài đặt › Cài đặt chung › Bàn phím › Bàn phím › Thêm bàn phím mới › MSTrans. Chạm MSTrans trong danh sách, bật **Cho phép truy cập đầy đủ**.
2. Dán API key OpenAI ở mục **OpenAI**, bấm **Kiểm tra kết nối**.
3. Mục **Về bạn**: chọn giới tính, ghi cách xưng hô (ví dụ “tôi xưng em, gọi đối tác là anh”) và vài dòng về công việc. Tab **Thuật ngữ**: thêm tên sản phẩm, thuật ngữ ngành để bản dịch luôn dùng đúng từ.
4. Cài đặt iPhone › MSTrans › **Dán từ ứng dụng khác** › **Cho phép**. Không đặt thì lần đầu dịch tin đã sao chép iOS hỏi “Cho phép dán”, và chạm vào ô trả lời là phải chạm thêm một lần nữa.
5. Trong app, mục **Tin đối tác gửi**, bật **Dịch ngay khi mở bàn phím**: từ đó sao chép tin rồi chạm vào ô trả lời là bản dịch hiện ngay.

Bước “Cho phép truy cập đầy đủ” được đánh dấu xong sau khi bạn mở bàn phím MSTrans lần đầu.

Bàn phím báo **Cần API key** dù đã dán key trong app: app và bàn phím chưa dùng chung dữ liệu (công cụ cài bỏ mất App Group). Sao chép API key, rồi bấm **Dán API key** ngay trên bàn phím.

## 5. Dùng hằng ngày

**Trả lời đối tác**
1. Trong Zalo, WhatsApp, WeChat, Messenger…, chạm quả địa cầu để chuyển sang bàn phím MSTrans (có logo Mộc Sinh ở góc trái), gõ tiếng Việt (kiểu Telex).
2. Bấm **Dịch** (nút xanh): cả đoạn đổi sang ngôn ngữ đối tác. Để **Tự động**, bàn phím tự trả lời đúng ngôn ngữ đối tác đang dùng.
3. Một dòng hiện lên trên thanh Dịch với **Nghĩa** (dấu tích xanh): câu đối tác sẽ đọc, dịch ngược lại tiếng Việt. Nếu câu gốc có chỗ dễ hiểu sai, dòng này hiện **Lưu ý** (tam giác vàng). Chạm vào để xem đầy đủ, hoặc bấm **Hoàn tác** để lấy lại câu gốc. Gõ tiếp là dòng này ẩn đi: lúc bình thường bàn phím chỉ có thanh Dịch.

**Đọc tin đối tác** (biểu tượng màu cam)
- Nhấn giữ tin › **Sao chép**, rồi chạm vào ô trả lời. Đã bật **Dịch ngay**: bản dịch tiếng Việt hiện ngay trên dòng trên cùng của bàn phím, tin dài thì mở sẵn cả bảng để đọc, bấm **Xong** để gõ trả lời. Chưa bật: dòng trên cùng hiện “Có tin vừa sao chép · chạm để dịch”, chạm một lần. Tin nào sao chép cũng được dịch sang tiếng Việt, kể cả tin có tên người Việt.
- Chạm **logo MSTrans** bất cứ lúc nào để xem cùng lúc: tin đối tác vừa dịch, câu bạn vừa gửi (kèm nghĩa, lưu ý) và các tin đang dùng làm ngữ cảnh. Nút **Cuộc mới** xóa ngữ cảnh khi chuyển sang nói chuyện với người khác.

**Không thấy MSTrans trong một app (ví dụ Zalo)**

App đã mở từ trước khi cài (hoặc cài lại) MSTrans vẫn giữ danh sách bàn phím cũ, nên giữ quả địa cầu không thấy MSTrans. Vuốt lên từ cạnh dưới màn hình, gạt app đó đi rồi mở lại. Vẫn chưa có thì khởi động lại iPhone.

**Dịch bằng chạm lưng iPhone** (mọi app, không cần mở bàn phím)

Một lần thiết lập:
1. Mở app **Phím tắt** › **+** › thêm thao tác **Dịch tin đã sao chép** (của MSTrans) › Xong.
2. Cài đặt › Trợ năng › Cảm ứng › **Chạm vào mặt sau** › **Chạm hai lần** › chọn phím tắt vừa tạo.

Từ đó, cùng một cái chạm làm cả hai chiều:
- **Đọc**: giữ tin đối tác › Sao chép › gõ hai lần vào lưng iPhone: bản dịch tiếng Việt hiện lên.
- **Trả lời**: gõ tiếng Việt bằng bàn phím bất kỳ › chạm giữ ô chat › **Chọn tất cả** › **Cắt** › gõ hai lần vào lưng iPhone: MSTrans dịch sang tiếng đối tác và chép sẵn bản dịch › chạm giữ ô chat › **Dán** › gửi.

Nếu thông báo “chưa đọc được tin đã sao chép”: mở phím tắt vừa tạo, thêm thao tác **Lấy bảng tạm** lên trước thao tác MSTrans. Nếu báo “chưa tự chép được”: thêm thao tác **Sao chép vào bảng tạm** sau thao tác MSTrans.

**Trong app MSTrans**
- **Dịch**: dịch đoạn dài, hai chiều (nút ⇄ đổi chiều), có nút dán, sao chép và chia sẻ; bên dưới là 3 bản dịch gần nhất, chạm để xem và chép lại.
- **Lịch sử**: mọi tin đã dịch (xanh là bạn gửi, cam là đối tác), tìm kiếm, vuốt phải để đánh dấu câu hay dùng, vuốt trái để xóa. Tắt lưu lịch sử trong Cài đặt › Dữ liệu nếu không muốn giữ.
- **Thuật ngữ**: cặp từ luôn dịch cố định, ví dụ 主轴 → trục chính.

**Gõ phím**
- Giữ một nguyên âm để chọn dấu (á à ả ã ạ ă â…).
- Giữ phím cách rồi kéo để di chuyển con trỏ.
- Chạm hai lần Shift để khóa chữ hoa; chạm hai lần phím cách để thêm dấu chấm.
- Giữ phím xóa: xóa từng chữ, giữ lâu thì xóa cả từ.
- Gõ tiếng Anh: từ không thể là tiếng Việt (facebook, order, class…) tự giữ nguyên. Muốn gõ một chữ trùng phím dấu, gõ phím đó hai lần: `tesst` → `test`, `sorrry` → `sorry`, `ww` → `w`.
- Gõ nhanh bao nhiêu cũng được: bàn phím tự nhớ chữ đang gõ, không phụ thuộc app cập nhật ô nhập nhanh hay chậm. Lúc đang gõ, bàn phím không tin những gì app báo lại (có app như Spotlight báo sai trong chốc lát). Dừng gõ nửa giây, bàn phím mới so lại với ô nhập.
- Chạm hơi cao trên hàng phím đầu vẫn ra chữ, không bấm nhầm vào thanh dịch hay nút đổi ngôn ngữ. Chạm nhanh mà iOS hủy giữa chừng cũng vẫn ra chữ.
- Chữ vào ô nhập ngay khi ngón tay vừa chạm phím, không chờ nhấc tay, nên gõ nhanh thấy chữ hiện tức thì. Giữ nguyên âm vẫn chọn được dấu, giữ phím cách vẫn di được con trỏ (chữ vừa gõ tự rút lại). Phím xuống dòng/Gửi vẫn chờ nhấc tay để không gửi nhầm. Muốn giống bàn phím Apple (chữ vào khi nhấc tay), tắt **Ra chữ ngay khi chạm phím** trong Cài đặt › Bàn phím.

## Khi gõ bị sai hay mất chữ

Gõ tiếp cho đến khi lỗi hiện ra. Sau đó mở app MSTrans › Cài đặt › **Chẩn đoán bàn phím**, bấm **Sao chép nhật ký gõ** và gửi cho người hỗ trợ. Nhật ký ghi từng phím, chữ bàn phím đã sửa và chữ app báo lại, nên có đoạn cuối những gì vừa gõ. Nó chỉ nằm trên iPhone này, và **Xóa nhật ký gõ** xóa nó đi.

Nhật ký cũng ghi chỗ chậm: phím đến bàn phím trễ (⌛ … đến trễ), bàn phím xử lý lâu (⏱), tiếng click và rung lâu, và app chat hiện chữ chậm (⌛ app hiện chữ chậm nhất sau … ms). Nhờ vậy biết chậm do bàn phím hay do app đang chat.

## 6. Mỗi 7 ngày

Mở AltStore › **My Apps** › **Refresh All** khi máy tính đang chạy AltServer cùng mạng Wi-Fi. Làm mới giữ nguyên API key, cài đặt và lịch sử.

Có bản mới: tải artifact mới ở bước 1, rồi trong AltStore bấm **+** và chọn file mới để cài đè.

## Khi bàn phím không hiện

Chọn MSTrans mà iPhone tự quay về bàn phím Apple:

1. Mở app MSTrans › Cài đặt › **Chẩn đoán bàn phím**. Dòng có dấu ✗ là chỗ lỗi. Nếu có dòng “thiếu cờ main binary”, “không có hồ sơ ký riêng” hoặc “ký bằng mã của app chính”, công cụ cài đã ký sai: xóa app rồi cài lại bằng AltStore (bước 2 và 3).
2. Cài đặt › Cài đặt chung › Bàn phím › Bàn phím: xóa MSTrans, thêm lại, bật lại **Cho phép truy cập đầy đủ**. Khởi động lại iPhone.
3. Vẫn lỗi: bấm **Sao chép báo cáo** trong Chẩn đoán bàn phím, và gửi kèm nhật ký lỗi ở Cài đặt › Quyền riêng tư & Bảo mật › Phân tích & Cải tiến › **Dữ liệu phân tích** (mục bắt đầu bằng `MSTransKeyboard`; cần bật **Chia sẻ phân tích iPhone** trước).

## Chất lượng và chi phí

- **Chuẩn nhất** (mặc định): GPT-6.1 Sol, khoảng 0,003 USD mỗi tin (1.000 tin ≈ 3 USD).
- **Nhanh**: GPT-6 Luna, rẻ hơn khoảng 20 lần, dùng khi cần tiết kiệm.
- Mỗi tin của đối tác chỉ dịch một lần; app nhớ bản dịch nên không gọi lại.
- Gửi kèm tối đa 10 tin gần nhất làm ngữ cảnh. OpenAI không lưu nội dung (`store: false`).

## Cách app giữ nhẹ

- Bàn phím viết bằng UIKit thuần, không thư viện ngoài, build tối ưu kích thước; dùng khoảng 20 MB bộ nhớ (đo trên trình giả lập iOS 26.2).
- Toàn bộ phím nằm trong một view; chạm là phản hồi ngay, không có khe chết giữa các phím.
- Phím sát mép màn hình (q, a, z, p, l, xóa, phím cách) không bị iOS giữ lại để chờ thao tác vuốt hệ thống; tiếng click và rung chạy sau khi phím đã hiện.
- Bộ gõ Telex xử lý mỗi phím trong khoảng 0,01 mili giây (đo trên máy chủ build), nhỏ hơn hàng trăm lần một khung hình của màn hình.
- Bàn phím đọc bộ nhớ tạm ở luồng nền (khi bạn chạm để dịch, hoặc ngay lúc mở nếu bật Dịch ngay), nên dù iOS có hỏi quyền dán thì phím vẫn hiện và gõ được.
- Mở bàn phím là kết nối tới OpenAI được mở sẵn, nên lần bấm Dịch đầu tiên không mất thêm thời gian bắt tay mạng. Mạng rớt hay máy chủ bận thì tự thử lại một lần.
- Bấm Dịch là dịch cả tin, kể cả khi tin có nhiều dòng (iOS chỉ đưa cho bàn phím đoạn đang gõ, bàn phím tự đọc hết các đoạn còn lại).

## Cần thử trên máy thật

- Bàn phím đọc được bao nhiêu chữ trong ô nhập của Messenger, WeChat (iOS có thể chỉ trả một phần).

## Sau này nếu có tài khoản Apple Developer trả phí

App hết hạn sau 1 năm thay vì 7 ngày, cài được qua TestFlight, và Apple ký đúng mọi phần nên không phụ thuộc công cụ cài. Cần thêm 4 secret `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8`, `TEAM_ID` trong Settings › Secrets and variables › Actions của repo, rồi chạy **Run workflow** với tùy chọn **Upload to TestFlight**.
