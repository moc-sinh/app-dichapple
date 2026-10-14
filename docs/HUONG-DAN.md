# Cài Dịch Apple lên iPhone, không cần Mac, không cần tài khoản trả phí

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
3. Muốn tự đọc ngữ cảnh: mục **Tự đọc ngữ cảnh** › chạm nút ghi › **Bắt đầu phát**. Khi phát, góc màn hình có chấm đỏ; chạm chấm đỏ để tắt.

## 5. Mỗi 7 ngày

Cắm iPhone (hoặc cùng mạng Wi-Fi nếu đã bật làm mới tự động của Sideloadly) và bấm **Start** lại với cùng file. Cài đè giữ nguyên API key và cài đặt.

Có bản mới: tải artifact mới ở bước 1 và cài đè.

## Cách app giữ nhẹ

- Bàn phím viết bằng UIKit thuần, không thư viện ngoài, build tối ưu kích thước (`-Osize`).
- Mỗi lần bấm Dịch là một request, tối đa 8 tin ngữ cảnh và 1.500 ký tự; không lưu gì phía OpenAI (`store: false`).
- Đọc màn hình chỉ chạy khi bàn phím Dịch Apple đang mở, tối đa 1 khung hình mỗi 1,5 giây, bỏ qua khi màn hình không đổi, chỉ đọc vùng khung chat. Không lưu ảnh, chỉ giữ chữ của vài tin cuối.

## Cần thử trên máy thật

- Bàn phím đọc được bao nhiêu chữ trong ô nhập của Zalo, Messenger, WeChat (iOS có thể chỉ trả một phần).
- Vision có nhận chữ tiếng Việt không (tiếng Trung, Anh, Hàn, Nhật được hỗ trợ).
- Bộ nhớ của phần đọc màn hình dưới giới hạn khoảng 50 MB.
- Sideloadly giữ đúng App Group khi ký lại (app tự đọc App Group thật từ hồ sơ ký, nên đổi tên nhóm vẫn chạy).

## Sau này nếu có tài khoản Apple Developer trả phí

App hết hạn sau 1 năm thay vì 7 ngày và cài được qua TestFlight. Cần thêm 4 secret `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8`, `TEAM_ID` trong Settings › Secrets and variables › Actions của repo, rồi chạy **Run workflow** với tùy chọn **Upload to TestFlight**.
