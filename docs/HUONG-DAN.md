# Cài và dùng Dịch Apple (không cần Mac, không cần tài khoản trả phí)

Dùng cá nhân thì chỉ cần **Apple ID miễn phí** và một **máy tính Windows**. GitHub tự build file cài `.ipa`; công cụ **Sideloadly** trên Windows ký file đó bằng Apple ID của bạn rồi cài vào iPhone.

## Giới hạn của Apple ID miễn phí

- App hết hạn sau **7 ngày**, phải ký lại (Sideloadly làm lại trong khoảng một phút, hoặc tự làm nếu bật làm mới tự động).
- Tối đa **3 app** cài kiểu này trên một máy và **10 mã ứng dụng mỗi 7 ngày**. Dịch Apple dùng 3 mã (app, bàn phím, đọc màn hình).
- Không dùng được TestFlight.

## 1. Lấy file cài

GitHub › repo `app-dichapple` › **Actions** › **iOS** › chọn lần chạy mới nhất có dấu xanh › mục **Artifacts** › tải **DichApple-ipa**. File tải về là `.zip`, giải nén ra `DichApple.ipa`.

## 2. Cài Sideloadly trên Windows

1. Cài **iTunes** và **iCloud** bản tải từ trang Apple (không dùng bản Microsoft Store) để Windows nhận iPhone.
2. Tải Sideloadly tại <https://sideloadly.io> và cài.

## 3. Cài app vào iPhone

1. Cắm iPhone vào máy tính bằng cáp, mở khóa iPhone, chọn **Tin cậy**.
2. Mở Sideloadly, kéo `DichApple.ipa` vào, nhập Apple ID, bấm **Start**. Nên dùng một Apple ID phụ.
3. Trên iPhone, bật **Chế độ nhà phát triển**: Cài đặt › Quyền riêng tư & Bảo mật › Chế độ nhà phát triển › bật, rồi khởi động lại máy.
4. Cài đặt › Cài đặt chung › **VPN & Quản lý thiết bị** › chọn Apple ID của bạn › **Tin cậy**.

## 4. Thiết lập trong app

1. Mở Dịch Apple, làm theo mục **Cài bàn phím**: thêm bàn phím Dịch Apple, bật **Cho phép truy cập đầy đủ**.
2. Dán API key OpenAI, bấm **Kiểm tra kết nối**.
3. Mục **Về bạn**: chọn giới tính, ghi cách xưng hô (ví dụ “tôi xưng em, gọi đối tác là anh”), vài dòng về công việc và các thuật ngữ ngành. Bản dịch dựa vào đây để xưng hô và dùng từ cho đúng.
4. Nếu iOS hỏi “Cho phép dán”, chọn **Cho phép**. Hoặc vào Cài đặt › Dịch Apple › **Dán từ ứng dụng khác** › **Cho phép**, để tin bạn sao chép được dịch ngay.

## 5. Dùng hằng ngày

**Trả lời đối tác**
1. Gõ tiếng Việt bằng bàn phím Dịch Apple (kiểu Telex).
2. Bấm **Dịch**: cả đoạn đổi sang ngôn ngữ đối tác. Để **Tự động**, app tự trả lời đúng ngôn ngữ đối tác đang dùng.
3. Dòng trên cùng hiện **Nghĩa**: câu đối tác sẽ đọc, dịch ngược lại tiếng Việt. Nếu câu gốc có chỗ dễ hiểu sai, dòng này hiện **Lưu ý**. Chạm vào để xem đầy đủ, hoặc bấm **Hoàn tác** để lấy lại câu gốc.

**Đọc tin đối tác**
- **Bật đọc màn hình** (trong app, mục *Tin đối tác gửi*, chạm nút ghi rồi **Bắt đầu phát**): mỗi khi bạn chạm vào ô trả lời, bản dịch tin mới nhất của đối tác hiện ngay ở dòng trên cùng của bàn phím. Không cần sao chép. Khi phát, góc màn hình có chấm đỏ; chạm chấm đỏ để tắt.
- **Không bật đọc màn hình**: nhấn giữ tin › **Sao chép**, rồi chạm vào ô trả lời. Bản dịch hiện ngay ở dòng trên cùng.
- **Đọc mà không mở bàn phím**: mở app **Phím tắt** › **+** › thêm thao tác **Dịch tin đã sao chép** (của Dịch Apple) › đặt tên. Rồi vào Cài đặt › Trợ năng › Cảm ứng › **Chạm vào mặt sau** › **Chạm hai lần** › chọn phím tắt đó. Từ đó: sao chép tin, gõ hai lần vào lưng iPhone là bản dịch hiện lên.

**Gõ phím**
- Giữ một nguyên âm để chọn dấu (á à ả ã ạ ă â…).
- Giữ phím cách rồi kéo để di chuyển con trỏ.
- Chạm hai lần Shift để khóa chữ hoa; chạm hai lần phím cách để thêm dấu chấm.
- Giữ phím xóa: xóa từng chữ, giữ lâu thì xóa cả từ.
- Gõ tiếng Anh: từ không thể là tiếng Việt (facebook, order, class…) tự giữ nguyên. Muốn gõ một chữ trùng phím dấu, gõ phím đó hai lần: `tesst` → `test`, `sorrry` → `sorry`.

## 6. Mỗi 7 ngày

Cắm iPhone (hoặc cùng mạng Wi-Fi nếu đã bật làm mới tự động của Sideloadly) và bấm **Start** lại với cùng file. Cài đè giữ nguyên API key và cài đặt.

Có bản mới: tải artifact mới ở bước 1 và cài đè.

## Chất lượng và chi phí

- **Chuẩn nhất** (mặc định): GPT-6.1 Sol, khoảng 0,003 USD mỗi tin (1.000 tin ≈ 3 USD).
- **Nhanh**: GPT-6 Luna, rẻ hơn khoảng 20 lần, dùng khi cần tiết kiệm.
- Mỗi tin của đối tác chỉ dịch một lần; app nhớ bản dịch nên không gọi lại.
- Gửi kèm tối đa 10 tin gần nhất làm ngữ cảnh. OpenAI không lưu nội dung (`store: false`).

## Cách app giữ nhẹ

- File cài khoảng 340 KB. Bàn phím viết bằng UIKit thuần, không thư viện ngoài, build tối ưu kích thước.
- Toàn bộ phím nằm trong một view; chạm là phản hồi ngay, không có khe chết giữa các phím.
- Đọc màn hình chỉ chạy khi bàn phím Dịch Apple đang mở, tối đa 1 khung hình mỗi 1,5 giây, bỏ qua khi màn hình không đổi, chỉ đọc vùng khung chat. Không lưu ảnh, chỉ giữ chữ của vài tin cuối.

## Cần thử trên máy thật

- Bàn phím đọc được bao nhiêu chữ trong ô nhập của Zalo, Messenger, WeChat (iOS có thể chỉ trả một phần).
- Vision có nhận chữ tiếng Việt không (tiếng Trung, Anh, Hàn, Nhật được hỗ trợ).
- Bộ nhớ của phần đọc màn hình dưới giới hạn khoảng 50 MB.
- Sideloadly giữ đúng App Group khi ký lại (app tự đọc App Group thật từ hồ sơ ký, nên đổi tên nhóm vẫn chạy).

## Sau này nếu có tài khoản Apple Developer trả phí

App hết hạn sau 1 năm thay vì 7 ngày và cài được qua TestFlight. Cần thêm 4 secret `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8`, `TEAM_ID` trong Settings › Secrets and variables › Actions của repo, rồi chạy **Run workflow** với tùy chọn **Upload to TestFlight**.
