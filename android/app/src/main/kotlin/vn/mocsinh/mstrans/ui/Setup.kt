package vn.mocsinh.mstrans.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import vn.mocsinh.mstrans.MSTransApp
import vn.mocsinh.mstrans.keyboard.MSTransKeyboard

/** How far setup has got: the keyboard turned on in Android's settings, chosen, and the OpenAI key. */
object Setup {
    data class Status(val keyboardEnabled: Boolean, val keyboardSelected: Boolean, val hasKey: Boolean) {
        val done: Boolean get() = keyboardEnabled && hasKey
        val remaining: Int get() = (if (keyboardEnabled) 0 else 1) + (if (hasKey) 0 else 1)
    }

    private fun id(context: Context): String = ComponentName(context, MSTransKeyboard::class.java).flattenToShortString()

    fun status(context: Context): Status {
        val imm = context.getSystemService(InputMethodManager::class.java)
        val ours = ComponentName(context, MSTransKeyboard::class.java)
        val enabled = imm?.enabledInputMethodList?.any { it.component == ours } ?: false
        val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val selected = current == id(context) || ComponentName.unflattenFromString(current) == ours
        return Status(enabled, selected, MSTransApp.of(context).settings.apiKey != null)
    }

    /** Android's list of keyboards, where MSTrans is turned on. */
    fun openKeyboardSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The keyboard chooser, to switch to MSTrans now. */
    fun pickKeyboard(context: Context) {
        context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    /** The app's page in Android's settings (permissions). */
    fun openAppSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Text-to-speech settings, where a voice for another language is downloaded. */
    fun openVoiceSettings(context: Context) {
        val intent = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun openLink(context: Context, url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
