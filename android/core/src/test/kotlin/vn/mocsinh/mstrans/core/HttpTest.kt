package vn.mocsinh.mstrans.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpTest {
    /** Eight slow answers in flight (a busy room) do not hold up a ninth (the user's Tôi nói). */
    @Test
    fun manyRequestsToOneHostDoNotQueue() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/slow") Thread.sleep(1500)
                return MockResponse().setBody("ok")
            }
        }
        server.start()
        val slow = (1..8).map {
            async(Dispatchers.IO) { Http.client.newCall(Request.Builder().url(server.url("/slow")).build()).text() }
        }
        // OkHttp's own limit was 5: once 5 are in, the rest would wait in its queue.
        val deadline = System.currentTimeMillis() + 5000
        while (server.requestCount < 5 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        Thread.sleep(200)
        val began = System.currentTimeMillis()
        val (status, body) = Http.client.newCall(Request.Builder().url(server.url("/fast")).build()).text()
        val took = System.currentTimeMillis() - began
        assertEquals(200 to "ok", status to body)
        assertTrue(took < 1000, "the fast request waited $took ms behind the slow ones")
        slow.awaitAll()
        server.shutdown()
    }
}
