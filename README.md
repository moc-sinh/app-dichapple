# Dịch Apple

Bàn phím iPhone gõ tiếng Việt (Telex) có thanh **Dịch** ở trên: bấm Dịch là cả đoạn trong ô nhập đổi sang ngôn ngữ của người nhận (中文, English, 한국어, 日本語) bằng `gpt-6-luna`. Tin đối tác gửi thì copy rồi bấm **Tin copy** để đọc bằng tiếng Việt. Bản dịch dùng ngữ cảnh: các tin vừa copy hoặc vừa dịch, và chữ đọc được trên màn hình khi bật chế độ đọc màn hình.

| Thư mục | Nội dung |
|---|---|
| `App/` | App chính (SwiftUI): cài bàn phím, API key, ngôn ngữ, đọc màn hình, hồ sơ và thuật ngữ |
| `Keyboard/` | Bàn phím (UIKit): bộ gõ Telex, thanh Dịch, thay chữ trong ô nhập |
| `Screen/` | Phần mở rộng ReplayKit đọc chữ trên màn hình bằng Vision, ngay trên máy |
| `Shared/` | Cấu hình, lưu dữ liệu chung (App Group), gọi OpenAI |
| `preview/` | Trang xem trước giao diện trên web |
| `Tests/` | Kiểm thử bộ gõ Telex |

Project Xcode được sinh từ `project.yml` bằng XcodeGen. Mỗi lần push, GitHub Actions build file cài `DichApple.ipa`; dùng cá nhân thì cài bằng Sideloadly với Apple ID miễn phí. Từng bước, không cần Mac: [docs/HUONG-DAN.md](docs/HUONG-DAN.md).
