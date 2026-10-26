package vn.mocsinh.mstrans.core.conversation

/**
 * Cuts the microphone's sound into utterances: from just before the first syllable to the pause
 * that ends the sentence. Each utterance is transcribed and translated on its own, so a meeting
 * is translated sentence by sentence, whoever speaks.
 *
 * Speech is sound well above the room's own noise, and a set pause ends a turn (0.4–1.4 s). The
 * noise level is the quiet end of the last few seconds, so it follows a noisy room.
 */
class SpeechSegmenter(
    /** Quiet this long ends an utterance. */
    var silence: Double = 0.7,
    /** Longer speech is cut at its quietest moment, so a long talk is translated as it goes. */
    var maxLength: Double = 15.0,
) {
    class Utterance(val samples: ShortArray, /** Seconds since listening began, at the first sample. */ val start: Double) {
        val duration: Double get() = samples.size.toDouble() / PCM.RATE
    }

    /** Someone is speaking now. */
    var speaking = false
        private set
    /**
     * Sound some way above the room's noise, if not as loud as an utterance must begin: a far or quiet
     * voice. Dịch liền sends it on (the echo cancelling it needs makes far voices weaker, and one heard
     * only this loud was never sent, nor translated).
     */
    var audible = false
        private set
    private var audibleFrames = 0
    /** Frames it stays audible after the last loud ones: between syllables too. */
    private var audibleHold = 0
    /** The microphone is not listened to while the phone speaks a translation aloud. */
    var muted = false
        private set

    companion object {
        const val FRAME = PCM.RATE / 50
        private const val FRAME_SECONDS = 0.02
        private const val PREROLL_FRAMES = 15
        private const val TAIL_FRAMES = 10
        private const val MINIMUM_SPEECH = 0.3
        private const val MINIMUM_SPEECH_DB = -52.0
        private const val HISTORY_FRAMES = 250

        /**
         * Where long speech is split: after the quietest 20 ms of its last six seconds (a breath
         * between sentences, usually), never in its first half.
         */
        fun split(samples: ShortArray, count: Int = samples.size): Int {
            val frames = count / FRAME
            val earliest = maxOf(frames / 2, frames - 300)
            var quietest = frames
            var quietestDb = Double.POSITIVE_INFINITY
            for (index in earliest until frames) {
                val start = index * FRAME
                val db = PCM.dbfs(samples, start, start + FRAME)
                if (db < quietestDb) {
                    quietestDb = db
                    quietest = index
                }
            }
            return minOf(count, (quietest + 1) * FRAME)
        }
    }

    private val history = DoubleArray(HISTORY_FRAMES)
    private var historyCount = 0
    private var historyNext = 0
    private var floorDb = -65.0
    private var framesSinceFloor = 0
    private var confirm = 0
    private var quietFrames = 0
    private val current = SampleBuffer()
    private var currentStart = 0
    private var lead = 0
    private val preroll = SampleBuffer()
    private val pending = SampleBuffer()
    private var position = 0

    /** Takes the next samples (24 kHz); returns the utterances they ended. */
    fun push(samples: ShortArray): List<Utterance> {
        pending.append(samples)
        val ended = ArrayList<Utterance>()
        var offset = 0
        while (pending.size - offset >= FRAME) {
            process(pending.array, offset)?.let { ended.add(it) }
            offset += FRAME
        }
        pending.removeFirst(offset)
        return ended
    }

    /** Listening stops: what was being said goes out as it is. */
    fun flush(): Utterance? {
        try {
            if (!speaking) return null
            return finish()
        } finally {
            speaking = false
            current.clear()
            preroll.clear()
            pending.clear()
            confirm = 0
            quietFrames = 0
        }
    }

    /** The phone starts speaking: what was being said goes out, and nothing is heard until `unmute`. */
    fun mute(): Utterance? {
        muted = true
        val utterance = if (speaking) finish() else null
        speaking = false
        current.clear()
        preroll.clear()
        confirm = 0
        return utterance
    }

    fun unmute() {
        muted = false
        preroll.clear()
        confirm = 0
        quietFrames = 0
    }

    private fun process(frame: ShortArray, from: Int): Utterance? {
        position += FRAME
        if (muted) return null
        val db = PCM.dbfs(frame, from, from + FRAME)
        remember(db)
        // Three frames in a row (a click is one), and a moment after.
        audibleFrames = if (db >= maxOf(MINIMUM_SPEECH_DB - 6, floorDb + 6)) audibleFrames + 1 else 0
        audibleHold = if (audibleFrames >= 3) 15 else maxOf(0, audibleHold - 1)
        audible = audibleHold > 0
        val onset = maxOf(MINIMUM_SPEECH_DB, floorDb + 10)
        val release = maxOf(MINIMUM_SPEECH_DB - 6, floorDb + 5)

        if (!speaking) {
            preroll.append(frame, from, from + FRAME)
            if (preroll.size > PREROLL_FRAMES * FRAME) preroll.removeFirst(preroll.size - PREROLL_FRAMES * FRAME)
            // Two loud frames in a row: a click is one.
            confirm = if (db >= onset) confirm + 1 else 0
            if (confirm >= 2) {
                speaking = true
                confirm = 0
                quietFrames = 0
                current.clear()
                current.append(preroll)
                currentStart = position - preroll.size
                lead = preroll.size
                preroll.clear()
            }
            return null
        }

        current.append(frame, from, from + FRAME)
        quietFrames = if (db < release) quietFrames + 1 else 0
        val said = (current.size - lead - quietFrames * FRAME).toDouble() / PCM.RATE
        if (quietFrames * FRAME_SECONDS >= pause(said)) return finish()
        if (current.size >= maxLength * PCM.RATE) return cut()
        return null
    }

    /**
     * The pause that ends an utterance `said` seconds long: the one set, for a sentence of up to three
     * seconds; shorter as it goes on, down to 0.35 s at eight. A room that talks on is cut at its
     * breaths, and translated as it goes rather than in pieces of the longest length.
     */
    fun pause(said: Double): Double {
        val shortest = minOf(silence, 0.35)
        return silence - (silence - shortest) * ((said - 3) / 5).coerceIn(0.0, 1.0)
    }

    /** The noise level: the quiet end (10th percentile) of the last five seconds. */
    private fun remember(db: Double) {
        if (historyCount < HISTORY_FRAMES) {
            history[historyCount++] = db
        } else {
            history[historyNext] = db
            historyNext = (historyNext + 1) % HISTORY_FRAMES
        }
        framesSinceFloor += 1
        if (framesSinceFloor < 10 && historyCount >= 20) return
        framesSinceFloor = 0
        val sorted = history.copyOf(historyCount).also { it.sort() }
        floorDb = sorted[sorted.size / 10].coerceIn(-90.0, -25.0)
    }

    private fun finish(): Utterance? {
        speaking = false
        val trailing = maxOf(0, quietFrames - TAIL_FRAMES) * FRAME
        val keptEnd = current.size - minOf(trailing, current.size)
        val speech = (current.size - lead - quietFrames * FRAME).toDouble() / PCM.RATE
        preroll.clear()
        val tail = PREROLL_FRAMES * FRAME
        preroll.append(current.array, maxOf(0, current.size - tail), current.size)
        quietFrames = 0
        val utterance = if (speech >= MINIMUM_SPEECH) Utterance(current.toArray(0, keptEnd), currentStart.toDouble() / PCM.RATE) else null
        current.clear()
        return utterance
    }

    /** Splits a long speech where it is quietest. */
    private fun cut(): Utterance {
        val splitAt = split(current.array, current.size)
        val utterance = Utterance(current.toArray(0, splitAt), currentStart.toDouble() / PCM.RATE)
        current.removeFirst(splitAt)
        currentStart += splitAt
        lead = 0
        quietFrames = 0
        return utterance
    }
}
