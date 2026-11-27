package vn.mocsinh.mstrans.screen

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * The tile "Dịch màn hình" in the panel pulled down from the top of the screen. On: one tap turns
 * translating off and MSTrans gives the accessibility permission back (no other app sees it).
 * Off: one tap opens Android's switch of MSTrans · Dịch màn hình (Android lets no app turn it on
 * by itself); translating starts as soon as it is on.
 */
class ScreenTileService : TileService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        show()
    }

    override fun onClick() {
        super.onClick()
        val service = ScreenState.service
        if (service != null && ScreenState.permitted(this)) {
            service.turnOff()
            // Android takes a moment to take the permission back.
            main.postDelayed({ show() }, 400)
        } else {
            ScreenState.settings(this).enabled = true
            open(ScreenState.permissionPage(this))
        }
        show()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun show() {
        val tile = qsTile ?: return
        val on = ScreenState.service != null && ScreenState.permitted(this)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) "Đang bật" else "Đã tắt"
        tile.contentDescription = if (on) "Dịch Web đang bật. Chạm để tắt hẳn" else "Dịch Web đã tắt. Chạm để bật"
        tile.updateTile()
    }
}
