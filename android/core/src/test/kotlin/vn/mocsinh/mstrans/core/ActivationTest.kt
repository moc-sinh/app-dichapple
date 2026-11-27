package vn.mocsinh.mstrans.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** With a code made up for the test (the real one is not in the source). */
class ActivationTest {
    private val salt = Activation.bytes("00112233445566778899aabbccddeeff")
    private val lock = Activation.Lock(salt, Activation.hash("111222", salt, 1_000), 1_000)
    private var now = 1_000_000L

    @Test
    fun `the right code activates, and stays activated`() {
        val store = MemoryStore()
        val activation = Activation(store, lock) { now }
        assertFalse(activation.active)
        assertEquals(Activation.Answer.Accepted, activation.tryCode(" 111222 "))
        assertTrue(Activation(store, lock) { now }.active)
        // A new code (a new lock) asks again.
        val other = Activation.Lock(salt, Activation.hash("999000", salt, 1_000), 1_000)
        assertFalse(Activation(store, other) { now }.active)
    }

    @Test
    fun `wrong codes wait longer and longer`() {
        val store = MemoryStore()
        val activation = Activation(store, lock) { now }
        assertEquals(Activation.Answer.Wrong(0), activation.tryCode("000000"))
        assertEquals(Activation.Answer.Wrong(0), activation.tryCode("000001"))
        assertEquals(Activation.Answer.Wrong(30_000), activation.tryCode("000002"))
        // Even the right code waits its turn.
        assertTrue(activation.tryCode("111222") is Activation.Answer.TooSoon)
        now += 30_001
        assertEquals(Activation.Answer.Wrong(60_000), activation.tryCode("000003"))
        now += 60_001
        assertEquals(Activation.Answer.Accepted, activation.tryCode("111222"))
        assertEquals(0, activation.wait)
    }

    @Test
    fun `nothing of the code is kept`() {
        val store = MemoryStore()
        Activation(store, lock) { now }.tryCode("111222")
        val kept = store.get(Activation.Key.STAMP)!!
        assertFalse("111222" in kept)
        assertTrue(kept.startsWith("v1:"))
    }
}
