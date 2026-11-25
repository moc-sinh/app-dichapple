# Checkpoint: MSTrans Android 1.7.0, tự động dịch màn hình

Cập nhật lúc 26/11/2026 (máy tính Windows có Android SDK). Nhánh `mstrans/auto-screen-translate`.
Người yêu cầu: Minh (Mộc Sinh). Đặc tả gốc là tin nhắn "NHIỆM VỤ: NÂNG CẤP MSTRANS ANDROID — AUTO
SCREEN TRANSLATION" ngày 25/11/2026, tóm tắt ở mục 2.

## 0. Đọc trước khi làm tiếp

- **Trạng thái 26/11/2026:** đã viết xong mã của tính năng (mục 10, bước 1 đến 6 phần build/lint), test lõi xanh,
  APK 1.7.0 build được, lint không thêm lỗi. **Chưa thử trên điện thoại hay emulator nào** (máy tính chưa có
  điện thoại cắm vào, Windows chưa bật ảo hoá cho emulator): cả 20 tình huống ở mục 11 vẫn **chưa kiểm**. Chưa push
  các commit mới (CI chưa chạy với ML Kit). Xem mục 13.
- **Quy tắc của Minh:** trả lời bằng tiếng Việt dễ hiểu; chạy test trước mỗi lần push; một phiên một
  nhánh (phiên cloud đã ngừng đẩy lên nhánh này); không merge, không phát hành công khai; không đổi
  khóa ký; không ghi tên model AI vào commit, PR hay mã; `git fetch` trước khi push; không báo
  "xong" chức năng chưa kiểm thử.
- Lịch sử cả app ở `docs/CHECKPOINT.md`. File này chỉ nói về tính năng dịch màn hình.

## 1. Nhánh và commit

| Commit | Nội dung |
|---|---|
| `cf4f40f` | Điểm xuất phát: đầu nhánh giao hàng `claude/funny-gates-s6pl2c` (Android 1.6.0, mã ở `389a193`) |
| `0740c90` | CI: chỉ nhánh giao hàng đăng Release công khai. Nhánh khác tạo Release **nháp** (chỉ người có quyền ghi repo thấy), không giữ artifact công khai. Thêm bước kiểm APK (package, versionCode, chữ ký, SHA-256) và Android Lint, kết quả ghi thành annotation |
| `f985b9f` | Bảng chuyển chữ Phồn thể → Giản thể (3.221 cặp, từ OpenCC, giấy phép Apache 2.0). Chưa có mã nào dùng |
| commit chứa file này | Checkpoint + sửa `ci/android-local-check.sh` (Maven Central trả 429), commit `[skip ci]` |

Rollback: mọi thay đổi chỉ nằm trên nhánh này. Nhánh giao hàng `claude/funny-gates-s6pl2c` không bị
đụng tới. Bỏ nhánh này là quay về 1.6.0 nguyên vẹn.

**CI run Android #14** (commit `f985b9f`): xanh, đã xác nhận nhánh thử chỉ tạo Release nháp. Chi tiết
và mốc Android Lint ở mục 9.

## 2. Tóm tắt yêu cầu

- Mở MSTrans, bật "tự động dịch màn hình", cấp quyền lần đầu, mở Taobao như bình thường: chữ Trung
  tự dịch sang tiếng Việt và tự cập nhật khi cuộn, mở sản phẩm, đổi danh mục, chuyển màn hình. Không
  bấm dịch thủ công, vẫn thao tác app gốc bình thường. Không tạo app riêng, không sửa APK Taobao, không
  root, không Shizuku, không vượt FLAG_SECURE.
- App ưu tiên (tên package đã đối chiếu, xem mục 3): Taobao `com.taobao.taobao` (chọn sẵn, nhưng tính
  năng **tắt** cho tới khi người dùng tự bật và cấp quyền), 1688 `com.alibaba.wireless`, Pinduoduo
  `com.xunmeng.pinduoduo`, JD `com.jingdong.app.mall`, WeChat `com.tencent.mm`, Alibaba.com
  `com.alibaba.intl.android.apps.poseidon`. Người dùng chọn được phạm vi app.
- Mặc định dịch Trung → Việt **ngoại tuyến**: không API key, không tốn phí mỗi lần cuộn, có hướng dẫn
  tải mô hình lần đầu, chạy được khi mất mạng. Chế độ AI nâng cao (dùng OpenAI sẵn có trong MSTrans)
  chỉ khi người dùng tự chọn, không gửi cả màn hình, có kiểm soát chi phí.
- Từ điển thương mại điện tử (30 thuật ngữ bắt buộc ở mục 7). Tuyệt đối không nhầm Đăng xuất với Xóa
  tài khoản. Bảo toàn số tiền, số lượng, kích thước, mã sản phẩm, %, dấu âm, đơn vị; không chắc thì
  giữ nguyên chữ gốc. Dùng chung tab Thuật ngữ, không làm hai hệ thống từ điển.
- Yêu cầu hiệu năng, bảo mật (mục 8), giao diện (mục 5, điểm 12), quy trình GitHub, 20 tình huống thử
  trên máy (mục 11). Đầu ra: mã, nhánh, APK cài được, báo cáo kiểm thử, lỗi đã sửa và giới hạn, báo cáo
  hiệu năng và bảo mật **có số đo thật**, hướng dẫn bật, hướng dẫn cập nhật đè, danh sách commit.
- Phiên bản: 1.7.0.

## 3. Audit (đã xác minh ngày 25/11/2026)

- Repo `moc-sinh/app-dichapple` **Public**. Nhánh mặc định `claude/stoic-hopper-f60iid` (iOS cũ).
  Android nằm ở `claude/funny-gates-s6pl2c`. Nhánh `claude/android-apk` (commit `0f223c4` trong đặc tả)
  là bản 1.0.0 cũ, đã gộp qua PR #2 ngày 26/10/2026, **không dùng lại**.
