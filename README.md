# MSTrans

Bàn phím iPhone của **Mộc Sinh** để nhắn tin với đối tác nước ngoài:

- **Gõ tiếng Việt như bàn phím Apple** (Telex), có thanh **Dịch** ở trên. Bấm Dịch là cả đoạn đổi sang ngôn ngữ đối tác; **Tự động** trả lời đúng ngôn ngữ đối tác đang dùng (14 ngôn ngữ).
- **Kiểm tra trước khi gửi**: sau khi bấm Dịch, một dòng hiện nghĩa dịch ngược và lưu ý khi câu gốc dễ hiểu sai; có Hoàn tác.
- **Chạm lưng iPhone**: sao chép rồi gõ hai lần vào lưng máy là dịch được cả hai chiều, ở mọi app, không cần mở bàn phím.
- **Đọc tin đối tác**: sao chép tin rồi chạm vào ô trả lời, bản dịch hiện ngay trên bàn phím (hoặc gõ hai lần vào lưng iPhone để dịch không cần mở bàn phím).
- **Dịch có ngữ cảnh**: dùng các tin gần nhất của cuộc chat, hồ sơ, cách xưng hô và thuật ngữ của bạn. Mặc định dùng GPT-6.1 Sol để có bản dịch chuẩn nhất.
- **App MSTrans** có 4 tab: **Dịch** (dịch cả hai chiều ngay trong app), **Lịch sử** (mọi tin đã dịch, tìm kiếm, đánh dấu), **Thuật ngữ** (cặp từ luôn dịch cố định) và **Cài đặt**.

Màu lấy từ logo Mộc Sinh: xanh là bạn và mọi nút bấm, cam là đối tác, vàng là lưu ý cần đọc lại.

| Thư mục | Nội dung |
|---|---|
| `App/` | App chính (SwiftUI): 4 tab, thao tác Phím tắt “Dịch tin đã sao chép” |
| `Keyboard/` | Bàn phím (UIKit): phím chuẩn iPhone, thanh Dịch, dòng thông tin; `Keyboard/TelexCore/` là bộ gõ Telex của [VietTelex](https://github.com/ptrinh/viettelex) (MIT) |
| `Shared/` | Màu và logo, cấu hình, dữ liệu chung (App Group), lịch sử, nhận diện ngôn ngữ, gọi OpenAI |
| `preview/` | Trang xem trước giao diện trên web |
| `Tests/` | Kiểm thử bộ gõ Telex: các ca của MSTrans và 9.091 ca của VietTelex (`Tests/Suite`) |
| `ci/` | Chạy thử bàn phím và chụp màn hình app trên trình giả lập iOS |

Project Xcode được sinh từ `project.yml` bằng XcodeGen. Mỗi lần push, GitHub Actions chạy kiểm thử, build file cài `MSTrans.ipa`, rồi mở bàn phím trong trình giả lập iOS để chắc chắn nó hiện lên (kể cả khi đã bật truy cập đầy đủ và có chữ trong bộ nhớ tạm) và chụp màn hình từng tab. Dùng cá nhân thì cài bằng AltStore với Apple ID miễn phí (Sideloadly ký sai bàn phím trên iOS 26, bàn phím không mở được). Cài đặt và cách dùng, không cần Mac: [docs/HUONG-DAN.md](docs/HUONG-DAN.md).
