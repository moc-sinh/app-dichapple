package vn.mocsinh.mstrans.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.Handler
import android.os.Looper
import vn.mocsinh.mstrans.core.conversation.AudioPort
import vn.mocsinh.mstrans.core.conversation.PCM
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The microphone and the speaker for the Hội thoại tab. What the microphone hears comes out as
 * 24 kHz mono PCM16 on the audio thread, a tenth of a second at a time; translations are played
 * back in the same format, one after another.
 *
 * Always the phone's own speaker in Dịch liền and Soniox (they speak while they listen, with the
 * phone's echo cancelling); Chính xác plays like any other sound.
 */
class AndroidAudio(context: Context) : AudioPort {
    override var onSamples: ((ShortArray) -> Unit)? = null
    override var onInterruption: ((Boolean) -> Unit)? = null

    private val app = context.applicationContext
    private val manager: AudioManager = app.getSystemService(AudioManager::class.java)!!
    private val main = Handler(Looper.getMainLooper())
    private val audioThread = Executors.newSingleThreadExecutor { Thread(it, "MSTrans.audio").apply { priority = Thread.MAX_PRIORITY } }
    private val speaker = Speaker(main, "MSTrans.speaker")
    private val alone = Speaker(main, "MSTrans.alone")

    private var record: AudioRecord? = null
    private var reader: Thread? = null
    private var echoCanceller: AcousticEchoCanceler? = null
    @Volatile private var running = false
    private var communication = false
    private var interrupted = false

    /** A phone call or another app took the microphone: Android silences this one (Android 10+). */
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !running) return
            val session = record?.audioSessionId ?: return
            val mine = configs.firstOrNull { it.clientAudioSessionId == session } ?: return
            val silenced = mine.isClientSilenced
            if (silenced == interrupted) return
            interrupted = silenced
            if (silenced) speaker.stop()
            onInterruption?.invoke(silenced)
        }
    }

    override fun start(echoCancelling: Boolean) {
        stopAlone()
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("MSTrans chưa được dùng micro.")
        }
        // Echo cancelling works on the voice-call path: the phone's speaker, at call volume.
        val source = if (echoCancelling) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val opened = open(source) ?: throw IllegalStateException(NO_MICROPHONE)
        val (rec, rate) = opened
        if (echoCancelling) {
            communication = true
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            useSpeaker(true)
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceller = runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull()
            }
        }
        speaker.usage = if (echoCancelling) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            restoreRoute()
            throw IllegalStateException(NO_MICROPHONE)
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            restoreRoute()
            throw IllegalStateException(NO_MICROPHONE)
        }
        record = rec
        running = true
        interrupted = false
        manager.registerAudioRecordingCallback(recordingCallback, main)
        val resampler = Resampler(rate, PCM.RATE)
        reader = Thread({ read(rec, rate, resampler) }, "MSTrans.mic").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** The microphone at 24 kHz where the phone has it, else at 48 or 16 kHz, converted. */
    private fun open(source: Int): Pair<AudioRecord, Int>? {
        for (rate in intArrayOf(PCM.RATE, 48_000, 16_000, 44_100)) {
            val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) continue
            val rec = try {
                AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, rate / 2 * 2))
            } catch (e: Exception) {
                continue
            }
            if (rec.state == AudioRecord.STATE_INITIALIZED) return rec to rate
            rec.release()
        }
        return null
    }

    private fun read(rec: AudioRecord, rate: Int, resampler: Resampler) {
        val buffer = ShortArray(rate / 10)
        while (running) {
            val count = rec.read(buffer, 0, buffer.size)
            if (count < 0) break
            if (count == 0) continue
            val samples = resampler.convert(buffer, count)
            if (samples.isEmpty()) continue
            audioThread.execute { onSamples?.invoke(samples) }
        }
    }

    override fun stop() {
        running = false
        stopPlayback()
        manager.unregisterAudioRecordingCallback(recordingCallback)
        val rec = record
        record = null
        reader?.let { thread ->
            runCatching { rec?.stop() }
            thread.join(500)
        }
        reader = null
        rec?.release()
        echoCanceller?.release()
        echoCanceller = null
        restoreRoute()
    }

    private fun restoreRoute() {
        if (!communication) return
        communication = false
        useSpeaker(false)
        manager.mode = AudioManager.MODE_NORMAL
    }

    private fun useSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                val builtIn = manager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (builtIn != null) manager.setCommunicationDevice(builtIn)
            } else {
                manager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            manager.isSpeakerphoneOn = on
        }
    }

    override fun onAudioThread(block: () -> Unit) {
        audioThread.execute(block)
    }

    /** Played after what is already playing; `done` on the main thread once heard (or stopped). */
    override fun play(pcm: ByteArray, done: () -> Unit) {
        if (!running || pcm.size < 2) {
            main.post(done)
            return
        }
        speaker.enqueue(pcm, done)
    }

    override fun stopPlayback() = speaker.stop()

    /** Đọc lại after Dừng: the speaker only, the microphone stays off. */
    override fun playAlone(pcm: ByteArray, done: () -> Unit) {
        alone.stop()
        alone.usage = AudioAttributes.USAGE_MEDIA
        alone.enqueue(pcm, done)
    }

    override fun stopAlone() = alone.stop()

    companion object {
        const val NO_MICROPHONE = "Không mở được micro. Đóng app khác đang dùng micro rồi thử lại."
    }
}

