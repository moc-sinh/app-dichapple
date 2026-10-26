package vn.mocsinh.mstrans.core.conversation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import vn.mocsinh.mstrans.core.Language
import vn.mocsinh.mstrans.core.TokenUsage
import vn.mocsinh.mstrans.core.TranslationModel
import vn.mocsinh.mstrans.core.arr
import vn.mocsinh.mstrans.core.bool
import vn.mocsinh.mstrans.core.obj
import vn.mocsinh.mstrans.core.str
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One meeting or conversation of the Hội thoại tab, as it is kept on the phone. */
data class Meeting(
    val id: String = UUID.randomUUID().toString(),
    var started: Long = System.currentTimeMillis(),
    val lines: MutableList<Line> = ArrayList(),
    var summary: String? = null,
    /** What it cost with OpenAI and Soniox, estimated. */
    var cost: CostMeter? = null,
) {
    data class Line(
        val at: Long,
        /** Said by the user (with the Tôi nói button). */
        val mine: Boolean,
        /** What was spoken in ("vi", "zh"…). */
        val language: String,
        val text: String,
        val translation: String,
        /** What it was translated into. */
        val target: String,
    )

    val ended: Long get() = lines.lastOrNull()?.at ?: started

    val title: String get() = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.forLanguageTag("vi-VN")).format(Date(started))

    /** "32 phút", "1 giờ 5 phút". */
    val length: String
        get() {
            val minutes = maxOf(1, ((ended - started) / 60_000).toInt())
            return if (minutes < 60) "$minutes phút" else "${minutes / 60} giờ ${minutes % 60} phút"
        }

    /** The minutes as text: each sentence, who spoke which language, and its translation. */
    val transcript: String
        get() {
            val time = SimpleDateFormat("HH:mm:ss", Locale.US)
            val text = StringBuilder("Cuộc họp $title ($length)\n")
            for (line in lines) {
                val who = if (line.mine) "Tôi" else Language.displayName(line.language)
                text.append("\n[${time.format(Date(line.at))}] $who: ${line.text}\n")
                if (line.translation.isNotEmpty()) text.append("  → ${line.translation}\n")
            }
            val summary = summary
            if (!summary.isNullOrEmpty()) text.append("\nTÓM TẮT\n$summary\n")
            return text.toString()
        }

    fun copyDeep(): Meeting = copy(lines = ArrayList(lines), cost = cost?.copy())

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("started", started)
        put("lines", JsonArray(lines.map {
            buildJsonObject {
                put("at", it.at)
                put("mine", it.mine)
                put("language", it.language)
                put("text", it.text)
                put("translation", it.translation)
                put("target", it.target)
            }
        }))
        put("summary", summary?.let { JsonPrimitive(it) } ?: JsonNull)
        put("cost", cost?.let { JsonPrimitive(it.dollars) } ?: JsonNull)
    }

    companion object {
        fun fromJson(o: JsonObject): Meeting? {
            val id = o["id"].str() ?: return null
            val lines = (o["lines"].arr() ?: emptyList()).mapNotNull { element ->
                val l = element.obj() ?: return@mapNotNull null
                Line(
                    l["at"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                    l["mine"].bool() ?: false, l["language"].str() ?: "", l["text"].str() ?: "",
                    l["translation"].str() ?: "", l["target"].str() ?: "",
                )
            }
            return Meeting(
                id = id,
                started = o["started"]?.jsonPrimitive?.longOrNull ?: return null,
                lines = lines.toMutableList(),
                summary = o["summary"].str(),
                cost = (o["cost"] as? JsonPrimitive)?.doubleOrNull?.let { CostMeter(it) },
            )
        }
    }
}

/**
 * Meetings are files in the app's own folder, one JSON file each, written as the meeting goes so
 * an app closed by the system loses nothing.
 */
class MeetingStore(private val folder: File) {
    private val queue = Executors.newSingleThreadExecutor { Thread(it, "MSTrans.meetings").apply { isDaemon = true } }

