# Đưa Dịch Apple lên iPhone (không cần Mac)

GitHub Actions build app trên máy macOS của GitHub, ký bằng tài khoản Apple Developer của bạn, rồi đẩy lên TestFlight. Bạn cài app bằng TestFlight trên iPhone.

## 1. Tài khoản Apple Developer

Đăng ký bằng app **Apple Developer** trên iPhone ($99/năm). Đợi Apple duyệt xong (thường vài giờ đến 1–2 ngày).

## 2. Tạo mã định danh (làm trên web, máy nào cũng được)

Vào <https://developer.apple.com/account/resources/identifiers/list>:

1. Bấm **+** › **App Groups** › mã `group.com.mocsinh.dichapple`.
2. Bấm **+** › **App IDs** › **App**, tạo 3 mã, mỗi mã bật **App Groups** rồi gắn nhóm ở bước 1:
   - `com.mocsinh.dichapple` (app chính)
   - `com.mocsinh.dichapple.keyboard` (bàn phím)
   - `com.mocsinh.dichapple.screen` (đọc màn hình)

Nếu Apple báo mã đã có người dùng, đổi `com.mocsinh` thành tiền tố khác ở cả hai chỗ: `project.yml` và `Shared/Config.swift`.

## 3. Tạo app trên App Store Connect

<https://appstoreconnect.apple.com> › **Ứng dụng** › **+** › **Ứng dụng mới**: nền tảng iOS, tên `Dịch Apple` (hoặc tên khác nếu trùng), Bundle ID `com.mocsinh.dichapple`, SKU tùy ý.

## 4. Tạo API key cho GitHub

App Store Connect › **Người dùng và quyền truy cập** › **Tích hợp** › **App Store Connect API** › **Khóa nhóm** › **+**:

- Vai trò **Admin** (cần để GitHub tự tạo chứng chỉ ký app).
- Tải file `.p8` về (chỉ tải được một lần). Ghi lại **Key ID** và **Issuer ID** trên trang đó.

Team ID nằm ở <https://developer.apple.com/account> › **Membership details**.

## 5. Đưa khóa vào GitHub

Repo trên GitHub › **Settings** › **Secrets and variables** › **Actions** › **New repository secret**, tạo 4 secret:

| Tên | Giá trị |
|---|---|
| `ASC_KEY_ID` | Key ID |
| `ASC_ISSUER_ID` | Issuer ID |
| `ASC_KEY_P8` | Toàn bộ nội dung file `.p8` (mở bằng trình soạn văn bản, chép cả dòng BEGIN/END) |
| `TEAM_ID` | Team ID |

## 6. Build và đẩy lên TestFlight

GitHub › **Actions** › **iOS** › **Run workflow** (để tích **Upload to TestFlight**). Mất khoảng 15–25 phút.

Mỗi lần push code, workflow chỉ build thử (không cần secret) để bắt lỗi sớm. Chỉ khi bấm **Run workflow** mới đẩy lên TestFlight.

## 7. Cài trên iPhone

1. App Store Connect › app › **TestFlight** › **Kiểm thử nội bộ** › thêm chính bạn.
2. Cài app **TestFlight** trên iPhone, mở lời mời, cài **Dịch Apple**.
3. Mở Dịch Apple, làm theo mục **Cài bàn phím**, dán API key OpenAI.
4. Muốn tự đọc ngữ cảnh: mục **Tự đọc ngữ cảnh** › chạm nút ghi › **Bắt đầu phát**. Khi phát, góc màn hình có chấm đỏ; tắt bằng cách chạm chấm đỏ.

Bản TestFlight dùng được 90 ngày; chạy lại bước 6 để có bản mới.

## Cách app giữ nhẹ

- Bàn phím viết bằng UIKit thuần, không thư viện ngoài, build tối ưu kích thước (`-Osize`).
- Mỗi lần bấm Dịch là một request, tối đa 8 tin ngữ cảnh và 1.500 ký tự; không lưu gì phía OpenAI (`store: false`).
- Đọc màn hình chỉ chạy khi bàn phím Dịch Apple đang mở, tối đa 1 khung hình mỗi 1,5 giây, bỏ qua khi màn hình không đổi, chỉ đọc vùng khung chat. Không lưu ảnh, chỉ giữ chữ của vài tin cuối.

## Cần thử trên máy thật

- Bàn phím đọc được bao nhiêu chữ trong ô nhập của Zalo, Messenger, WeChat (iOS có thể chỉ trả một phần).
- Vision có nhận chữ tiếng Việt không (tiếng Trung, Anh, Hàn, Nhật được hỗ trợ).
- Bộ nhớ của phần đọc màn hình dưới giới hạn khoảng 50 MB.