/** Mono PCM16 from one rate to another: halves 48 kHz by averaging, otherwise straight lines between samples. */
class Resampler(private val from: Int, private val to: Int) {
    private var position = 0.0
    private var last: Short = 0
    private var odd: Short? = null

    fun convert(input: ShortArray, count: Int): ShortArray {
        if (from == to) return input.copyOf(count)
        if (from == to * 2) {
            val out = ShortArray((count + (if (odd != null) 1 else 0)) / 2)
            var n = 0
            var i = 0
            odd?.let { held ->
                if (count > 0) {
                    out[n++] = ((held + input[0]) / 2).toShort()
                    i = 1
                }
            }
            odd = null
            while (i + 1 < count) {
                out[n++] = ((input[i] + input[i + 1]) / 2).toShort()
                i += 2
            }
            if (i < count) odd = input[i]
            return out.copyOf(n)
        }
        val step = from.toDouble() / to
        val out = ArrayList<Short>(count * to / from + 2)
        // `position` runs over [-1, count): -1 is the last sample of the previous chunk.
        while (position < count - 1) {
            val index = position.toInt().let { if (position < 0) -1 else it }
            val fraction = position - index
            val a = if (index < 0) last.toDouble() else input[index].toDouble()
            val b = input[index + 1].toDouble()
            out.add((a + (b - a) * fraction).toInt().toShort())
            position += step
        }
        if (count > 0) last = input[count - 1]
        position -= count
        return out.toShortArray()
    }
}

/**
 * Plays 24 kHz mono PCM16 one piece after another on its own thread. Each piece's `done` runs on the
 * main thread once it has been heard, or as soon as playback is stopped.
 */
class Speaker(private val main: Handler, name: String) {
    @Volatile var usage = AudioAttributes.USAGE_MEDIA

    private class Piece(val generation: Int, val pcm: ByteArray?, val done: (() -> Unit)?)
    private class Waiting(val end: Long, val done: () -> Unit)

    private val pieces = LinkedBlockingQueue<Piece>()
    @Volatile private var generation = 0

    // The writer thread's own.
    private var track: AudioTrack? = null
    private var trackGeneration = -1
    private var written = 0L
    private val waiting = ArrayList<Waiting>()
    private var lastHead = 0L
    private var stalledSince = 0L
    private var padded = false

    init {
        Thread({ loop() }, name).apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun enqueue(pcm: ByteArray, done: () -> Unit) {
        pieces.add(Piece(generation, pcm, done))
    }

    /** What is playing stops at once; what waits is dropped. */
    fun stop() {
        generation += 1
        pieces.add(Piece(generation, null, null))
    }

    private fun loop() {
        while (true) {
            val piece = pieces.poll(20, TimeUnit.MILLISECONDS)
            if (trackGeneration != generation) release()
            if (piece == null) {
                report()
                continue
            }
            if (piece.generation != generation || piece.pcm == null) {
                piece.done?.let { main.post(it) }
                continue
            }
            val t = track ?: create() ?: run {
                main.post(piece.done!!)
                null
            } ?: continue
            if (!write(t, piece.pcm, piece.generation)) {
                main.post(piece.done!!)
                continue
            }
            written += piece.pcm.size / 2
            waiting.add(Waiting(written, piece.done!!))
            padded = false
            report()
        }
    }

    private fun create(): AudioTrack? {
        val minimum = AudioTrack.getMinBufferSize(PCM.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(minimum, PCM.RATE / 10 * 2)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(PCM.RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            return null
        }
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            return null
        }
        t.play()
        track = t
        trackGeneration = generation
        written = 0
        lastHead = 0
        stalledSince = 0
        return t
    }

    /** Small writes that do not block, so a stop is heard within a few milliseconds. */
    private fun write(t: AudioTrack, pcm: ByteArray, piece: Int): Boolean {
        var offset = 0
        while (offset < pcm.size) {
            if (piece != generation) return false
            val n = t.write(pcm, offset, minOf(4_800, pcm.size - offset), AudioTrack.WRITE_NON_BLOCKING)
            if (n < 0) return false
            if (n == 0) {
                report()
                Thread.sleep(10)
            } else {
                offset += n
            }
        }
        return true
    }

    /** Pieces heard by now are told so. A sound too short to start the track is pushed out with silence. */
    private fun report() {
        val t = track ?: return
        if (waiting.isEmpty()) return
        val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        while (waiting.isNotEmpty() && waiting[0].end <= head) main.post(waiting.removeAt(0).done)
        if (waiting.isEmpty()) return
        val now = System.nanoTime()
        if (head != lastHead) {
            lastHead = head
            stalledSince = now
        } else if (stalledSince == 0L) {
            stalledSince = now
        } else if (!padded && pieces.isEmpty() && now - stalledSince > 150_000_000L) {
            padded = true
            val silence = ByteArray(PCM.RATE / 10 * 2)
            t.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
        }
    }

    private fun release() {
        track?.let { t ->
            runCatching {
                t.pause()
                t.flush()
                t.stop()
            }
            t.release()
        }
        track = null
        trackGeneration = generation
        for (w in waiting) main.post(w.done)
        waiting.clear()
    }
}
