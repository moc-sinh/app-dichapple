# Cài và dùng MSTrans (không cần Mac, không cần tài khoản trả phí)

Dùng cá nhân thì chỉ cần **Apple ID miễn phí** và một **máy tính Windows**. GitHub tự build file cài `.ipa`; công cụ **Sideloadly** trên Windows ký file đó bằng Apple ID của bạn rồi cài vào iPhone.

> Đã cài bản cũ tên **Dịch Apple**? Cứ cài MSTrans đè lên như bình thường: app được cập nhật tại chỗ, giữ nguyên API key và cài đặt. Bàn phím trong danh sách sẽ đổi tên thành MSTrans.

## Giới hạn của Apple ID miễn phí

- App hết hạn sau **7 ngày**, phải ký lại (Sideloadly làm lại trong khoảng một phút, hoặc tự làm nếu bật làm mới tự động).
- Tối đa **3 app** cài kiểu này trên một máy và **10 mã ứng dụng mỗi 7 ngày**. MSTrans dùng 3 mã (app, bàn phím, đọc màn hình).
- Không dùng được TestFlight.

## 1. Lấy file cài

GitHub › repo `app-dichapple` › **Actions** › **iOS** › chọn lần chạy mới nhất có dấu xanh › mục **Artifacts** › tải **MSTrans-ipa**. File tải về là `.zip`, giải nén ra `MSTrans.ipa`.

## 2. Cài Sideloadly trên Windows

1. Cài **iTunes** và **iCloud** bản tải từ trang Apple (không dùng bản Microsoft Store) để Windows nhận iPhone.
2. Tải Sideloadly tại <https://sideloadly.io> và cài.

## 3. Cài app vào iPhone

1. Cắm iPhone vào máy tính bằng cáp, mở khóa iPhone, chọn **Tin cậy**.
2. Mở Sideloadly, kéo `MSTrans.ipa` vào, nhập Apple ID, bấm **Start**. Nên dùng một Apple ID phụ.
3. Trên iPhone, bật **Chế độ nhà phát triển**: Cài đặt › Quyền riêng tư & Bảo mật › Chế độ nhà phát triển › bật, rồi khởi động lại máy.
4. Cài đặt › Cài đặt chung › **VPN & Quản lý thiết bị** › chọn Apple ID của bạn › **Tin cậy**.

## 4. Thiết lập trong app

Mở MSTrans › tab **Cài đặt**, mục **Bắt đầu** có sẵn từng bước:

1. Thêm bàn phím MSTrans: Cài đặt › Cài đặt chung › Bàn phím › Bàn phím › Thêm bàn phím mới › MSTrans. Chạm MSTrans trong danh sách, bật **Cho phép truy cập đầy đủ**.
2. Dán API key OpenAI ở mục **OpenAI**, bấm **Kiểm tra kết nối**.
3. Mục **Về bạn**: chọn giới tính, ghi cách xưng hô (ví dụ “tôi xưng em, gọi đối tác là anh”) và vài dòng về công việc. Tab **Thuật ngữ**: thêm tên sản phẩm, thuật ngữ ngành để bản dịch luôn dùng đúng từ.
4. Lần đầu dịch tin đã sao chép, iOS hỏi “Cho phép dán”: chọn **Cho phép dán**. Để không bị hỏi lại: Cài đặt › MSTrans › **Dán từ ứng dụng khác** › **Cho phép**.

Hai bước đầu được đánh dấu xong sau khi bạn mở bàn phím MSTrans lần đầu.

## 5. Dùng hằng ngày

**Trả lời đối tác**
1. Trong Zalo, WeChat, Messenger…, chạm quả địa cầu để chuyển sang bàn phím MSTrans (có logo Mộc Sinh ở góc trái), gõ tiếng Việt (kiểu Telex).
2. Bấm **Dịch** (nút xanh): cả đoạn đổi sang ngôn ngữ đối tác. Để **Tự động**, bàn phím tự trả lời đúng ngôn ngữ đối tác đang dùng.
3. Dòng trên cùng hiện **Nghĩa** (dấu tích xanh): câu đối tác sẽ đọc, dịch ngược lại tiếng Việt. Nếu câu gốc có chỗ dễ hiểu sai, dòng này hiện **Lưu ý** (tam giác vàng). Chạm vào để xem đầy đủ, hoặc bấm **Hoàn tác** để lấy lại câu gốc.

**Đọc tin đối tác** (biểu tượng màu cam)
- **Bật đọc màn hình** (tab Cài đặt, mục *Tin đối tác gửi*, chạm nút ghi rồi **Bắt đầu phát**): mỗi khi bạn chạm vào ô trả lời, bản dịch tin mới nhất của đối tác hiện ngay ở dòng trên cùng của bàn phím. Khi phát, góc màn hình có chấm đỏ; chạm chấm đỏ để tắt.
- **Không bật đọc màn hình**: nhấn giữ tin › **Sao chép**, rồi chạm vào ô trả lời. Dòng trên cùng hiện “Có tin vừa sao chép · chạm để dịch”: chạm một lần là có bản dịch.
- **Đọc mà không mở bàn phím**: mở app **Phím tắt** › **+** › thêm thao tác **Dịch tin đã sao chép** (của MSTrans) › đặt tên. Rồi vào Cài đặt › Trợ năng › Cảm ứng › **Chạm vào mặt sau** › **Chạm hai lần** › chọn phím tắt đó. Từ đó: sao chép tin, gõ hai lần vào lưng iPhone là bản dịch hiện lên.

