package app.orchard.tv.system

import android.content.Context
import android.os.Build
import android.os.SystemClock
import app.orchard.tv.BuildConfig
import java.io.BufferedReader
import java.io.BufferedWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Explicitly enabled on the owner's rooted TCL. All process I/O stays off the UI thread. */
object TclRootBacklight {
    data class Panel(val brightness: Float, val lowBlue: Int?)
    private val lock = Mutex()
    @Volatile private var active = false
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var retryAfter = 0L

    fun enabled(context: Context) = BuildConfig.WORKBENCH && Build.VERSION.SDK_INT >= 26 &&
        context.getSharedPreferences("backlight", Context.MODE_PRIVATE).getBoolean("root_tcl", false)

    fun panelActive(value: Boolean) { active = value }

    suspend fun close() = withContext(Dispatchers.IO) { lock.withLock { if (!active) closeLocked() } }

    private fun closeLocked() {
        runCatching { writer?.apply { write("EXIT\n"); flush(); close() } }
        runCatching { reader?.close() }
        process?.destroy()
        process = null; reader = null; writer = null
    }

    suspend fun panel(context: Context) = request(context, "GET")
    suspend fun adjust(context: Context, direction: Int): Panel? {
        if (direction != -1 && direction != 1) return null
        return request(context, "STEP $direction")
    }

    suspend fun setLowBlue(context: Context, enabled: Boolean) = request(context, "EYE ${if (enabled) 1 else 0}")

    private suspend fun request(context: Context, command: String): Panel? = withContext(Dispatchers.IO) {
        if (!active || !enabled(context)) return@withContext null
        lock.withLock {
            if (!active || SystemClock.elapsedRealtime() < retryAfter) return@withLock null
            try {
                if (process?.isAlive != true) {
                    closeLocked()
                    // TCL authorizes this SDK against the process package name and installed
                    // settings certificate. The root child uses its identity only for this API;
                    // no system APK, signature or SELinux policy is changed (T800T11 verified).
                    val apk = context.applicationInfo.sourceDir
                    val quotedApk = "'" + apk.replace("'", "'\\''") + "'"
                    val shell = "CLASSPATH=$quotedApk exec /system/bin/app_process /system/bin --nice-name=com.tcl.settings app.orchard.tv.system.TclBacklightHelper"
                    val child = ProcessBuilder("su", "-c", shell).redirectErrorStream(true).start()
                    process = child; reader = child.inputStream.bufferedReader(); writer = child.outputStream.bufferedWriter()
                }
                writer!!.apply { write(command); newLine(); flush() }
                val deadline = SystemClock.elapsedRealtime() + 20_000
                val line = StringBuilder()
                while (active && SystemClock.elapsedRealtime() < deadline) {
                    if (reader!!.ready()) {
                        val ch = reader!!.read()
                        if (ch < 0) break
                        if (ch == 10) {
                            val text = line.toString(); line.setLength(0)
                            if (text == "WB-BACKLIGHT ERR") break
                            if (text.startsWith("WB-BACKLIGHT OK ")) {
                                val parts = text.removePrefix("WB-BACKLIGHT OK ").split(' ')
                                val value = parts.getOrNull(0)?.toIntOrNull()
                                val eye = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..2 }
                                if (value != null && value in 1..100) return@withLock Panel(value / 100f, eye)
                                break
                            }
                        } else if (line.length < 512) line.append(ch.toChar()) else break
                    } else {
                        if (process?.isAlive != true) break
                        delay(20)
                    }
                }
                closeLocked(); retryAfter = SystemClock.elapsedRealtime() + 30_000
                null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                closeLocked()
                throw cancelled
            } catch (_: Exception) {
                closeLocked(); retryAfter = SystemClock.elapsedRealtime() + 30_000
                null
            }
        }
    }
}
