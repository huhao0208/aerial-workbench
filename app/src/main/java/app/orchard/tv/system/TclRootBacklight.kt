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
    private val session = TclRootPictureSession()

    fun enabled(context: Context) = BuildConfig.WORKBENCH && Build.VERSION.SDK_INT >= 26 &&
        context.getSharedPreferences("backlight", Context.MODE_PRIVATE).getBoolean("root_tcl", false)

    fun panelActive(value: Boolean) = session.panelActive(value)
    suspend fun close() = session.close()

    /** One read-only authorization/read attempt per app process, including when the panel is closed. */
    suspend fun prepare(context: Context): Panel? =
        if (enabled(context)) session.prepare(shell(context)) else null

    suspend fun panel(context: Context): Panel? = request(context, "GET")
    suspend fun adjust(context: Context, direction: Int): Panel? {
        if (direction != -1 && direction != 1) return null
        return request(context, "STEP $direction")
    }
    suspend fun setLowBlue(context: Context, enabled: Boolean): Panel? = request(context, "EYE ${if (enabled) 1 else 0}")

    private suspend fun request(context: Context, command: String): Panel? =
        if (enabled(context)) session.request(shell(context), command) else null

    private fun shell(context: Context): String {
        // TCL checks the process identity against its installed settings certificate (T800T11).
        // No system APK, embedded vendor key or SELinux policy is changed.
        val apk = context.applicationInfo.sourceDir
        val quotedApk = "'" + apk.replace("'", "'\\''") + "'"
        return "CLASSPATH=$quotedApk exec /system/bin/app_process /system/bin --nice-name=com.tcl.settings app.orchard.tv.system.TclBacklightHelper"
    }
}

/** Serializes startup and menu I/O. The startup path can only issue GET, never a picture write. */
internal class TclRootPictureSession(
    private val startProcess: (String) -> Process = { shell ->
        ProcessBuilder("su", "-c", shell).redirectErrorStream(true).start()
    },
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private val lock = Mutex()
    @Volatile private var active = false
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var retryAfter = 0L
    private var startupAttempted = false
    private var lastPanel: TclRootBacklight.Panel? = null

    fun panelActive(value: Boolean) { active = value }

    suspend fun prepare(shell: String): TclRootBacklight.Panel? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (startupAttempted) return@withLock lastPanel
            startupAttempted = true
            try {
                exchangeLocked(shell, "GET", preparing = true)
            } finally {
                // Reuse the helper if the user opened the panel during authorization. Otherwise
                // release it immediately; preparation must not add an idle root process.
                if (!active) closeLocked()
            }
        }
    }

    suspend fun request(shell: String, command: String): TclRootBacklight.Panel? = withContext(Dispatchers.IO) {
        if (!active) return@withContext null
        lock.withLock { exchangeLocked(shell, command, preparing = false) }
    }

    suspend fun close() = withContext(Dispatchers.IO) { lock.withLock { if (!active) closeLocked() } }

    private fun closeLocked() {
        runCatching { writer?.apply { write("EXIT\n"); flush(); close() } }
        runCatching { reader?.close() }
        process?.destroy()
        process = null; reader = null; writer = null
    }

    private suspend fun exchangeLocked(shell: String, command: String, preparing: Boolean): TclRootBacklight.Panel? {
        if ((!preparing && !active) || now() < retryAfter) return null
        try {
            if (process?.isAlive != true) {
                closeLocked()
                val child = startProcess(shell)
                process = child; reader = child.inputStream.bufferedReader(); writer = child.outputStream.bufferedWriter()
            }
            writer!!.apply { write(command); newLine(); flush() }
            val deadline = now() + 20_000
            val line = StringBuilder()
            while ((active || preparing) && now() < deadline) {
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
                            if (value != null && value in 1..100) {
                                return TclRootBacklight.Panel(value / 100f, eye).also { lastPanel = it }
                            }
                            break
                        }
                    } else if (line.length < 512) line.append(ch.toChar()) else break
                } else {
                    if (process?.isAlive != true) break
                    delay(20)
                }
            }
            closeLocked(); retryAfter = now() + 30_000
            return null
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            closeLocked()
            throw cancelled
        } catch (_: Exception) {
            closeLocked(); retryAfter = now() + 30_000
            return null
        }
    }
}