    /** Written off the main thread, in order. */
    fun save(meeting: Meeting) {
        if (meeting.lines.isEmpty()) return
        val snapshot = meeting.copyDeep().toJson().toString()
        queue.execute {
            folder.mkdirs()
            val file = File(folder, "${meeting.id}.json")
            val temp = File(folder, "${meeting.id}.json.tmp")
            runCatching {
                temp.writeText(snapshot)
                if (!temp.renameTo(file)) {
                    file.writeText(snapshot)
                    temp.delete()
                }
            }
        }
    }

    /** Newest first. */
    fun all(): List<Meeting> = queue.submit<List<Meeting>> {
        (folder.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { file -> runCatching { Meeting.fromJson(Json.parseToJsonElement(file.readText()).jsonObject) }.getOrNull() }
            .sortedByDescending { it.started }
    }.get()

    fun delete(meeting: Meeting) {
        queue.execute { File(folder, "${meeting.id}.json").delete() }
    }
}

/**
 * What the Hội thoại tab spends with OpenAI and Soniox, estimated from what was sent and what
 * OpenAI reports (their prices of October 2026, in US dollars). The phone's own voice costs nothing.
 */
data class CostMeter(var dollars: Double = 0.0) {
    object Rate {
        /** gpt-transcribe, per minute of sound sent (only speech is sent, not the pauses). */
        const val TRANSCRIBE_PER_MINUTE = 0.0045
        /** gpt-4o-mini-tts, per minute of voice made. */
        const val VOICE_PER_MINUTE = 0.015
        /** gpt-realtime-translate, per minute of sound sent to one session. */
        const val LIVE_PER_MINUTE = 0.034
        /** Soniox's live recognition with translation, per minute of sound sent. */
        const val SONIOX_PER_MINUTE = 0.003
    }

    fun transcribed(seconds: Double) {
        dollars += maxOf(0.0, seconds) / 60 * Rate.TRANSCRIBE_PER_MINUTE
    }

    fun voiced(seconds: Double) {
        dollars += maxOf(0.0, seconds) / 60 * Rate.VOICE_PER_MINUTE
    }

    /** Sound sent to a live session: OpenAI's, or Soniox's. */
    fun streamed(seconds: Double, soniox: Boolean = false) {
        dollars += maxOf(0.0, seconds) / 60 * if (soniox) Rate.SONIOX_PER_MINUTE else Rate.LIVE_PER_MINUTE
    }

    /** A translation or a summary, by the tokens OpenAI counted. */
    fun used(usage: TokenUsage?, model: TranslationModel) {
        usage ?: return
        val (input, cached, output) = model.price
        val fresh = maxOf(0, usage.input - usage.cachedInput)
        dollars += (fresh * input + usage.cachedInput * cached + usage.output * output) / 1_000_000
    }

    companion object {
        /** Per hour of people speaking, for the choices made. */
        fun perHour(live: Boolean, soniox: Boolean, model: TranslationModel, readAloud: Boolean, openAIVoice: Boolean): Double {
            if (soniox) return 60 * Rate.SONIOX_PER_MINUTE + if (readAloud && openAIVoice) 60 * Rate.VOICE_PER_MINUTE else 0.0
            if (live) return 60 * Rate.LIVE_PER_MINUTE
            val (input, _, output) = model.price
            val sentence = (750 * input + (if (model == TranslationModel.LUNA) 60 else 160) * output) / 1_000_000
            var hour = 60 * Rate.TRANSCRIBE_PER_MINUTE + 720 * sentence
            if (readAloud && openAIVoice) hour += 60 * Rate.VOICE_PER_MINUTE
            return hour
        }

        /** "khoảng 0,03 USD", "dưới 0,01 USD". */
        fun text(dollars: Double): String {
            if (dollars < 0.01) return "dưới 0,01 USD"
            val rounded = if (dollars < 1) String.format(Locale.US, "%.2f", dollars) else String.format(Locale.US, "%.1f", dollars)
            return "khoảng " + rounded.replace(".", ",") + " USD"
        }
    }
}