**Trong app MSTrans**
- **Dịch**: dịch đoạn dài, hai chiều (nút ⇄ đổi chiều), có nút dán, sao chép và chia sẻ; bên dưới là 3 bản dịch gần nhất, chạm để xem và chép lại.
- **Lịch sử**: mọi tin đã dịch (xanh là bạn gửi, cam là đối tác), tìm kiếm, vuốt phải để đánh dấu câu hay dùng, vuốt trái để xóa. Tắt lưu lịch sử trong Cài đặt › Dữ liệu nếu không muốn giữ.
- **Thuật ngữ**: cặp từ luôn dịch cố định, ví dụ 主轴 → trục chính.

**Gõ phím**
- Giữ một nguyên âm để chọn dấu (á à ả ã ạ ă â…).
- Giữ phím cách rồi kéo để di chuyển con trỏ.
- Chạm hai lần Shift để khóa chữ hoa; chạm hai lần phím cách để thêm dấu chấm.
- Giữ phím xóa: xóa từng chữ, giữ lâu thì xóa cả từ.
- Gõ tiếng Anh: từ không thể là tiếng Việt (facebook, order, class…) tự giữ nguyên. Muốn gõ một chữ trùng phím dấu, gõ phím đó hai lần: `tesst` → `test`, `sorrry` → `sorry`.

## 6. Mỗi 7 ngày

Cắm iPhone (hoặc cùng mạng Wi-Fi nếu đã bật làm mới tự động của Sideloadly) và bấm **Start** lại với cùng file. Cài đè giữ nguyên API key, cài đặt và lịch sử.

Có bản mới: tải artifact mới ở bước 1 và cài đè.

## Khi bàn phím không hiện

Chọn MSTrans mà iPhone tự quay về bàn phím Apple: bản trước 15/10/2026 bị lỗi này khi đã bật truy cập đầy đủ và trong bộ nhớ tạm có chữ (bàn phím đọc bộ nhớ tạm lúc mở, iOS chặn lại để hỏi quyền dán). Bản MSTrans đã sửa. Nếu vẫn gặp:

1. Cài bản mới nhất (bước 1 và 3).
2. Cài đặt › Cài đặt chung › Bàn phím › Bàn phím: xóa MSTrans, thêm lại, bật lại **Cho phép truy cập đầy đủ**. Khởi động lại iPhone.
3. Vẫn lỗi: Cài đặt › Quyền riêng tư & Bảo mật › Phân tích & Cải tiến › **Dữ liệu phân tích**, tìm mục bắt đầu bằng `MSTransKeyboard`, chạm vào rồi chia sẻ nội dung để gửi kèm khi báo lỗi.

## Chất lượng và chi phí

- **Chuẩn nhất** (mặc định): GPT-6.1 Sol, khoảng 0,003 USD mỗi tin (1.000 tin ≈ 3 USD).
- **Nhanh**: GPT-6 Luna, rẻ hơn khoảng 20 lần, dùng khi cần tiết kiệm.
- Mỗi tin của đối tác chỉ dịch một lần; app nhớ bản dịch nên không gọi lại.
- Gửi kèm tối đa 10 tin gần nhất làm ngữ cảnh. OpenAI không lưu nội dung (`store: false`).

## Cách app giữ nhẹ

- Bàn phím viết bằng UIKit thuần, không thư viện ngoài, build tối ưu kích thước; dùng khoảng 20 MB bộ nhớ (đo trên trình giả lập iOS 26.2).
- Toàn bộ phím nằm trong một view; chạm là phản hồi ngay, không có khe chết giữa các phím.
- Bàn phím không bao giờ tự đọc bộ nhớ tạm, nên không bị iOS chặn lúc mở; chỉ đọc khi bạn chạm để dịch, và đọc ở luồng nền.
- Đọc màn hình chỉ chạy khi bàn phím MSTrans đang mở, tối đa 1 khung hình mỗi 1,5 giây, bỏ qua khi màn hình không đổi, chỉ đọc vùng khung chat. Không lưu ảnh, chỉ giữ chữ của vài tin cuối.

## Cần thử trên máy thật

- Bàn phím đọc được bao nhiêu chữ trong ô nhập của Zalo, Messenger, WeChat (iOS có thể chỉ trả một phần).
- Vision có nhận chữ tiếng Việt không (tiếng Trung, Anh, Hàn, Nhật được hỗ trợ).
- Bộ nhớ của phần đọc màn hình dưới giới hạn khoảng 50 MB.

## Sau này nếu có tài khoản Apple Developer trả phí

App hết hạn sau 1 năm thay vì 7 ngày và cài được qua TestFlight. Cần thêm 4 secret `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8`, `TEAM_ID` trong Settings › Secrets and variables › Actions của repo, rồi chạy **Run workflow** với tùy chọn **Upload to TestFlight**.
