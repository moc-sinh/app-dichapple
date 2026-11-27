package vn.mocsinh.mstrans.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.security.MessageDigest

/**
 * "Xác nhận kết nối" for the keys typed in Cài đặt: each asked of its own service with a request
 * that costs nothing (Soniox's list of models, Google's list of voices). A key confirmed is
 * remembered by a fingerprint (never the key itself): changed, it is not confirmed any more.
 */
object KeyCheck {
    enum class Service(val title: String) { OPENAI("OpenAI"), SONIOX("Soniox"), GOOGLE("Google Cloud") }

    /** Null: the key works. Otherwise what went wrong, in Vietnamese. */
    suspend fun soniox(key: String, base: String = "https://api.soniox.com"): String? =
        ask(Request.Builder().url("$base/v1/models").header("Authorization", "Bearer $key").build())

    suspend fun google(key: String, base: String = "https://texttospeech.googleapis.com"): String? =
        ask(Request.Builder().url("$base/v1/voices?languageCode=vi-VN").header("X-Goog-Api-Key", key).build())

    private suspend fun ask(request: Request): String? = withContext(Dispatchers.IO) {
        try {
            Http.client.newCall(request).execute().use { response ->
                when (response.code) {
                    in 200..299 -> null
                    401, 403 -> "Key không đúng hoặc chưa được bật dịch vụ (${response.code})."
                    429 -> "Dịch vụ đang báo quá giới hạn (429). Thử lại sau."
                    else -> "Dịch vụ trả lỗi ${response.code}."
                }
            }
        } catch (e: java.io.IOException) {
            "Không kết nối được. Kiểm tra mạng rồi thử lại."
        }
    }

    private fun key(service: Service) = "keyConfirmed." + service.name.lowercase()

    private fun fingerprint(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.trim().toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    fun confirmed(store: KeyValueStore, service: Service, key: String?): Boolean =
        key != null && store.get(key(service)) == fingerprint(key)

    fun confirm(store: KeyValueStore, service: Service, key: String) = store.set(key(service), fingerprint(key))

    fun forget(store: KeyValueStore, service: Service) = store.set(key(service), null)

    /** The key as shown once confirmed: its start and end, the rest hidden ("sk-p…••••…a1b2"). */
    fun masked(key: String): String {
        val k = key.trim()
        if (k.length <= 8) return "•".repeat(k.length)
        return k.take(4) + "•".repeat(8) + k.takeLast(4)
    }
}
