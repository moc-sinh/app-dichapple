package vn.mocsinh.mstrans.ui

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A translation coming in as it is written (`Translator.translate` with progress, heard on OkHttp's
 * thread), drawn on the main thread at most every 50 ms: a long text comes a few characters at a time.
 */
class StreamedText(private val draw: (StreamedText) -> Unit) {
    @Volatile var text = ""
        private set

    /** Whole: only the meaning check written after it may still be coming. */
    @Volatile var whole = false
        private set

    /** Nothing more is drawn: the translation is over, or another one took its place. */
    @Volatile var stopped = false

    private val drawing = AtomicBoolean(false)

    /** On OkHttp's thread. */
    fun heard(text: String, whole: Boolean) {
        this.text = text
        if (whole) this.whole = true
        if (!drawing.compareAndSet(false, true)) return
        main.postDelayed({
            // Cleared before it is read: what is heard from now on is drawn the next time.
            drawing.set(false)
            if (!stopped) draw(this)
        }, 50)
    }

    private companion object {
        val main = Handler(Looper.getMainLooper())
    }
}