- APK 1.6.0 đang phát hành (`android-1.6.0-13`) đã tải về và đọc lại: package `vn.mocsinh.mstrans`,
  versionCode 13, versionName 1.6.0, minSdk 26, targetSdk 34, ký APK Signature Scheme v2 (không v1, không
  v3), chứng thư SHA-256
  `FF:13:E1:B4:51:1E:99:15:A7:61:71:09:5E:82:DF:BF:98:F6:CB:49:16:92:9B:A3:78:3C:54:61:99:4A:6E:B9`,
  **trùng** chứng thư của khóa `android/app/mstrans-sideload.p12` trong repo. SHA-256 của file APK 1.6.0:
  `0e89f6e8b324b21066181743a75d90f3f6e85398b64e2d643a24e3aa2941012b`. Quyền hiện có: INTERNET,
  RECORD_AUDIO, MODIFY_AUDIO_SETTINGS, VIBRATE.
- versionCode = biến môi trường `MSTRANS_BUILD` (CI đặt bằng số thứ tự run của workflow Android, mặc
  định là 1 khi build tay). versionName ở `android/app/build.gradle.kts`.
- Công cụ: compileSdk 35, targetSdk 34, minSdk 26 (Android 8), AGP 8.7.3, Kotlin 2.2.0, Gradle 8.14.3,
  JDK 17, coroutines 1.10.2, OkHttp 4.12.0, kotlinx-serialization 1.8.1. **Không dùng androidx**
  (`android.useAndroidX=false`), giao diện dựng bằng View trong code.
- Cấu trúc: `android/core` (Kotlin thuần, có test JUnit), `android/app` (Android). App có 5 tab trong
  `MainActivity` (Dịch, Hội thoại, Lịch sử, Thuật ngữ, Cài đặt), trang con mở bằng
  `activity.push(page(...))` (xem `ui/Pages.kt`). Hàm dựng giao diện ở `ui/Ui.kt`: `section`, `row`,
  `switchRow`, `choose`, `confirm`, `toast`, `Segmented`, `pill`, `emptyState`. Cài đặt lưu trong
  SharedPreferences `mstrans` qua `core/Settings.kt` (`KeyValueStore`).
- Thuật ngữ: `Settings.glossary` là chuỗi, mỗi dòng `từ gốc = bản dịch` (vế phải trống = giữ nguyên),
  đọc bằng `core.Glossary.parse`, sửa ở `ui/GlossaryScreen.kt`, đổi thuật ngữ thì gọi
  `settings.clearIncomingCache()`.
- Chế độ AI có sẵn: `core.Translator(apiKey, model, settings)`, hàm `write(instructions, input)` trả
  về chữ, model rẻ nhanh là `TranslationModel.LUNA`.
- Kiểm thử gốc trên cloud: `bash ci/android-local-check.sh` → test core đạt, mã app biên dịch được (chỉ
  2 cảnh báo cũ ở `MainActivity.kt`) sau khi sửa kho Maven trong script.
- Môi trường cloud: `dl.google.com` bị chặn (không tải được ML Kit, AGP, Android SDK), không có KVM
  (không chạy được emulator). Tải được file đính kèm Release qua `gh api`, không tải được artifact.

## 4. Môi trường và lệnh

### Trên máy tính có Android SDK (Windows, PowerShell, trong thư mục `android`)

- JDK 17 (hoặc JBR đi kèm Android Studio). SDK: `platforms;android-35`, `build-tools;35.0.0`.
- **Bắt buộc đặt `ANDROID_HOME`.** `settings.gradle.kts` chỉ thêm module `:app` khi `ANDROID_HOME`
  hoặc `ANDROID_SDK_ROOT` trỏ tới thư mục có thật. `local.properties` của Android Studio là không đủ.
  Ví dụ: `$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"`.
