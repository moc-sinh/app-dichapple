package vn.mocsinh.mstrans

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import kotlinx.coroutines.MainScope
import vn.mocsinh.mstrans.audio.AndroidAudio
import vn.mocsinh.mstrans.audio.AndroidPhoneVoice
import vn.mocsinh.mstrans.core.KeyValueStore
import vn.mocsinh.mstrans.core.LanguageDetector
import vn.mocsinh.mstrans.core.Settings
import vn.mocsinh.mstrans.core.conversation.ConversationModel
import vn.mocsinh.mstrans.core.conversation.ConversationServices
import vn.mocsinh.mstrans.core.conversation.MeetingStore
import java.io.File

/** Settings on the phone: one SharedPreferences file, read by the app and the keyboard (same process). */
class PreferenceStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

/** What the app, the keyboard and the Dịch bằng MSTrans window share. */
class MSTransApp : Application() {
    lateinit var store: PreferenceStore
        private set
    lateinit var settings: Settings
        private set
    /** Long-lived, outside any screen: a meeting goes on while the phone turns or the theme changes. */
    val scope = MainScope()
    val meetings: MeetingStore by lazy { MeetingStore(File(filesDir, "Meetings")) }
    val phoneVoice: AndroidPhoneVoice by lazy { AndroidPhoneVoice(this) }
    val conversation: ConversationModel by lazy {
        ConversationModel(settings, store, AndroidAudio(this), meetings, scope, ConversationServices.live(settings, phoneVoice))
    }

    override fun onCreate() {
        super.onCreate()
        store = PreferenceStore(getSharedPreferences("mstrans", Context.MODE_PRIVATE))
        settings = Settings(store)
        useSystemLanguageDetection()
    }

    /**
     * Latin-script messages the word lists cannot place (Portuguese, Italian…) are asked of Android's
     * own language detector, where the phone has one (Android 10+).
     */
    private fun useSystemLanguageDetection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val manager = getSystemService(TextClassificationManager::class.java) ?: return
        LanguageDetector.latinGuess = guess@{ text ->
            val classifier = runCatching { manager.textClassifier }.getOrNull() ?: return@guess null
            val result = runCatching { classifier.detectLanguage(TextLanguage.Request.Builder(text).build()) }.getOrNull()
                ?: return@guess null
            if (result.localeHypothesisCount == 0) return@guess null
            val locale = result.getLocale(0)
            if (result.getConfidenceScore(locale) < 0.6f) return@guess null
            locale.toLanguageTag().substringBefore('-').ifEmpty { null }
        }
    }

    companion object {
        fun of(context: Context): MSTransApp = context.applicationContext as MSTransApp
    }
}
