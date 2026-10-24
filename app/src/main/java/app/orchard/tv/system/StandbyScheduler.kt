package app.orchard.tv.system

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.orchard.tv.settings.AerialSettings
import java.util.concurrent.TimeUnit

/**
 * Puts the TV in standby N minutes after the screensaver starts, for owners who leave the aerial
 * films on overnight. The action is the power key over the same su path as the other Root helpers:
 * standby wakes with the remote, unlike a poweroff. Any wake cancels the timer via [stop].
 */
object StandbyScheduler {
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    /** The injected key: POWER toggles standby on this TV. */
    fun command() = "input keyevent 26"

    /** Arms the timer for the configured delay; a no-op while off (0) or before the dream shows. */
    fun start(context: Context) {
        stop()
        val minutes = AerialSettings.screensaverStandbyMinutes
        if (minutes <= 0) return
        val app = context.applicationContext
        pending = Runnable {
            runCatching {
                val child = ProcessBuilder("su", "-c", command()).start()
                child.outputStream.close()
                if (!child.waitFor(15, TimeUnit.SECONDS)) child.destroy()
            }.onFailure { DiagLog.i("standby failed: $it") }
        }.also { handler.postDelayed(it, minutes * 60_000L) }
    }

    /** Cancels the pending standby: every wake path calls this. */
    fun stop() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }
}
