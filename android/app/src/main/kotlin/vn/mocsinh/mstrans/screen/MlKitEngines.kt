package vn.mocsinh.mstrans.screen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import vn.mocsinh.mstrans.core.screen.Box
import vn.mocsinh.mstrans.core.screen.PictureText
import vn.mocsinh.mstrans.core.screen.ScreenBlocks
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

    /**
     * The text in `bitmap`, placed on the screen (`left`, `top` is the bitmap's corner): a wrapped
     * sentence is one text, other lines one each (`ScreenBlocks`). `known`: a piece of a line known
     * as a whole (a number in it is then part of a known sentence, see `PictureText.pieces`).
     */
    suspend fun read(bitmap: Bitmap, left: Int, top: Int, known: (String) -> Boolean = { true }): List<ScreenText> {
        // Light letters on a dark page (dark mode) are read worse: the reader is given them dark on light.
        val dark = dark(bitmap)
        val source = if (dark) inverted(bitmap) else bitmap
        val text = try {
            recognizer().process(InputImage.fromBitmap(source, 0)).await()
        } finally {
            if (source !== bitmap) source.recycle()
        }
        return text.textBlocks.flatMap { block ->
            // Each line as the things written on it: a price and, further along, how many bought are two.
            val lines = block.lines.mapNotNull { line ->
                // What the reader is not sure of, or reads on a slant (the writing on the goods in a photograph), is left alone.
                if (!PictureText.worth(line.text, line.confidence, line.angle, known)) return@mapNotNull null
                val pieces = PictureText.pieces(line.elements.map { word -> letters(word) }, ink = { box -> ink(bitmap, box) }, whole = known)
                if (pieces.isNotEmpty()) pieces.map { (piece, box) -> piece to box.offset(left, top) }
                else line.boundingBox?.let { listOf(line.text to Box(it.left, it.top, it.right, it.bottom).offset(left, top)) }
            }
            // A sentence wrapped over lines is one text; a line of several things is never part of one,
            // but for its first thing: a variant's name wrapped over two lines with its price beside
            // the first is still one name (Taobao, 27/11/2026: "…【网红升级节" / "能款】" was translated alone).
            val whole = if (lines.all { it.size == 1 }) ScreenBlocks.texts(lines.map { it[0] }) else {
                val names = ScreenBlocks.texts(lines.map { it[0] })
                if (lines.size > 1 && names.size == 1) names + lines.flatMap { it.drop(1) }.flatMap { ScreenBlocks.texts(listOf(it)) }
                else lines.flatten().flatMap { ScreenBlocks.texts(listOf(it)) }
            }
            // An icon taken for a letter (a cart for 口) is not text either.
            whole.filter { PictureText.worth(it.text, null, null, known) }
        }
    }

    /**
     * The colour of the letter in `box` (picture pixels): of the points in it, the one furthest from
     * the colour around the box. Null when nothing stands out.
     */
    private fun ink(bitmap: Bitmap, box: Box): Int? {
        val l = box.left.coerceIn(0, bitmap.width - 1)
        val r = box.right.coerceIn(l + 1, bitmap.width)
        val t = box.top.coerceIn(0, bitmap.height - 1)
        val b = box.bottom.coerceIn(t + 1, bitmap.height)
        val around = intArrayOf(bitmap.getPixel(l, t), bitmap.getPixel(r - 1, t), bitmap.getPixel(l, b - 1), bitmap.getPixel(r - 1, b - 1))
        val br = around.sumOf { it shr 16 and 0xFF } / 4
        val bg = around.sumOf { it shr 8 and 0xFF } / 4
        val bb = around.sumOf { it and 0xFF } / 4
        var best = 0
        var colour = 0
        val stepX = maxOf(1, (r - l) / 12)
        val stepY = maxOf(1, (b - t) / 12)
        var y = t
        while (y < b) {
            var x = l
            while (x < r) {
                val c = bitmap.getPixel(x, y)
                val d = maxOf(kotlin.math.abs((c shr 16 and 0xFF) - br), kotlin.math.abs((c shr 8 and 0xFF) - bg), kotlin.math.abs((c and 0xFF) - bb))
                if (d > best) {
                    best = d
                    colour = c
                }
                x += stepX
            }
            y += stepY
        }
        return if (best >= 60) colour else null
    }

    /** Mostly dark: the average brightness of a grid of points is low. */
    private fun dark(bitmap: Bitmap): Boolean {
        var sum = 0L
        var n = 0
        for (row in 0 until 48) for (column in 0 until 24) {
            val c = bitmap.getPixel((column + 0.5f).times(bitmap.width / 24f).toInt().coerceAtMost(bitmap.width - 1), (row + 0.5f).times(bitmap.height / 48f).toInt().coerceAtMost(bitmap.height - 1))
            sum += (299 * ((c shr 16) and 0xFF) + 587 * ((c shr 8) and 0xFF) + 114 * (c and 0xFF)) / 1000
            n++
        }
        return sum / n < DARK
    }

    /** The picture in negative (a copy). */
    private fun inverted(bitmap: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val negative = ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
        Canvas(out).drawBitmap(bitmap, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(negative) })
        return out
    }

    /** The letters of a word with their places; the word whole where the reader does not place its letters. */
    private fun letters(word: Text.Element): List<Pair<String, Box>> {
        val letters = word.symbols.mapNotNull { letter -> letter.boundingBox?.let { letter.text to Box(it.left, it.top, it.right, it.bottom) } }
        if (letters.isNotEmpty() && letters.size == word.symbols.size) return letters
        return listOfNotNull(word.boundingBox?.let { word.text to Box(it.left, it.top, it.right, it.bottom) })
    }

    /**
     * The translator and the reader of pictures load on their first use (most of a second each):
     * done once, before a screen waits for them.
     */
    suspend fun warmUp() {
        if (modelReady) runCatching { translator().translate("你好").await() }
        runCatching {
            val blank = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
            try {
                recognizer().process(InputImage.fromBitmap(blank, 0)).await()
            } finally {
                blank.recycle()
            }
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
        private const val DARK = 100L

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
