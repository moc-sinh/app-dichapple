package vn.mocsinh.mstrans

import android.content.Context
import vn.mocsinh.mstrans.core.Activation

/**
 * MSTrans's activation (see `Activation`): the app, the keyboard's translations, "Dịch bằng MSTrans"
 * and Dịch màn hình work only once the owner's code has been typed. Only the code's salted hash is
 * here; changing the code means making a new salt and hash (PBKDF2-SHA256, the same rounds).
 */
object AppLock {
    private val LOCK = Activation.Lock(
        salt = Activation.bytes("7bb93762ce8b828a7cb11dad2ffb2db9"),
        hash = Activation.bytes("7dfd100efd0c539d9978e2078b78f5e32a9baf62e6acf3b3511e2ac9267194b4"),
        iterations = 200_000,
    )

    fun of(context: Context) = Activation(MSTransApp.of(context).store, LOCK)

    fun active(context: Context): Boolean = of(context).active
}
