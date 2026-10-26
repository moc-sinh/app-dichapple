package vn.mocsinh.mstrans.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One connection pool for every call: OpenAI, Soniox and Google. */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // The answer comes in one piece once it is written: a long message can take a while.
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Connections of its own (Hội thoại asks GPT again while a first request hangs: on the same stalled
     * connection, the second would hang too).
     */
    val spare: OkHttpClient by lazy {
        client.newBuilder().connectionPool(ConnectionPool()).build()
    }

    /** For live sessions: no read timeout (a quiet room sends nothing for a while). */
    val sockets: OkHttpClient by lazy {
        client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    }
}

/** Runs the call; cancelling the coroutine cancels the request. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isCancelled) return
            continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation { runCatching { cancel() } }
}

/**
 * Runs the call and reads the whole answer on OkHttp's thread: read on Android's main thread, the
 * body's bytes would be network on the main thread, which Android stops.
 */
suspend fun Call.text(): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use { it.code to it.body?.string().orEmpty() }
            } catch (e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
                return
            }
            continuation.resume(result)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isCancelled) return
            continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation { runCatching { cancel() } }
}

/** A read that timed out, not a dropped connection. */
fun IOException.isTimeout(): Boolean = this is InterruptedIOException && message?.contains("timeout", true) == true

fun rethrowIfCancelled(e: Throwable) {
    if (e is CancellationException) throw e
}

// Small helpers for reading JSON answers without a schema.

val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

fun parseObject(text: String): JsonObject? =
    runCatching { lenientJson.parseToJsonElement(text) as? JsonObject }.getOrNull()

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
