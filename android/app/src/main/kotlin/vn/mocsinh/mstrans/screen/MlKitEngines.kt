package vn.mocsinh.mstrans.screen

import android.graphics.Bitmap
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import vn.mocsinh.mstrans.core.screen.Box
import vn.mocsinh.mstrans.core.screen.ScreenText
import vn.mocsinh.mstrans.core.screen.TextEngine

/**
 * ML Kit on the phone: Chinese text in pictures (its model is inside the APK) and Chinese →
 * Vietnamese offline (the language pack downloads once). Nothing is sent anywhere to translate.
 */
class MlKitEngines {
    private var translator: Translator? = null
    private var recognizer: TextRecognizer? = null
    @Volatile var modelReady = false
        private set

    @Synchronized
    private fun translator(): Translator = translator ?: Translation.getClient(OPTIONS).also { translator = it }

    @Synchronized
    private fun recognizer(): TextRecognizer =
        recognizer ?: TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()).also { recognizer = it }

    /** Null until the language pack is on the phone. */
    val engine = TextEngine { text -> if (!modelReady) null else translator().translate(text).await() }

    suspend fun checkModel(): Boolean {
        modelReady = downloaded()
        ScreenState.modelReady = modelReady
        return modelReady
    }

    suspend fun download(wifiOnly: Boolean) {
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        translator().downloadModelIfNeeded(conditions).await()
        checkModel()
    }

    /** Lines of Chinese in `bitmap`, placed on the screen (`left`, `top` is the bitmap's corner). */
    suspend fun read(bitmap: Bitmap, left: Int, top: Int): List<ScreenText> {
        val text = recognizer().process(InputImage.fromBitmap(bitmap, 0)).await()
        return text.textBlocks.flatMap { block -> block.lines }.mapNotNull { line ->
            val r = line.boundingBox ?: return@mapNotNull null
            ScreenText(line.text, Box(r.left, r.top, r.right, r.bottom).offset(left, top), fromImage = true)
        }
    }

    @Synchronized
    fun close() {
        translator?.close()
        translator = null
        recognizer?.close()
        recognizer = null
    }

    companion object {
        val OPTIONS: TranslatorOptions = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.CHINESE)
            .setTargetLanguage(TranslateLanguage.VIETNAMESE)
            .build()

        private val LANGUAGES = listOf(TranslateLanguage.CHINESE, TranslateLanguage.VIETNAMESE)

        suspend fun downloaded(): Boolean {
            val models = RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).await()
            return LANGUAGES.all { language -> models.any { it.language == language } }
        }

        /** Takes the Chinese and Vietnamese packs off the phone (English, used in between, stays). */
        suspend fun delete() {
            val manager = RemoteModelManager.getInstance()
            for (language in LANGUAGES) manager.deleteDownloadedModel(TranslateRemoteModel.Builder(language).build()).await()
            ScreenState.modelReady = false
        }
    }
}