- **Bắt buộc đặt `MSTRANS_BUILD` lớn hơn versionCode đang cài trên điện thoại** (đang là 13). Không đặt
  thì versionCode = 1 và Android từ chối cài đè. Dùng số = số run Android mới nhất trên GitHub + 1 (sau
  run #14 thì dùng 15). Không dùng số quá lớn: bản CI sau đó có số nhỏ hơn sẽ không cài đè được.
  Ví dụ: `$env:MSTRANS_BUILD = "15"`.
- Lệnh:
  - `.\gradlew.bat :core:test`: test lõi.
  - `.\gradlew.bat :app:assembleRelease` → `app\build\outputs\apk\release\app-release.apk` (ký bằng
    khóa sideload trong repo).
  - `.\gradlew.bat :app:lintRelease` → `app\build\reports\lint-results-release.html`.
  - Chữ ký: `& "$env:ANDROID_HOME\build-tools\35.0.0\apksigner.bat" verify --verbose --print-certs app\build\outputs\apk\release\app-release.apk`
    (phải ra đúng chứng thư SHA-256 ở mục 3).
  - Package và versionCode: `& "$env:ANDROID_HOME\build-tools\35.0.0\aapt2.exe" dump badging app\build\outputs\apk\release\app-release.apk | Select-Object -First 1`.
  - SHA-256: `Get-FileHash app\build\outputs\apk\release\app-release.apk -Algorithm SHA256`.
  - Cài đè lên điện thoại: `adb install -r app\build\outputs\apk\release\app-release.apk`. Không gỡ app
    cũ, không dùng `-d`: gỡ là mất lịch sử, key và cài đặt.
  - Đo: `adb shell dumpsys meminfo vn.mocsinh.mstrans`, `adb shell top -b -n 1 | findstr mstrans`,
    `adb shell dumpsys batterystats vn.mocsinh.mstrans`, `adb logcat -s MSTransScreen` (tag dự kiến; chỉ log thời
    gian và số lượng, **không log chữ trên màn hình**).

### Trên máy cloud (không có SDK)

- `bash ci/android-local-check.sh`: test core rồi biên dịch mã app với `android-all` lấy từ Maven
  Central. Khi app có ML Kit, script này sẽ không biên dịch được phần ML Kit (cloud không tới được Google
  Maven) trừ khi thêm stub. Build thật thì dùng máy tính hoặc CI.

## 5. Kiến trúc đã chốt

1. **Một AccessibilityService** trong MSTrans (`app/.../screen/ScreenTranslationService.kt`). Lớp phủ
   dùng `TYPE_ACCESSIBILITY_OVERLAY` với `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN
   | FLAG_LAYOUT_NO_LIMITS`: chạm xuyên qua xuống Taobao, **không cần quyền vẽ trên app khác**
   (SYSTEM_ALERT_WINDOW). Lớp phủ trợ năng là cửa sổ tin cậy nên không bị chặn chạm của Android 12+.
2. **Nguồn chữ:**
   - (a) Cây giao diện qua Trợ năng: mọi bản Android 8+, có toạ độ chính xác, rẻ.
   - (b) OCR ảnh chụp màn hình để đọc chữ trong ảnh sản phẩm. Android 14+: `takeScreenshotOfWindow(windowId)`
     chỉ chụp cửa sổ app (không dính lớp phủ của mình). Android 11 đến 13: `takeScreenshot(display)`,
     ẩn lớp phủ khoảng 2 khung hình trước khi chụp, hiện lại ngay sau khi chụp xong. Android 8 đến 10:
     bản 1.7.0 chỉ đọc chữ qua Trợ năng (MediaProjection để sau, ghi là giới hạn).
3. **Gộp nguồn:** ưu tiên nút Trợ năng. Bỏ nút cha có chữ trùng chữ các nút con. Bỏ dòng OCR nằm đè hơn
   50% diện tích lên một nút Trợ năng, hoặc có chữ nằm trong chữ của nút giao với nó. Bỏ phần ngoài cửa
   sổ app, vùng bàn phím, thanh trạng thái.
4. **Thứ tự dịch mỗi đoạn chữ:**
   1. Thuật ngữ của người dùng (tab Thuật ngữ, vế trái có chữ Hán), khớp nguyên đoạn.
   2. Từ điển thương mại điện tử có sẵn (trong `core`, chỉ đọc), khớp nguyên đoạn sau khi cắt khoảng
      trắng và đổi Phồn thể → Giản thể.
   3. Mẫu câu có số (mục 7).
   4. ML Kit dịch Trung → Việt ngoại tuyến. Trước khi dịch, thay thuật ngữ trong câu bằng bản dịch tiếng
      Việt của nó; sau khi dịch, kiểm tra: mọi số, giá, %, dấu âm, ký hiệu tiền, mã hàng còn nguyên văn;
      các thuật ngữ đã thay còn nguyên; luật thuật ngữ nguy hiểm (có `注销` thì bản dịch phải có "xóa"
      hoặc "hủy" và không có "đăng xuất"; có `退出登录`/`退出当前账户` thì phải có "đăng xuất" hoặc
      "thoát" và không có "xóa").
   5. Kiểm tra trượt → dịch từng khúc: chỉ dịch các khúc chữ Hán, mọi thứ khác giữ nguyên văn.
   6. Không có bản dịch đáng tin → giữ chữ gốc, không phủ.
5. **Phồn thể:** đổi sang Giản thể trước khi tra từ điển và dịch, bằng bảng
   `android/core/src/main/resources/vn/mocsinh/mstrans/core/screen/ts_characters.txt` (đã commit).
6. **Thuật ngữ:** dùng lại `Settings.glossary`, không tạo hệ thống thứ hai. Thuật ngữ người dùng đè
   lên từ điển có sẵn. Đổi thuật ngữ thì xóa bộ nhớ đệm bản dịch.
7. **Khi nào quét:**
   - `TYPE_WINDOW_STATE_CHANGED` (màn hình mới): xóa lớp phủ, tăng số thế hệ, quét sau khoảng 250 ms.
   - `TYPE_VIEW_SCROLLED`: ẩn lớp phủ ngay, quét khi ngừng cuộn (chờ khoảng 350 đến 450 ms).
   - `TYPE_WINDOW_CONTENT_CHANGED`: chỉ quét khi "chữ ký" chữ Hán của cây giao diện đổi (bỏ qua chữ số
     để đồng hồ đếm ngược không gây quét liên tục).
   - `TYPE_WINDOWS_CHANGED`: đổi app, bàn phím hiện hoặc ẩn.
   - Mỗi lần quét mang một số thế hệ: kết quả của thế hệ cũ bị bỏ, nên bản dịch trang trước không hiện
     trên trang mới. Có khoảng cách tối thiểu giữa hai lần quét theo mức tốc độ. Chữ ký ảnh (lưới độ
     sáng 24×48) để bỏ OCR khi ảnh không đổi.
8. **Hiệu năng:** duyệt cây ngoài luồng giao diện, có giới hạn số nút và độ sâu; mỗi lúc một lần quét
   (huỷ lần cũ); tối đa 2 việc dịch song song; vẽ ngay các đoạn có sẵn trong từ điển hoặc bộ nhớ đệm, rồi
   vẽ dần các đoạn dịch xong (gom khoảng 150 ms); bộ nhớ đệm LRU 600 mục, **chỉ trong RAM**; ảnh chụp:
   chép HardwareBuffer sang bitmap thường, đóng buffer ngay, recycle bitmap sau OCR; đóng bộ OCR, bộ dịch
   và huỷ coroutine trong `onDestroy`/`onUnbind`; tạm dừng khi tắt màn hình, khi sang app không chọn, khi
   có bàn phím, khi gặp màn hình nhạy cảm.
9. **Quyền riêng tư:** xem mục 8.
10. **Vẽ lớp phủ:** một View toàn màn hình vẽ mọi khung bằng `StaticLayout`; cỡ chữ tự co (tìm nhị phân
    từ khoảng 9 sp tới chiều cao khung); quá dài thì cắt "…", **không bao giờ tràn ngang** sang vùng điều
    khiển bên cạnh; màu nền lấy mẫu quanh khung trong ảnh chụp (nếu có), không có thì theo giao diện
    sáng/tối; màu chữ theo độ tương phản; hiện dần để bớt nhấp nháy; xoay màn hình thì xóa và quét lại;
    bù toạ độ theo vị trí thật của View trên màn hình.
11. **Nút nổi** (lớp phủ trợ năng nhỏ, kéo được): chạm = xem chữ gốc / hiện bản dịch; giữ lâu = tạm dừng
    / chạy tiếp.
12. **Giao diện:** thêm dòng "Dịch màn hình" trong tab Cài đặt, mở một trang riêng gồm: công tắc tự động
    dịch; trạng thái quyền Trợ năng và nút mở cài đặt (kèm hướng dẫn "Cho phép cài đặt bị hạn chế" trên
    Android 13+ và nút mở Thông tin ứng dụng); trạng thái gói dịch + nút tải (tùy chọn chỉ Wi-Fi) + nút xóa;
    danh sách app (6 app trên, ghi app nào đã cài, và "Thêm ứng dụng khác" từ danh sách app có biểu tượng);
    ngôn ngữ nguồn (Trung giản thể và phồn thể) và đích (Việt), cố định; chế độ Ngoại tuyến / AI nâng cao;
    mức tốc độ (Tiết kiệm pin / Cân bằng / Nhanh); công tắc đọc chữ trong ảnh; công tắc nút nổi; tạm dừng;
    hướng dẫn tiếng Việt; dòng lỗi gần nhất. Giữ nguyên thiết kế hiện có.
13. **Thư viện** (phiên bản cần xác minh lại trên maven.google.com từ máy tính; dự án overlay-translator
    bản 24/10/2026 đang dùng đúng các bản này):
    `com.google.mlkit:text-recognition-chinese:16.0.1` (mô hình đi kèm APK, chạy ngoại tuyến ngay),
    `com.google.mlkit:translate:17.0.3` (tải mô hình `zh` và `vi` một lần, dịch qua tiếng Anh có sẵn),
    `org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2`. Play services kéo theo androidx nên
    phải đổi `android.useAndroidX=true`. Cân nhắc `abiFilters` `arm64-v8a`, `armeabi-v7a` (thêm `x86_64`
    nếu thử trên emulator) để APK không phình.
14. **AI nâng cao:** `Translator(apiKey, TranslationModel.LUNA, settings).write(...)`, đầu vào và đầu ra
    JSON; chỉ gửi các đoạn chữ từ điển và bộ nhớ đệm chưa có; mỗi lần tối đa 40 đoạn hoặc 3.000 ký tự;
    một yêu cầu một lúc; có trần số yêu cầu mỗi 10 phút; kết quả qua cùng bộ kiểm tra số; không có key
    hoặc vượt trần thì quay về ngoại tuyến và báo trên trang Cài đặt. Không bao giờ gửi ảnh.
15. **Cấu hình dịch vụ** (`res/xml/screen_translation_service.xml`): eventTypes
    `typeWindowStateChanged|typeWindowContentChanged|typeViewScrolled|typeWindowsChanged`; flags
    `flagRetrieveInteractiveWindows|flagReportViewIds|flagIncludeNotImportantViews`;
    `canRetrieveWindowContent=true`; `canTakeScreenshot=true`; `notificationTimeout` khoảng 150;
    `isAccessibilityTool=false`; không có `canPerformGestures`; `description` là chuỗi tiếng Việt nói rõ
    đọc gì, không đọc gì. Manifest: service `exported=true` với
    `permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`, thêm `<queries>` cho 6 package và
    intent MAIN/LAUNCHER. Kiểm từng thuộc tính `android:` với `android.R$attr` trước khi push (đã từng
    hỏng vì sai tên thuộc tính).

## 6. Kế hoạch file và test

`android/core/src/main/kotlin/vn/mocsinh/mstrans/core/screen/` (Kotlin thuần, test được):

| File | Việc |
|---|---|
| `ChineseText.kt` | Nhận biết chữ Hán, đổi Phồn → Giản (đọc bảng tài nguyên), chuẩn hoá khoá tra |
| `CommerceDictionary.kt` | Từ điển có sẵn, mẫu câu có số, tra khớp dài nhất |
| `NumberGuard.kt` | Lấy số, giá, %, dấu âm, ký hiệu tiền, mã hàng; kiểm tra còn nguyên văn |
| `ScreenTranslator.kt` | Thứ tự dịch ở mục 5 điểm 4, thuật ngữ, luật thuật ngữ nguy hiểm, LRU, giao diện `TextEngine` |
| `ScreenTextMerger.kt` | Gộp và khử trùng chữ từ Trợ năng và OCR |
| `SensitiveScreen.kt` | Quyết định tạm dừng (mật khẩu, bàn phím, từ khóa, cửa sổ bảo vệ) |
| `FrameSignature.kt` | Chữ ký ảnh và so sánh |
| `ScreenController.kt` | Máy trạng thái: sự kiện → hành động (xóa phủ, hẹn quét, bỏ kết quả cũ), đồng hồ giả để test |
| `ScreenSettings.kt` | Khóa cài đặt, mặc định (tắt; Taobao chọn sẵn), mức tốc độ |
| `OverlayFit.kt` | Co cỡ chữ (hàm đo chữ truyền vào), chọn màu theo tương phản |
| `ScreenAi.kt` | Dựng yêu cầu và đọc kết quả chế độ AI |

Test ở `android/core/src/test/kotlin/vn/mocsinh/mstrans/core/screen/`: đủ 30 thuật ngữ bắt buộc;
Đăng xuất khác Xóa tài khoản (cả khi bộ dịch giả cố tình dịch sai); số, giá, %, dấu âm, đơn vị, mã hàng
giữ nguyên; Phồn thể ra cùng bản dịch với Giản thể; thuật ngữ người dùng đè từ điển; gộp nguồn không
trùng; màn hình nhạy cảm; máy trạng thái (cuộn, đổi trang, đổi app, khóa màn hình, kết quả cũ bị bỏ);
chữ ký ảnh; co chữ; chế độ AI với MockWebServer (đã có sẵn trong test core).

`android/app/src/main/kotlin/vn/mocsinh/mstrans/screen/`: `ScreenTranslationService.kt`,
`ScreenCapture.kt`, `MlKitEngines.kt` (OCR, dịch, quản lý mô hình), `OverlayView.kt`,
`OverlayController.kt`, `FloatingButton.kt`, `ScreenState.kt` (trạng thái chung cho trang Cài đặt). Thêm
`ui/ScreenTranslationPage.kt`, `res/xml/screen_translation_service.xml`, `res/values/strings.xml` (chuỗi
mô tả dịch vụ).

## 7. Từ điển

**30 thuật ngữ bắt buộc (đúng nguyên văn đặc tả):** 我的淘宝 = Taobao của tôi · 设置 = Cài đặt ·
退出登录 = Đăng xuất · 退出当前账户 = Thoát tài khoản hiện tại · 切换账户 = Chuyển tài khoản ·
注销账号 = Xóa tài khoản · 登录 = Đăng nhập · 我的订单 = Đơn hàng của tôi · 待付款 = Chờ thanh toán ·
待发货 = Chờ gửi hàng · 待收货 = Chờ nhận hàng · 收货地址 = Địa chỉ nhận hàng · 购物车 = Giỏ hàng ·
加入购物车 = Thêm vào giỏ hàng · 立即购买 = Mua ngay · 起批量 = Số lượng nhập tối thiểu ·
一件代发 = Gửi hàng lẻ trực tiếp · 混批 = Nhập sỉ kết hợp nhiều mẫu · 现货 = Hàng có sẵn ·
预售 = Hàng đặt trước · 包邮 = Miễn phí vận chuyển · 不含税 = Chưa bao gồm thuế · 含税 = Đã bao gồm thuế ·
规格 = Quy cách · 库存 = Tồn kho · 型号 = Mã mẫu · 物流 = Vận chuyển · 运费 = Phí vận chuyển ·
退款 = Hoàn tiền · 售后 = Hậu mãi.

**Đề xuất bổ sung (chưa rà, cần kiểm lại trước khi đưa vào):**
- Tài khoản, cài đặt: 账号与安全 = Tài khoản và bảo mật · 个人信息 = Thông tin cá nhân · 隐私 = Quyền
  riêng tư · 通用 = Cài đặt chung · 消息通知 = Thông báo tin nhắn · 清除缓存 = Xóa bộ nhớ đệm ·
  帮助与反馈 = Trợ giúp và phản hồi · 注册 = Đăng ký · 密码 = Mật khẩu · 修改密码 = Đổi mật khẩu ·
  验证码 = Mã xác minh · 确定 = Đồng ý · 确认 = Xác nhận · 取消 = Hủy · 返回 = Quay lại · 完成 = Xong ·
  保存 = Lưu · 编辑 = Sửa · 删除 = Xóa · 搜索 = Tìm kiếm · 分享 = Chia sẻ.
- Mua sắm: 首页 = Trang chủ · 推荐 = Đề xuất · 分类 = Danh mục · 全部 = Tất cả · 筛选 = Lọc ·
  综合 = Tổng hợp · 销量 = Lượt bán · 价格 = Giá · 新品 = Hàng mới · 店铺 = Cửa hàng · 进店 = Vào cửa
  hàng · 旗舰店 = Cửa hàng chính hãng · 宝贝 = Sản phẩm · 商品 = Sản phẩm · 商品详情 = Chi tiết sản phẩm ·
  参数 = Thông số · 评价 = Đánh giá · 问大家 = Hỏi người đã mua · 收藏 = Lưu yêu thích · 关注 = Theo dõi ·
  足迹 = Đã xem · 优惠券 = Phiếu giảm giá · 领券 = Nhận phiếu giảm giá · 红包 = Hồng bao · 秒杀 = Flash
  sale · 正品 = Hàng chính hãng · 结算 = Thanh toán · 提交订单 = Đặt hàng · 付款 = Thanh toán ·
  支付宝 = Alipay · 数量 = Số lượng · 颜色 = Màu sắc · 尺码 = Cỡ · 尺寸 = Kích thước · 材质 = Chất liệu ·
  品牌 = Thương hiệu · 产地 = Xuất xứ · 重量 = Trọng lượng.
- Đơn hàng, vận chuyển: 待评价 = Chờ đánh giá · 已发货 = Đã gửi hàng · 确认收货 = Xác nhận đã nhận hàng ·
  查看物流 = Xem vận chuyển · 快递 = Chuyển phát nhanh · 运费险 = Bảo hiểm phí vận chuyển · 退货 = Trả
  hàng · 换货 = Đổi hàng · 七天无理由退货 = Đổi trả trong 7 ngày không cần lý do.
- Nhập hàng (1688): 供应商 = Nhà cung cấp · 厂家 = Nhà sản xuất · 源头工厂 = Xưởng gốc · 批发 = Bán sỉ ·
  起订量 = Số lượng đặt tối thiểu · 代发 = Gửi hàng hộ · 定制 = Đặt làm riêng · 询价 = Hỏi giá ·
  发票 = Hóa đơn · 开票 = Xuất hóa đơn · 增值税 = Thuế GTGT · 样品 = Hàng mẫu · 拿样 = Lấy mẫu.

**Mẫu câu có số** (X, Y giữ nguyên văn; `万` viết thành "vạn", chữ số không đổi): 满X减Y = Mua đủ X giảm
Y · X人付款 = X người đã mua · 已售X = Đã bán X · 月销X = Bán X/tháng · X件起批 = Sỉ từ X cái ·
X天内发货 = Gửi hàng trong X ngày · X小时内发货 = Gửi hàng trong X giờ · 共X件 = Tổng X sản phẩm ·
X条评价 = X đánh giá · 券后X = Sau phiếu giảm X.

## 8. Bảo mật, quyền riêng tư, khóa ký

- Chỉ chạy khi người dùng bật công tắc **và** cấp quyền Trợ năng. Mặc định tắt.
- Chỉ xử lý app đã chọn: sự kiện của app khác bị bỏ ngay, không đọc nội dung. Bỏ qua chính MSTrans.
- Không bao giờ nhấn, nhập chữ, cuộn hộ hay giao dịch: không gọi `performAction`, không có quyền cử chỉ.
- Ảnh chụp chỉ ở RAM, không ghi ra bộ nhớ. Không log chữ hay ảnh màn hình. Chế độ ngoại tuyến không gửi
  gì ra mạng. Chế độ AI chỉ gửi chữ của đoạn chưa dịch, không gửi ảnh, chỉ khi người dùng chọn.
- Tạm dừng và xóa lớp phủ khi: có ô mật khẩu (`isPassword`), bàn phím đang hiện, từ khóa nhạy cảm
  (密码, 支付密码, 验证码, 银行卡, 安全键盘, 付款码, 收款码, CVV, 身份证…), cửa sổ có FLAG_SECURE (biết qua
  lỗi `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`; khi đó **ngừng dịch cả chữ Trợ năng** của cửa sổ đó). Nhận
  diện bằng từ khóa chỉ là lớp bổ sung, không phải bảo vệ tuyệt đối. Android 8 đến 10 không biết được cửa
  sổ có FLAG_SECURE hay không.
- Xóa bộ nhớ đệm trong RAM khi dừng, khi khóa màn hình, khi rời app đã chọn.
- Quyền mới dự kiến: chỉ dịch vụ Trợ năng (cộng các quyền ML Kit tự gộp vào manifest, phải đọc lại
  manifest gộp sau khi build). Không thêm SYSTEM_ALERT_WINDOW, không thêm quyền bộ nhớ.
- ML Kit có thể gửi số liệu sử dụng ẩn danh theo điều khoản của Google (không gồm ảnh hay chữ): ghi rõ
  trong báo cáo, không hứa "không gửi gì cả".
- **Khóa ký:** `android/app/mstrans-sideload.p12` và mật khẩu của nó nằm trong repo Public, nên coi như
  đã lộ: người khác có thể ký một APK mang tên MSTrans mà điện thoại chấp nhận là bản cập nhật. Chuyển
  repo sang Private không khắc phục được. **Không đổi khóa trong đợt này** (đổi là mất khả năng cập nhật
  đè). Đề xuất để Minh quyết: (a) trước mắt chỉ cài APK nhận qua kênh riêng và kiểm SHA-256; (b) về lâu
  dài xoay khóa bằng APK Signature Scheme v3 (`apksigner rotate` tạo chuỗi khóa cũ → mới, ký bản mới bằng
  khóa mới kèm chuỗi đó): Android 9+ cập nhật đè được, Android 8 thì không (phải gỡ cài lại hoặc nâng
  minSdk lên 28). Khóa mới để trong GitHub Secret, CI giải mã ra file rồi đặt `MSTRANS_KEYSTORE` (Gradle
  đã đọc sẵn biến này), bỏ khóa cũ khỏi cây mã (lịch sử git vẫn còn).

## 9. CI và cách giao APK kín

- Workflow `.github/workflows/android.yml`: test core → `assembleRelease` → kiểm APK (aapt2 badging,
  apksigner, SHA-256, dừng nếu package khác `vn.mocsinh.mstrans`) → Android Lint (lỗi lint ghi thành
  annotation, lint hỏng không chặn APK) → nhánh giao hàng `claude/funny-gates-s6pl2c`: artifact và
  Release công khai như cũ; **nhánh khác: Release nháp** `test-android-<version>-<run>` (kèm
  `MSTrans.apk.sha256`), không công khai, không thành "latest".
- Đọc kết quả khi không xem được log: `gh api repos/moc-sinh/app-dichapple/check-runs/<job id>/annotations`.
- Lấy APK từ Release nháp: `gh release download test-android-<version>-<run> -R moc-sinh/app-dichapple -p MSTrans.apk`
  (cần quyền ghi repo), rồi gửi Minh qua kênh riêng kèm SHA-256. Dọn bản nháp sau khi Minh cài:
  `gh release delete test-android-<version>-<run> -R moc-sinh/app-dichapple -y`.
- Run #14 (`f985b9f`, https://github.com/moc-sinh/app-dichapple/actions/runs/45654585409) **xanh**: test core
  đạt; APK `vn.mocsinh.mstrans`, versionCode 14, versionName 1.6.0, ký v2, chứng thư
  `ff13e1b4…994a6eb9` (trùng 1.6.0 và khóa trong repo); SHA-256 của APK
  `a4f34fb9fb42eb5704c2bd9291536eabe8565bfdc825546615331f5f7b9930d0`, 2.030.442 byte. Đã tạo Release nháp
  `test-android-1.6.0-14` (draft, prerelease); bước phát hành công khai bị bỏ qua đúng ý; Release công khai mới
  nhất vẫn là `android-1.6.0-13`. Bản nháp này chỉ là 1.6.0 dựng lại để thử quy trình, không gửi cho Minh, xóa
  được.
- **Mốc Android Lint của mã 1.6.0** (trước khi thêm tính năng): 1 lỗi `MissingPermission` ở
  `app/src/main/kotlin/vn/mocsinh/mstrans/audio/AndroidAudio.kt:128` (có từ trước: gọi ghi âm mà không kiểm
  quyền ngay tại chỗ) và 13 cảnh báo (DataExtractionRules 1, ObsoleteSdkInt 1, OldTargetApi 1, SetTextI18n 4,
  UnusedResources 3, VectorPath 1, ViewConstructor 2). Mã mới không được thêm lỗi lint nào; lỗi cũ nên sửa
  riêng (kiểm `checkSelfPermission` hoặc bắt `SecurityException` trước khi mở micro).

## 10. Việc tiếp theo (theo thứ tự)

1. Chuẩn bị máy: clone hoặc `git fetch`, checkout `mstrans/auto-screen-translate`; JDK 17, SDK, đặt
   `ANDROID_HOME`; chạy `.\gradlew.bat :core:test` và build 1.6.0 hiện tại để có mốc (ghi lại kích thước
   APK).
2. Xác minh phiên bản ML Kit và `kotlinx-coroutines-play-services` trên Google Maven; thêm phụ thuộc; đặt
   `android.useAndroidX=true`; build lại, đo kích thước APK trước và sau.
3. Viết lõi `core/screen` theo mục 6, viết test trước (TDD), cho tới khi xanh.
4. Viết phần app: dịch vụ, chụp màn hình, OCR và dịch ML Kit, lớp phủ, nút nổi, manifest, xml cấu hình.
5. Trang Cài đặt › Dịch màn hình và hướng dẫn tiếng Việt. Mọi nút phải chạy thật.
6. Đổi versionName thành 1.7.0. Build, lint, apksigner, cài đè lên 1.6.0 (13) trên điện thoại thật (hoặc
   emulator Android 11+ có dịch nhị phân ARM nếu chưa có điện thoại), chạy 20 tình huống ở mục 11, đo RAM,
   CPU, pin, độ trễ.
7. Push → CI → Release nháp → gửi APK kín cho Minh kèm SHA-256.
8. Báo cáo cho Minh: kiểm thử, lỗi đã sửa và giới hạn, hiệu năng và bảo mật (số đo thật), hướng dẫn bật,
   hướng dẫn cập nhật đè, danh sách commit và bản build. Cập nhật `docs/CHECKPOINT.md` và file này bằng
   commit `[skip ci]`. Mở PR nháp vào `claude/funny-gates-s6pl2c`, không merge.

## 11. Tình huống thử trên thiết bị: **chưa kiểm cái nào**

| # | Tình huống | Kết quả mong đợi |
|---|---|---|
| 1 | Mở Taobao sau khi bật | Chữ Trung hiện bản dịch trong khoảng 1 giây, không phải bấm |
| 2 | Cuộn nhanh danh sách | Bản dịch ẩn khi cuộn, hiện lại khi dừng, không lệch, không ANR |
| 3 | Mở chi tiết sản phẩm | Không còn bản dịch của trang trước |
| 4 | Ảnh có chữ Trung | Android 11+: chữ trong ảnh được dịch (OCR) |
| 5 | Trang Cài đặt của Taobao | Đúng thuật ngữ bắt buộc |
| 6 | Đăng xuất và Xóa tài khoản | 退出登录 = Đăng xuất, 注销账号 = Xóa tài khoản, không lẫn |
| 7 | Chuyển sang 1688 | Dịch tiếp nếu 1688 được chọn |
| 8 | Sang app không chọn | Lớp phủ biến mất ngay, không xử lý gì |
| 9 | Bật bàn phím | Tạm dừng, bàn phím không bị che |
| 10 | Khóa và mở khóa | Tạm dừng khi khóa, chạy lại khi mở |
| 11 | Tắt Internet sau khi tải mô hình | Vẫn dịch ngoại tuyến |
| 12 | Tắt rồi bật lại chế độ dịch | Lớp phủ mất ngay khi tắt, chạy lại khi bật |
| 13 | Xoay màn hình | Bản dịch đúng chỗ sau khi xoay |
| 14 | Thu hồi quyền Trợ năng | Mọi thứ dừng, trang Cài đặt báo chưa có quyền |
| 15 | Chạy lâu (30 phút trở lên) | RAM ổn định, không rò bộ nhớ |
| 16 | Giá tiền, thông số | Số, ¥, %, dấu âm, đơn vị, mã hàng giữ nguyên |
| 17 | Chạm, vuốt, cuộn, nhấn nút qua lớp phủ | Taobao nhận đầy đủ |
| 18 | Bàn phím và tab Hội thoại của MSTrans | Không hỏng, không bị dịch phủ lên |
| 19 | Màn hình nhạy cảm, màn hình có FLAG_SECURE | Không dịch, không chụp |
| 20 | RAM, CPU, pin, độ trễ | Có số đo thật để báo cáo |

Cũng chưa kiểm: lần chạy CI có ML Kit, Android Lint với mã mới, cài đè thật lên 1.6.0 (13).

## 12. Rủi ro và giới hạn đã biết

- Android 13+ chặn bật Trợ năng cho app cài từ file APK ("cài đặt bị hạn chế"). Người dùng phải vào
  Cài đặt › Ứng dụng › MSTrans › ⋮ › Cho phép cài đặt bị hạn chế (tên mục khác nhau tùy hãng). Phải có
  trong hướng dẫn và trên trang Cài đặt.
- Chất lượng dịch Trung → Việt của ML Kit ở mức cơ bản; từ điển, mẫu câu và Thuật ngữ bù cho phần giao
  diện mua sắm. Câu mô tả dài sẽ kém hơn AI.
- Chưa biết Taobao để lộ bao nhiêu chữ qua Trợ năng (nhiều phần vẽ riêng hoặc là ảnh); phần đó cần OCR,
  tức Android 11+. Android 8 đến 10 chỉ dịch được chữ Trợ năng đọc được.
- Màn hình có FLAG_SECURE (thường là thanh toán) không được dịch: đúng yêu cầu, không phải lỗi.
- Tải mô hình dịch cần mạng tới máy chủ Google (khoảng vài chục MB mỗi ngôn ngữ, cần đo).
- Pin: OCR là phần tốn nhất; chỉ chạy khi nội dung đổi. Cần số đo thật trước khi hứa.
- Khóa ký đã lộ (mục 8).

## 13. Tiến độ ngày 26/11/2026 (máy tính Windows)

Máy: Windows 11, i7-12700H, 32 GB RAM. JDK 21 (Temurin) chạy Gradle, mã vẫn biên dịch cho Java 17. Android SDK
cài vào `%LOCALAPPDATA%\Android\Sdk` từ gói command-line tools chính thức (`commandlinetools-win-13114758`,
SHA-256 `98b565cb…a3505b003` khớp), cùng `platform-tools`, `platforms;android-35`, `build-tools;35.0.0`.

| Commit | Nội dung |
|---|---|
| `c9affc5` | ML Kit `text-recognition-chinese:16.0.1`, `translate:17.0.3`, `kotlinx-coroutines-play-services:1.10.2` (đã kiểm là bản mới nhất trên Google Maven / Maven Central ngày 26/11/2026); `android.useAndroidX=true`; chỉ ARM (`MSTRANS_ABIS` thêm `x86_64` cho emulator); thư viện máy nén trong APK |
| `30928db` | Lõi `core/screen` theo mục 6, 58 test mới (tổng 103 test core đều xanh) |
| `62e012e` | Phần app: dịch vụ, chụp màn hình, ML Kit, lớp phủ, nút nổi, trang Cài đặt › Dịch màn hình, manifest, xml; versionName 1.7.0 |

**Kích thước APK (bản release, đo thật):** 1.6.0 = 2.031.014 byte. Thêm ML Kit với 3 kiến trúc CPU và thư viện máy
không nén = 81.207.479 byte. Chỉ ARM + nén = 27.041.751 byte. Bản 1.7.0 đủ tính năng = 27.127.423 byte. Phần lớn là
`libtranslate_jni.so` và `libmlkit_google_ocr_pipeline.so`. Gói dịch zh/vi tải về sau khi cài (chưa đo dung lượng).

**Bản build cục bộ** (`MSTRANS_BUILD=15`): `vn.mocsinh.mstrans`, versionCode 15, versionName 1.7.0, ký v2, chứng thư
`ff13e1b4…994a6eb9` (trùng 1.6.0). SHA-256 của APK thay đổi theo mỗi lần build. Quyền trong APK: 4 quyền cũ +
`ACCESS_NETWORK_STATE` (ML Kit) + `vn.mocsinh.mstrans.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (AndroidX, quyền nội bộ
chỉ app này có). Không có SYSTEM_ALERT_WINDOW, không có quyền bộ nhớ.

**Lint:** 1 lỗi (`MissingPermission` cũ ở `AndroidAudio.kt`), 13 cảnh báo, đúng như mốc 1.6.0. Đã tắt có ghi lý do:
`UseCompatLoadingForDrawables` (xuất hiện do bật AndroidX, app không dùng AppCompat), `ChromeOsAbiSupport` (chỉ
build cho điện thoại), `UnusedAttribute` trong `screen_translation_service.xml` (`canTakeScreenshot` 11+,
`isAccessibilityTool` 12+, máy cũ tự bỏ qua). Các thuộc tính `android:` của xml và manifest đã đối chiếu với
`android.R$attr` của SDK 35.

**Quyết định thêm khi viết mã (khác hoặc chi tiết hơn mục 5):**
- Từ điển chia hai: `TERMS` (30 thuật ngữ bắt buộc + thuật ngữ mua sắm đã rà) được thay cả trong câu dài trước khi
  dịch; `LABELS` (từ thường như 确定, 删除, 价格) chỉ khớp nguyên dòng, vì trong câu chúng hay mang nghĩa khác.
  Đã rà danh sách "đề xuất bổ sung" của mục 7 và đưa vào; 红包 dịch là "Lì xì".
- Thuật ngữ người dùng đọc được cả hai chiều: `báo giá = 报价` cũng cho 报价 → "báo giá".
- Bản dịch còn sót chữ Hán thì không tin (giữ chữ gốc). Dịch từng khúc thì thêm dấu cách và đổi dấu câu Trung
  (，。：) sang kiểu Việt.
- Từ khóa nhạy cảm chia hai mức: 支付密码, 付款码, 收款码, 安全键盘, 银行卡号, 身份证号, CVV… dừng ở mọi nơi; 密码,
  验证码, 银行卡, 身份证 chỉ dừng khi màn hình có ô nhập chữ (trang Cài đặt của Taobao có dòng "修改密码", không nên
  dừng ở đó).
- Thay vì tính "chữ ký" khi mỗi sự kiện đổi nội dung tới, lần quét tự tính chữ ký (chữ Hán + vị trí); giống lần
  trước thì không vẽ lại. Lần quét chờ màn hình yên nhưng không quá 1,5 giây (banner chạy liên tục không làm hoãn mãi).
- OCR khi chỉ có nội dung đổi (không đổi trang, không cuộn) cách nhau tối thiểu 5 / 2,5 / 1,2 giây theo mức tốc độ.
- Chữ của ô nhập (người dùng đang gõ) không bị phủ. `contentDescription` chỉ dùng khi nút không có chữ, không có con
  và không phải ảnh.

**Chưa kiểm (phải thử trên máy thật):** cả 20 tình huống mục 11; số đo RAM, CPU, pin, độ trễ; cài đè lên 1.6.0 (13);
lần chạy CI có ML Kit; Taobao để lộ bao nhiêu chữ qua Trợ năng; kích thước gói dịch tải về; chữ có vừa khung không.
