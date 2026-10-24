package app.orchard.tv.update

import android.content.Context
import android.os.Build
import app.orchard.tv.system.TclRootBacklight
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only the owner's explicit Root opt-in uses the tested TCL self-update route. */
internal object TclSelfUpdate {
    fun supported(context: Context) = Build.DEVICE == "tc8000_ay30a2" && TclRootBacklight.enabled(context) &&
        context.packageManager.resolveContentProvider("com.tcl.providers.config", 0) != null

    suspend fun install(context: Context, apk: File): Boolean = withContext(Dispatchers.IO) {
        val script = File(context.cacheDir, "tcl-self-update.sh")
        context.assets.open("tcl-self-update.sh").use { input -> script.outputStream().use { input.copyTo(it) } }
        val hash = apk.inputStream().use { input ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"
        val command = """
            set -eu
            umask 077
            stage=/data/adb/aerial-updater
            mkdir -p "${'$'}stage"
            mkdir "${'$'}stage/lock" || exit 40
            cp ${quote(script.path)} "${'$'}stage/run.sh"
            cp ${quote(apk.path)} "${'$'}stage/update.apk"
            chmod 0700 "${'$'}stage/run.sh"
            rm -f "${'$'}stage/result"
            /data/adb/magisk/busybox setsid /system/bin/sh "${'$'}stage/run.sh" --worker $hash > "${'$'}stage/worker.log" 2>&1 < /dev/null &
            child=${'$'}!
            attempt=0
            while [ ! -f "${'$'}stage/result" ]; do
                kill -0 "${'$'}child" 2>/dev/null || { rmdir "${'$'}stage/lock"; exit 41; }
                attempt=${'$'}((attempt+1)); [ "${'$'}attempt" -lt 150 ] || exit 42
                sleep 1
            done
            result=${'$'}(cat "${'$'}stage/result")
            exit "${'$'}result"
        """.trimIndent()
        runCatching {
            val child = ProcessBuilder("su", "-c", command).redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                .redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start()
            if (!child.waitFor(160, TimeUnit.SECONDS)) { child.destroy(); false } else child.exitValue() == 0
        }.getOrDefault(false)
    }
}
