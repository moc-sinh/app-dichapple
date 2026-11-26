package vn.mocsinh.mstrans.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import vn.mocsinh.mstrans.MainActivity
import vn.mocsinh.mstrans.R
import vn.mocsinh.mstrans.Screen
import vn.mocsinh.mstrans.core.screen.ScreenSettings
import vn.mocsinh.mstrans.core.screen.Speed
import vn.mocsinh.mstrans.screen.MlKitEngines
import vn.mocsinh.mstrans.screen.ScreenState

/** Tab Dịch Web (translating the screen of shopping apps): the switch, the permission, the language pack, the apps, the options. */
class ScreenTranslationPage(private val activity: MainActivity) : Screen {
    private val settings = ScreenState.settings(activity)
    private val c = activity.colors
    private val scope = MainScope()
    private val body = activity.vertical().apply { setPadding(activity.dp(16), activity.dp(4), activity.dp(16), activity.dp(32)) }
    private val scroll = ScrollView(activity).apply { addView(body) }
    private var downloading = false
    private var deleting = false
    private var modelError: String? = null

    override val view: View = activity.vertical().apply {
        addView(activity.topBar("Dịch Web"), matchWidth())
        addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** The tab shows, or the app comes back (from Android's settings, the permission given): all read again. */
    override fun shown() {
        ScreenState.listener = { build() }
        build()
        checkModel()
    }

    override fun hidden() {
        ScreenState.listener = null
    }

    override fun destroyed() = scope.cancel()

    private fun changed() {
        ScreenState.service?.refresh()
        build()
    }

    private fun build() {
        val y = scroll.scrollY
        scroll.post { scroll.scrollTo(0, y) }
        body.removeAllViews()
        body.addView(mainSection())
        body.addView(permissionSection())
        body.addView(modelSection())
        body.addView(appsSection())
        body.addView(languageSection())
        body.addView(modeSection())
        body.addView(optionsSection())
        body.addView(statusSection())
        body.addView(guideSection())
    }

    // Sections

    /**
     * One switch: on is the accessibility permission given (Android lets no app give it to itself:
     * its own page opens, one switch there); off is MSTrans giving it back, so no other app sees an
     * accessibility service running.
     */
    private fun mainSection(): View {
        val on = ScreenState.permitted(activity) && ScreenState.service != null
        val footer = if (on) {
            "Đang bật: mở Taobao (hay app đã chọn) là tự dịch. Tắt ở đây hoặc chạm ô “Dịch Web” trong bảng kéo xuống từ đỉnh màn hình: " +
                "MSTrans tắt hẳn quyền Trợ năng, app ngân hàng và các app khác không còn thấy."
        } else {
            "Bật: Android mở danh sách Trợ năng, chạm “MSTrans · Dịch Web”, bật công tắc rồi quay lại. Lần sau bật nhanh bằng ô “Dịch Web” " +
                "trong bảng kéo xuống từ đỉnh màn hình."
        }
        val rows = ArrayList<View>()
        rows.add(activity.switchRow("Dịch Web", on) { want ->
            if (want) {
                settings.enabled = true
                settings.paused = false
                runCatching { activity.startActivity(ScreenState.permissionPage(activity)) }.onFailure { activity.toast("Không mở được cài đặt Trợ năng") }
            } else {
                ScreenState.service?.turnOff()
                activity.toast("Đã tắt hẳn quyền Trợ năng của MSTrans")
            }
            build()
        })
        rows.add(activity.switchRow("Tự tắt khi rời app mua sắm", settings.autoOff) {
            settings.autoOff = it
            build()
        })
        if (Build.VERSION.SDK_INT >= 33) {
            rows.add(activity.row("Thêm ô “Dịch Web” vào bảng kéo xuống", null, c.green) { addTile() })
        }
        return activity.section(null, footer, rows)
    }

    /** What else is needed, while it is off: Android 13+ blocks the switch of an app installed from a file. */
    private fun permissionSection(): View {
        val rows = ArrayList<View>()
        rows.add(step(null, "Tự tắt khi rời app mua sắm",
            if (settings.autoOff) "Đang bật: rời app đã chọn sang app khác (về màn hình chính thì không tính) là MSTrans tự tắt hẳn quyền. " +
                "Quay lại mua hàng thì bật lại bằng ô “Dịch Web”." else "Đang tắt: quyền Trợ năng còn bật cho tới khi bạn tắt."))
        if (!ScreenState.permitted(activity) && Build.VERSION.SDK_INT >= 33) {
            rows.add(step(null, "Nếu Android báo “Cài đặt bị hạn chế”",
                "MSTrans được cài từ file APK nên Android 13 trở lên chặn bật lần đầu. Mở Thông tin ứng dụng › chạm ⋮ (góc trên) › " +
                    "Cho phép cài đặt bị hạn chế (tên mục khác nhau tùy hãng), rồi bật lại. Chỉ cần làm một lần."))
            rows.add(activity.row("Mở Thông tin ứng dụng", null, c.green) { Setup.openAppSettings(activity) })
        }
        rows.add(step(null, "MSTrans đọc gì",
            "Chỉ chữ của các app bạn chọn ở dưới, khi đang bật. Không bấm hay gõ thay bạn, không đọc ô mật khẩu, không dịch màn hình thanh toán."))
        return activity.section("Quyền", null, rows)
    }

    /** Android 13+: Android's own question "Add tile?", one tap. */
    private fun addTile() {
        if (Build.VERSION.SDK_INT < 33) return
        val bar = activity.getSystemService(android.app.StatusBarManager::class.java) ?: return
        runCatching {
            bar.requestAddTileService(
                android.content.ComponentName(activity, vn.mocsinh.mstrans.screen.ScreenTileService::class.java),
                activity.getString(R.string.screen_translation_tile),
                android.graphics.drawable.Icon.createWithResource(activity, R.drawable.ic_tab_translate),
                activity.mainExecutor,
            ) { result ->
                activity.toast(
                    when (result) {
                        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Đã thêm ô “Dịch Web”"
                        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Ô “Dịch Web” đã có sẵn"
                        else -> "Chưa thêm ô"
                    },
                )
            }
        }.onFailure { activity.toast("Kéo bảng xuống › chạm ✎ (sửa) › kéo ô “Dịch Web” lên") }
    }

    private fun modelSection(): View {
        val ready = ScreenState.modelReady
        val state = when {
            downloading -> "Đang tải…"
            deleting -> "Đang xóa…"
            ready == true -> "Đã tải"
            ready == false -> "Chưa tải"
            else -> "Đang kiểm tra…"
        }
        val rows = ArrayList<View>()
        rows.add(activity.row("Gói dịch Trung → Việt", state, action = null))
        if (ready != true) {
            rows.add(activity.row(if (downloading) "Đang tải gói dịch…" else "Tải gói dịch", null, if (downloading) c.secondary else c.green,
                action = if (downloading || deleting) null else ({ download() })))
        }
        rows.add(activity.switchRow("Chỉ tải qua Wi-Fi", settings.wifiOnly) { settings.wifiOnly = it })
        if (ready == true) {
            rows.add(activity.row("Xóa gói dịch", null, c.red, action = if (deleting) null else ({
                activity.confirm("Xóa gói dịch?", "Chế độ Ngoại tuyến sẽ chỉ dịch được các từ có sẵn trong từ điển cho tới khi tải lại.", "Xóa") { delete() }
            })))
        }
        modelError?.let { rows.add(activity.label(it, TextSize.FOOTNOTE, c.red).apply { setPadding(activity.dp(16), activity.dp(10), activity.dp(16), activity.dp(10)) }) }
        return activity.section("Gói dịch ngoại tuyến",
            "Tải một lần (vài chục MB, qua máy chủ Google), sau đó dịch ngay trên điện thoại: không cần API key, không tốn phí, chạy cả khi mất mạng. " +
                "Đọc chữ trong ảnh không cần tải gì thêm.", rows)
    }

    private fun appsSection(): View {
        val chosen = settings.apps
        val pm = activity.packageManager
        fun installed(pkg: String) = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
        val rows = ArrayList<View>()
        val suggested = ScreenSettings.SUGGESTED.map { it.packageName }.toSet()
        for (app in ScreenSettings.SUGGESTED) {
            val title = app.label + if (installed(app.packageName)) "" else " (chưa cài)"
            rows.add(appRow(title, app.packageName, app.packageName in chosen))
        }
        for (pkg in chosen.filter { it !in suggested }.sorted()) {
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            rows.add(appRow(label, pkg, true))
        }
        rows.add(activity.row("Thêm ứng dụng khác", null, c.green, R.drawable.ic_add) { addApp() })
        return activity.section("Ứng dụng được dịch",
            "Chỉ các app bật ở đây được đọc chữ. App khác, kể cả MSTrans, không bao giờ bị đọc.", rows)
    }

    private fun appRow(title: String, pkg: String, on: Boolean): View = activity.switchRow(title, on) { checked ->
        settings.apps = if (checked) settings.apps + pkg else settings.apps - pkg
        changed()
    }

    private fun languageSection(): View = activity.section("Ngôn ngữ", "Cố định trong bản này.", listOf(
        activity.row("Dịch từ", "Tiếng Trung (giản thể, phồn thể)", action = null),
        activity.row("Sang", "Tiếng Việt", action = null),
    ))

    private fun modeSection(): View {
        val hasKey = activity.settings.apiKey != null
        val footer = if (settings.mode == ScreenSettings.Mode.AI) {
            "AI nâng cao dịch sát nghĩa nhất, theo đúng ngành hàng đang xem (gửi kèm chữ khác trên cùng màn hình để AI hiểu mặt hàng). Dùng key " +
                "OpenAI ở tab Cài đặt; chỉ gửi chữ của các dòng từ điển chưa có, không gửi ảnh; bản ngoại tuyến hiện trước, bản AI thay vào sau. " +
                "Tối đa ${settings.aiLimit} lần gọi mỗi 10 phút, mỗi lần tối đa 40 dòng: Nhanh (GPT-6 Luna) ước tính dưới 0,001 USD một lần, " +
                "Chuẩn nhất (GPT-6.1 Sol) khoảng 0,01 USD một lần. Vượt giới hạn hoặc mất mạng thì tự dịch ngoại tuyến." +
                if (hasKey) "" else " Chưa có key OpenAI: đang dịch ngoại tuyến."
        } else {
            "Ngoại tuyến: miễn phí, không gửi chữ nào ra mạng. Thuật ngữ ở tab Thuật ngữ được dùng trước tiên, rồi từ điển mua sắm và ngành hàng " +
                "(tên phân loại, màu, chất liệu, thông số dịch đúng từng từ). Câu mô tả dài do bộ dịch trên máy làm, kém hơn AI nâng cao."
        }
        val limits = listOf(10, 30, 60)
        val rows = ArrayList<View>()
        rows.add(padded(Segmented(activity, ScreenSettings.Mode.entries.map { it.label }, settings.mode.ordinal) {
            settings.mode = ScreenSettings.Mode.entries[it]
            changed()
        }))
        if (settings.mode == ScreenSettings.Mode.AI) {
            rows.add(padded(Segmented(activity, listOf("Nhanh", "Chuẩn nhất"), if (settings.aiBest) 1 else 0) {
                settings.aiBest = it == 1
                changed()
            }))
            rows.add(activity.row("Giới hạn AI", "${settings.aiLimit} lần / 10 phút") {
                activity.choose("Giới hạn AI mỗi 10 phút", limits.map { "$it lần" }, limits.indexOf(settings.aiLimit).coerceAtLeast(0)) {
                    settings.aiLimit = limits[it]
                    changed()
                }
            })
        }
        return activity.section("Cách dịch", footer, rows)
    }

    private fun optionsSection(): View = activity.section("Tùy chọn",
        "Tiết kiệm pin đọc màn hình thưa hơn; Nhanh hiện bản dịch sớm hơn nhưng tốn pin hơn. Đọc chữ trong ảnh cần Android 11 trở lên " +
            "(máy này: Android ${Build.VERSION.RELEASE}). Nút nổi: chạm để xem chữ gốc, giữ để tạm dừng, kéo lên xuống để dời chỗ.",
        listOf(
            padded(Segmented(activity, Speed.entries.map { it.label }, settings.speed.ordinal) {
                settings.speed = Speed.entries[it]
                ScreenState.service?.refresh()
            }),
            activity.switchRow("Đọc chữ trong ảnh", settings.readImages) {
                settings.readImages = it
                changed()
            },
            activity.switchRow("Nút nổi", settings.floatingButton) {
                settings.floatingButton = it
                changed()
            },
            activity.switchRow("Tạm dừng", settings.paused) {
                settings.paused = it
                changed()
            },
        ))

    private fun statusSection(): View {
        val rows = ArrayList<View>()
        val service = ScreenState.service != null
        rows.add(activity.row("Trạng thái", if (!service) "Dịch vụ chưa chạy" else ScreenState.status.ifEmpty { "Sẵn sàng" }, action = null))
        ScreenState.lastScan?.let { rows.add(activity.row("Lần đọc gần nhất", it, action = null)) }
        ScreenState.lastError?.let {
            rows.add(activity.label("${Dates.time(ScreenState.lastErrorAt)} · $it", TextSize.FOOTNOTE, c.red).apply {
                setPadding(activity.dp(16), activity.dp(10), activity.dp(16), activity.dp(10))
            })
        }
        return activity.section("Tình trạng", null, rows)
    }

    private fun guideSection(): View = activity.section("Hướng dẫn",
        "Bản dịch che đúng chỗ chữ Trung; chạm vào đó vẫn là chạm vào Taobao. Khi cuộn, bản dịch tạm ẩn và hiện lại lúc dừng. Dịch tạm dừng khi " +
            "bàn phím mở, khi có ô mật khẩu hay màn hình thanh toán, khi khóa máy, và không bao giờ dịch màn hình mà app bảo vệ (thường là trang " +
            "thanh toán). Nhận diện màn hình nhạy cảm bằng từ khóa chỉ là lớp bảo vệ thêm, không tuyệt đối. Android 8 đến 10 chỉ dịch chữ, " +
            "không dịch chữ trong ảnh.",
        listOf(
            step(null, "1. Bật “Dịch Web”", "Ở đầu trang này."),
            step(null, "2. Cấp quyền Trợ năng", "Mục Quyền ở trên. Android 13 trở lên có thể phải cho phép cài đặt bị hạn chế trước."),
            step(null, "3. Tải gói dịch", "Một lần, nên dùng Wi-Fi."),
            step(null, "4. Mở Taobao", "Dùng như bình thường. Muốn sửa cách dịch một từ: thêm ở tab Thuật ngữ, ví dụ 包邮 = Miễn phí ship."),
        ))

    // Rows

    private fun step(done: Boolean?, title: String, detail: String): View = activity.horizontal(12).apply {
        gravity = Gravity.TOP
        setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(12))
        val iconId = when (done) {
            true -> R.drawable.ic_check_circle
            null -> R.drawable.ic_info
            false -> R.drawable.ic_circle
        }
        addView(ImageView(activity).apply {
            setImageDrawable(activity.icon(iconId, if (done == true) c.green else c.secondary))
            layoutParams = LinearLayout.LayoutParams(activity.dp(22), activity.dp(22))
        })
        addView(activity.vertical(2).apply {
            addView(activity.label(title, TextSize.BODY))
            addView(activity.label(detail, TextSize.FOOTNOTE, c.secondary))
        }, weighted())
    }

    private fun padded(view: View): View = LinearLayout(activity).apply {
        setPadding(activity.dp(16), activity.dp(10), activity.dp(16), activity.dp(10))
        addView(view, matchWidth())
    }

    // Actions

    private fun checkModel() {
        scope.launch {
            ScreenState.modelReady = runCatching { MlKitEngines.downloaded() }.getOrNull()
            build()
        }
    }

    private fun download() {
        downloading = true
        modelError = null
        build()
        scope.launch {
            val engines = MlKitEngines()
            try {
                engines.download(settings.wifiOnly)
                activity.toast("Đã tải gói dịch")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                modelError = if (settings.wifiOnly) "Chưa tải được. Đang bật “Chỉ tải qua Wi-Fi”: kết nối Wi-Fi rồi thử lại." else
                    "Chưa tải được (${e.message ?: e.javaClass.simpleName}). Kiểm tra mạng rồi thử lại."
            } finally {
                engines.close()
                downloading = false
                ScreenState.service?.modelChanged()
                checkModel()
            }
        }
    }

    private fun delete() {
        deleting = true
        build()
        scope.launch {
            try {
                MlKitEngines.delete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                modelError = "Chưa xóa được (${e.message ?: e.javaClass.simpleName})."
            } finally {
                deleting = false
                ScreenState.service?.modelChanged()
                checkModel()
            }
        }
    }

    /** Any app with an icon on the home screen. */
    private fun addApp() {
        val pm = activity.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val chosen = settings.apps
        val apps = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != activity.packageName && it.first !in chosen }
            .sortedBy { it.second.lowercase() }
        if (apps.isEmpty()) {
            activity.toast("Không còn app nào để thêm")
            return
        }
        activity.choose("Thêm ứng dụng", apps.map { it.second }, -1) {
            settings.apps = settings.apps + apps[it].first
            changed()
        }
    }
}
