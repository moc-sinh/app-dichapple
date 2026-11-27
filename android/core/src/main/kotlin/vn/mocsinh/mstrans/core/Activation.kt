package vn.mocsinh.mstrans.core

import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The activation code: MSTrans works only once the code its owner gives out has been typed. The
 * code itself is nowhere in the app or its source (the repository is public): only a salted,
 * slow hash of it (PBKDF2, `iterations` rounds). Six digits checked on the phone keep out whoever
 * is not given them; they cannot stop someone set on taking the app apart.
 *
 * Once accepted, a stamp of the lock is kept: a new code (a new lock) asks again. Wrong tries wait
 * longer and longer.
 */
class Activation(private val store: KeyValueStore, private val lock: Lock, private val clock: () -> Long = System::currentTimeMillis) {
    class Lock(val salt: ByteArray, val hash: ByteArray, val iterations: Int)

    object Key {
        const val STAMP = "activation"
        const val FAILED = "activationFailed"
        const val UNTIL = "activationWaitUntil"
    }

    /** The app is activated (with this lock). */
    val active: Boolean get() = store.get(Key.STAMP) == stamp(lock)

    /** Milliseconds to wait before the next try is taken (0: now). */
    val wait: Long get() = ((store.get(Key.UNTIL)?.toLongOrNull() ?: 0L) - clock()).coerceAtLeast(0)

    sealed interface Answer {
        data object Accepted : Answer
        data class Wrong(val waitMs: Long) : Answer
        data class TooSoon(val waitMs: Long) : Answer
    }

    /** Slow on purpose (a fraction of a second on a phone): call it off the main thread. */
    fun tryCode(code: String): Answer {
        wait.takeIf { it > 0 }?.let { return Answer.TooSoon(it) }
        if (matches(code.trim(), lock)) {
            store.set(Key.STAMP, stamp(lock))
            store.set(Key.FAILED, null)
            store.set(Key.UNTIL, null)
            return Answer.Accepted
        }
        val failed = (store.get(Key.FAILED)?.toIntOrNull() ?: 0) + 1
        store.set(Key.FAILED, failed.toString())
        // Three tries free, then 30 s, doubling, at most an hour.
        val waitMs = if (failed < FREE_TRIES) 0L else minOf(30_000L shl (failed - FREE_TRIES), 3_600_000L)
        if (waitMs > 0) store.set(Key.UNTIL, (clock() + waitMs).toString())
        return Answer.Wrong(waitMs)
    }

    companion object {
        private const val FREE_TRIES = 3

        fun hash(code: String, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(code.toCharArray(), salt, iterations, 256)
            return try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        fun matches(code: String, lock: Lock): Boolean =
            code.isNotEmpty() && MessageDigest.isEqual(hash(code, lock.salt, lock.iterations), lock.hash)

        /** What is kept once accepted: names the lock, gives nothing of the code away. */
        fun stamp(lock: Lock): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(lock.salt + lock.hash)
            return "v1:" + digest.take(8).joinToString("") { "%02x".format(it) }
        }

        fun bytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
