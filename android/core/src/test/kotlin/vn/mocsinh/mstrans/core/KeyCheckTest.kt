package vn.mocsinh.mstrans.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyCheckTest {
    @Test
    fun `a key is asked of its service with a free request`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"models":[]}"""))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"voices":[]}"""))
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        assertNull(KeyCheck.soniox("good", base))
        assertTrue(KeyCheck.soniox("bad", base)!!.contains("Key không đúng"))
        assertNull(KeyCheck.google("g-key", base))
        assertEquals("Bearer good", server.takeRequest().getHeader("Authorization"))
        server.takeRequest()
        val google = server.takeRequest()
        assertEquals("/v1/voices?languageCode=vi-VN", google.path)
        assertEquals("g-key", google.getHeader("X-Goog-Api-Key"))
        server.shutdown()
    }

    @Test
    fun `a confirmed key is remembered by a fingerprint, not kept`() {
        val store = MemoryStore()
        assertFalse(KeyCheck.confirmed(store, KeyCheck.Service.OPENAI, "sk-test-123456789"))
        KeyCheck.confirm(store, KeyCheck.Service.OPENAI, "sk-test-123456789")
        assertTrue(KeyCheck.confirmed(store, KeyCheck.Service.OPENAI, "sk-test-123456789"))
        assertFalse(KeyCheck.confirmed(store, KeyCheck.Service.OPENAI, "sk-test-other"))
        assertFalse(KeyCheck.confirmed(store, KeyCheck.Service.SONIOX, "sk-test-123456789"))
        assertFalse(store.get("keyConfirmed.openai")!!.contains("sk-test"))
        assertEquals("sk-t••••••••6789", KeyCheck.masked("sk-test-123456789"))
    }
}
