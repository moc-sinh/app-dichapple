package vn.mocsinh.mstrans

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.mocsinh.mstrans.core.ChatLine
import vn.mocsinh.mstrans.core.HistoryItem
import vn.mocsinh.mstrans.core.Language
import vn.mocsinh.mstrans.core.LanguageDetector
import vn.mocsinh.mstrans.core.TranslateError
import vn.mocsinh.mstrans.core.Translator
import vn.mocsinh.mstrans.core.target
import vn.mocsinh.mstrans.ui.PillStyle
import vn.mocsinh.mstrans.ui.StreamedText
import vn.mocsinh.mstrans.ui.TextSize
import vn.mocsinh.mstrans.ui.colors
import vn.mocsinh.mstrans.ui.copyText
import vn.mocsinh.mstrans.ui.dp
import vn.mocsinh.mstrans.ui.horizontal
import vn.mocsinh.mstrans.ui.label
import vn.mocsinh.mstrans.ui.leading
import vn.mocsinh.mstrans.ui.matchWidth
import vn.mocsinh.mstrans.ui.pill
import vn.mocsinh.mstrans.ui.progress
import vn.mocsinh.mstrans.ui.selectable
import vn.mocsinh.mstrans.ui.spacer
import vn.mocsinh.mstrans.ui.vertical

/**
 * "Dịch bằng MSTrans" in the menu of selected text (or Chia sẻ), in any app, both ways, as the
 * iPhone's Back Tap shortcut:
 * - a partner's message → read it in Vietnamese, without opening the keyboard;
 * - your own Vietnamese → translated into the partner's language and copied, ready to paste (or
 *   put in place of the selection, where the app allows it).
 */
