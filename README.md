# Dịch Apple

Bàn phím iPhone để nhắn tin với đối tác nước ngoài:

- **Gõ tiếng Việt như bàn phím Apple** (Telex), có thanh **Dịch** ở trên. Bấm Dịch là cả đoạn đổi sang ngôn ngữ đối tác; **Tự động** trả lời đúng ngôn ngữ đối tác đang dùng (14 ngôn ngữ).
- **Kiểm tra trước khi gửi**: dòng trên cùng hiện nghĩa dịch ngược và lưu ý khi câu gốc dễ hiểu sai; có Hoàn tác.
- **Đọc tin đối tác ngay**: bản dịch tin mới nhất hiện sẵn khi chạm vào ô trả lời (từ màn hình hoặc tin vừa sao chép), hoặc gõ hai lần vào lưng iPhone.
- **Dịch có ngữ cảnh**: dùng các tin gần nhất của cuộc chat, hồ sơ, cách xưng hô và thuật ngữ của bạn. Mặc định dùng GPT-6.1 Sol để có bản dịch chuẩn nhất.

| Thư mục | Nội dung |
|---|---|
| `App/` | App chính (SwiftUI): cài đặt, thao tác Phím tắt “Dịch tin đã sao chép” |
| `Keyboard/` | Bàn phím (UIKit): phím chuẩn iPhone, bộ gõ Telex, thanh Dịch, dòng thông tin |
| `Screen/` | Phần mở rộng ReplayKit đọc chữ trên màn hình bằng Vision, ngay trên máy |
| `Shared/` | Cấu hình, dữ liệu chung (App Group), nhận diện ngôn ngữ, gọi OpenAI |
| `preview/` | Trang xem trước giao diện trên web |
| `Tests/` | Kiểm thử bộ gõ Telex |

Project Xcode được sinh từ `project.yml` bằng XcodeGen. Mỗi lần push, GitHub Actions chạy kiểm thử và build file cài `DichApple.ipa`; dùng cá nhân thì cài bằng Sideloadly với Apple ID miễn phí. Cài đặt và cách dùng, không cần Mac: [docs/HUONG-DAN.md](docs/HUONG-DAN.md).
