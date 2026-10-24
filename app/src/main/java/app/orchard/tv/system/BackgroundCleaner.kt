package app.orchard.tv.system

import android.content.Context
import app.orchard.tv.settings.AerialSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Stops a configured list of background apps with `am force-stop` over the same su path as the
 * Root backlight controls: this TV's memory is small, and its video apps outlive their use. A
 * normal app cannot stop other apps, so without Root the stop fails and is reported as such.
 * Only the owner's list ever runs; Aerial itself and anything not shaped like a package name is
 * dropped, so the command cannot carry shell input.
 */
object BackgroundCleaner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastRunAt = AtomicLong(0)
    private const val MIN_INTERVAL_MS = 10_000L

    /** The packages a stop may name: never Aerial itself, never anything but a plain package name. */
    fun targets(packages: Collection<String>, self: String): List<String> =
        packages.filter { it != self && it.matches(Regex("[A-Za-z0-9._]+")) }.distinct().sorted()

    /** The su line stopping exactly those targets, or null when the list has nothing valid. */
    fun command(packages: Collection<String>, self: String): String? {
        val list = targets(packages, self)
        if (list.isEmpty()) return null
        return list.joinToString("; ") { "am force-stop $it" }
    }

    /** Stops the configured list now; [onDone] gets the count, 0 for an empty list, -1 when su failed. */
    fun cleanNow(context: Context, onDone: (Int) -> Unit = {}) {
        val app = context.applicationContext
        val targets = targets(AerialSettings.backgroundCleanPackages, app.packageName)
        if (targets.isEmpty()) {
            onDone(0)
            return
        }
        scope.launch {
            val exit = runCatching {
                val child = ProcessBuilder("su", "-c", targets.joinToString("; ") { "am force-stop $it" }).start()
                child.outputStream.close()
                if (child.waitFor(30, TimeUnit.SECONDS)) child.exitValue() else { child.destroy(); -1 }
            }.getOrDefault(-1)
            lastRunAt.set(System.currentTimeMillis())
            val stopped = if (exit == 0) targets.size else -1
            withContext(Dispatchers.Main) { onDone(stopped) }
        }
    }

    /**
     * Auto mode, called only when Home actually comes back from opening another app — never after
     * Aerial's own screensaver. Rate-limited so flicking in and out of Home cannot spin su.
     */
    fun onReturnedFromApp(context: Context) {
        if (!AerialSettings.backgroundCleanAuto || AerialSettings.backgroundCleanPackages.isEmpty()) return
        if (System.currentTimeMillis() - lastRunAt.get() < MIN_INTERVAL_MS) return
        cleanNow(context)
    }
}