class ProcessTextActivity : Activity() {
    private val settings get() = MSTransApp.of(this).settings
    private val scope = MainScope()
    private var job: Job? = null
    private lateinit var body: LinearLayout
    private lateinit var actions: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = (intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()
        val canReplace = intent.action == Intent.ACTION_PROCESS_TEXT &&
            !intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

        val c = colors
        body = vertical(12)
        actions = horizontal(8).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        val header = horizontal(10).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@ProcessTextActivity).apply {
                setImageResource(R.drawable.logo)
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            })
            addView(label("MSTrans", TextSize.HEADLINE, c.text, bold = true))
        }
        val content = vertical(14).apply {
            setPadding(dp(20), dp(18), dp(20), dp(14))
            addView(header)
            addView(ScrollView(this@ProcessTextActivity).apply { addView(body) }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ))
            addView(actions, matchWidth())
        }
        // Short answers keep the window short; long ones scroll inside it.
        content.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setContentView(content)
        start(source, canReplace)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun start(source: String, canReplace: Boolean) {
        val apiKey = settings.apiKey
        when {
            source.isEmpty() -> fail("Chưa có chữ nào để dịch. Chọn chữ trong tin rồi thử lại.")
            apiKey == null -> {
                fail(TranslateError.noKey().message ?: "")
                actions.addView(pill("Mở Cài đặt", style = PillStyle.PRIMARY) {
                    startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.Tab.SETTINGS.name))
                    finish()
                })
            }
            LanguageDetector.isVietnamese(source) -> reply(source, apiKey, canReplace)
            else -> read(source, apiKey)
        }
    }

    /** The partner's message, in Vietnamese: shown as it is written, from its first words. */
    private fun read(source: String, apiKey: String) {
        val c = colors
        caption("Tin đối tác → Tiếng Việt")
        quote(source)
        val waiting = progress("Đang dịch sang tiếng Việt…")
        body.addView(waiting)
        setActions(close = "Đóng")
        val shown = label("", TextSize.BODY, c.text)
        val stream = StreamedText {
            val text = it.text.trim()
            if (text.isEmpty()) return@StreamedText
            if (shown.parent == null) {
                body.removeView(waiting)
                body.addView(shown)
            }
            shown.text = text
        }
        job = scope.launch {
            try {
                val cached = settings.cachedIncoming(source)
                val translation = cached ?: run {
                    val context = withContext(Dispatchers.IO) { settings.context() }
                    val result = Translator(apiKey, settings.model, settings)
                        .translate(source, Language.vietnamese, context, progress = stream::heard)
                    save {
                        settings.cacheIncoming(source, result.text)
                        settings.addHistory(HistoryItem(side = ChatLine.Side.PARTNER, source = source, translation = result.text,
                            languageCode = LanguageDetector.language(source)?.code ?: ""))
                    }
                    result.text
                }
                stream.stopped = true
                body.removeView(waiting)
                if (shown.parent == null) body.addView(shown)
                shown.text = translation
                shown.selectable()
                setActions(close = "Xong", primary = "Sao chép bản dịch") {
                    copyText(translation)
                    finish()
                }
                save { settings.remember(ChatLine(ChatLine.Side.PARTNER, source, System.currentTimeMillis())) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                body.removeView(waiting)
                body.removeView(shown)
                fail(e.message ?: "Không dịch được. Thử lại.")
            } finally {
                stream.stopped = true
            }
        }
    }

    /**
     * Your Vietnamese, in the partner's language, copied so it can be pasted into the chat: as soon
     * as the translation is whole, while its meaning check is still being written.
     */
    private fun reply(source: String, apiKey: String, canReplace: Boolean) {
        val c = colors
        caption("Bạn viết")
        quote(source)
        val waiting = progress("Đang dịch…")
        body.addView(waiting)
        setActions(close = "Đóng")
        val checking = progress("Đang kiểm tra nghĩa…")
        job = scope.launch {
            var saved: HistoryItem? = null
            var stream: StreamedText? = null
            try {
                val (context, target) = withContext(Dispatchers.Default) {
                    val context = settings.context()
                    context to settings.target(settings.targetCode, context)
                }
                (waiting.getChildAt(1) as? android.widget.TextView)?.text = "Đang dịch sang ${target.name}…"
                val checkMeaning = settings.checkMeaning
                fun whole(text: String) {
                    if (saved != null) return
                    saved = replied(source, text, target, canReplace, waiting)
                    if (checkMeaning) body.addView(checking)
                }
                val heard = StreamedText { if (it.whole) whole(it.text) }
                stream = heard
                val result = Translator(apiKey, settings.model, settings).translate(source, target, context, checkMeaning, heard::heard)
                heard.stopped = true
                // Not whole before the end (a model without the JSON format): now.
                whole(result.text)
                body.removeView(checking)
                result.meaning?.let { meaning ->
                    caption("Nghĩa khi dịch ngược")
                    body.addView(label(meaning, TextSize.SUBHEADLINE, c.secondary).selectable())
                    saved?.let { item -> save { settings.updateHistory(item.copy(meaning = meaning)) } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                body.removeView(waiting)
                fail(e.message ?: "Không dịch được. Thử lại.")
            } finally {
                stream?.stopped = true
            }
        }
    }

    /**
     * The translation is whole: copied at once, ready to paste (the keyboard does not offer it back as
     * the partner's), and saved, so closing the window before its meaning check comes loses nothing.
     */
    private fun replied(source: String, text: String, target: Language, canReplace: Boolean, waiting: View): HistoryItem {
        copyText(text)
        body.removeView(waiting)
        caption("Đã chép bản ${target.name}, dán vào ô chat")
        body.addView(label(text, TextSize.BODY, colors.text).selectable())
        if (canReplace) {
            setActions(close = "Xong", primary = "Thay chữ đã chọn") {
                setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text))
                finish()
            }
        } else {
            setActions(close = "Xong")
        }
        val item = HistoryItem(side = ChatLine.Side.ME, source = source, translation = text, languageCode = target.code)
        save {
            settings.remember(ChatLine(ChatLine.Side.ME, text, System.currentTimeMillis()))
            settings.addHistory(item)
        }
        return item
    }

    /** Off the main thread, in order, and on after the window closes. */
    private fun save(write: () -> Unit) {
        MSTransApp.of(this).scope.launch(writes) { write() }
    }

    private companion object {
        val writes = Dispatchers.IO.limitedParallelism(1)
    }

    private fun caption(text: String) {
        body.addView(label(text, TextSize.FOOTNOTE, colors.secondary, medium = true))
    }

    private fun quote(text: String) {
        body.addView(label(text, TextSize.SUBHEADLINE, colors.secondary).apply {
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
    }

    private fun fail(message: String) {
        body.addView(label(message, TextSize.SUBHEADLINE, colors.red).leading(R.drawable.ic_error, colors.red, 18, 8))
        setActions(close = "Đóng")
    }

    private fun setActions(close: String, primary: String? = null, action: (() -> Unit)? = null) {
        actions.removeAllViews()
        actions.addView(spacer())
        actions.addView(pill(close) { finish() })
        if (primary != null && action != null) actions.addView(pill(primary, style = PillStyle.PRIMARY) { action() })
        actions.visibility = View.VISIBLE
    }
}
