package vn.mocsinh.mstrans.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import vn.mocsinh.mstrans.core.conversation.AudioPort
import vn.mocsinh.mstrans.core.conversation.ConversationModel
import vn.mocsinh.mstrans.core.conversation.ConversationServices
import vn.mocsinh.mstrans.core.conversation.MeetingStore
import vn.mocsinh.mstrans.core.conversation.SpeechServices
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A meeting in Chính xác, with the services and the phone's sound faked. */
class ConversationModelTest {
    private class FakeAudio(val scope: CoroutineScope) : AudioPort {
        override var onSamples: ((ShortArray) -> Unit)? = null
        override var onInterruption: ((Boolean) -> Unit)? = null
        var started = false
        val played = ArrayList<Int>()

        override fun start(echoCancelling: Boolean) {
            started = true
        }

        override fun stop() {
            started = false
        }

        override fun onAudioThread(block: () -> Unit) = block()

        override fun play(pcm: ByteArray, done: () -> Unit) {
            played.add(pcm.size)
            done()
        }

        override fun stopPlayback() = Unit
        override fun playAlone(pcm: ByteArray, done: () -> Unit) = done()
        override fun stopAlone() = Unit
    }

    @Test
    fun meetingSentenceBySentence() = runBlocking {
        val settings = Settings(MemoryStore()).apply { apiKey = "sk-test" }
        val audio = FakeAudio(this)
        val folder = Files.createTempDirectory("meetings").toFile()
        val translated = ArrayList<String>()
        val services = ConversationServices(
            transcribe = { _, wav, _ ->
                assertTrue(wav.size > 44)
                if (translated.isEmpty()) SpeechServices.Transcript("这个价格是多少？", "zh")
                else SpeechServices.Transcript("Nếu anh đặt năm trăm chiếc, bên em giảm năm phần trăm", "vi")
            },
            translate = { _, text, target, _, _ ->
                translated.add(text)
                Translation(if (target.code == "vi") "Giá này bao nhiêu?" else "如果您订五百台，我们优惠百分之五", null, target)
            },
            speak = { _, _, _, _, heard ->
                val pcm = ByteArray(48_000)
                heard(pcm.copyOf(24_000))
                pcm
            },
        )
        val model = ConversationModel(settings, MemoryStore(), audio, MeetingStore(folder), this, services)
        model.voice = "marin"
        model.start()
        assertTrue(model.running && audio.started)

        val sound = Sound()
        fun feed(samples: ShortArray) {
            var i = 0
            while (i < samples.size) {
                audio.onSamples!!.invoke(samples.copyOfRange(i, minOf(samples.size, i + 2_400)))
                i += 2_400
            }
        }
        feed(sound.quiet(1.0) + sound.speech(2.0) + sound.quiet(1.2))
        withTimeout(5_000) {
            while (model.entries.firstOrNull()?.state != ConversationModel.State.Done) delay(10)
        }
        val first = model.entries.first()
        assertEquals("zh", first.language)
        assertEquals("Giá này bao nhiêu?", first.translation)
        assertEquals("zh", model.partnerLanguage.code, "Tự động follows the others' language")
        withTimeout(5_000) { while (audio.played.isEmpty()) delay(10) }

        // The user holds Tôi nói and speaks: the phone stops reading at once.
        model.talk(true)
        val before = audio.played.sum()
        // Long enough for the words recognised (more words than the sound could hold is noise).
        feed(sound.speech(3.0))
        model.talk(false)
        withTimeout(5_000) {
            while (model.entries.size < 2 || model.entries[1].state != ConversationModel.State.Done) delay(10)
        }
        val mine = model.entries[1]
        assertTrue(mine.mine)
        assertEquals("zh", mine.target)
        assertEquals("如果您订五百台，我们优惠百分之五", mine.translation)
        withTimeout(5_000) { while (audio.played.sum() < before + 48_000) delay(10) }

        model.stop()
        assertEquals(2, model.meeting.lines.size)
        assertTrue((model.meeting.cost?.dollars ?: 0.0) > 0, "the meeting's cost is counted")
        withTimeout(5_000) { while (MeetingStore(folder).all().isEmpty()) delay(10) }
        assertEquals(2, MeetingStore(folder).all().first().lines.size)
        folder.deleteRecursively()
        Unit
    }
}
