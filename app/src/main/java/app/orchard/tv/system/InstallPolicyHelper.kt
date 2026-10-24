package app.orchard.tv.system

import android.content.Context
import app.orchard.tv.settings.AerialSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Persists the owner's adb-install choice in TCL's InstallConfig, the same known-policy set the
 * firmware templates and tcl-self-update.sh use — never a verifier or SELinux change. With
 * `home_adb`, adb install and other HOMEs are allowed and APK signature checks stay on. The
 * vendor cloud may push the policy back; a boot script under /data/adb/service.d reapplies it.
 */
object InstallPolicyHelper {
    const val SCRIPT = "/data/adb/service.d/26-aerial-adb-install.sh"
    private const val URI = "content://com.tcl.providers.config/InstallConfig"

    // Byte-identical to the normal / home_adb rows in app/src/main/assets/tcl-self-update.sh.
    private const val NORMAL = """[{"enable":"true","strategies":[{"name":"safeStrategy","enable":"true","priority":"0"},{"name":"blackListStrategy","enable":"true","packages":[],"priority":"1"},{"name":"thridStrategy","enable":"false","packages":["com.dangbeimarket","com.shafa.market","com.ant.store.appstore"],"priority":"2"},{"name":"launcherStrategy","enable":"true","packages":[],"priority":"3"},{"name":"overDueStrategy","enable":"true","packages":[],"priority":"4"},{"name":"pmStrategy","enable":"true","priority":"5"}]}]"""
    private const val HOME_ADB = """[{"enable":"true","strategies":[{"name":"safeStrategy","enable":"true","priority":"0"},{"name":"blackListStrategy","enable":"true","packages":[],"priority":"1"},{"name":"thridStrategy","enable":"false","packages":["com.dangbeimarket","com.shafa.market","com.ant.store.appstore"],"priority":"2"},{"name":"launcherStrategy","enable":"false","packages":[],"priority":"3"},{"name":"overDueStrategy","enable":"true","packages":[],"priority":"4"},{"name":"pmStrategy","enable":"false","priority":"5"}]}]"""

    /** pmStrategy as the chosen policy carries it: false when adb installs are allowed. */
    fun policy(allow: Boolean) = if (allow) HOME_ADB else NORMAL

    /**
     * The shell applied over su: write the chosen policy, read it back, then persist or remove the
     * boot script. Constants only — no user input reaches this text.
     */
    fun script(allow: Boolean): String {
        val wanted = policy(allow).replace(":", "\\:")
        val pmEnable = if (allow) "false" else "true"
        val persist = if (allow)
            "mkdir -p /data/adb/service.d\n" +
            "cat > $SCRIPT <<'EOF'\n#!/system/bin/sh\ncontent update --uri $URI --bind \"config_content:s:$wanted\" --where \"project_id='InstallConfig'\"\n" +
            "EOF\nchmod 0755 $SCRIPT\n"
        else
            "rm -f $SCRIPT\n"
        return """
            content update --uri $URI --bind "config_content:s:$wanted" --where "project_id='InstallConfig'" || exit 1
            got=${'$'}(content query --uri $URI --projection config_content --where "project_id='InstallConfig'")
            case "${'$'}got" in *'"pmStrategy","enable":"$pmEnable"'*) ;; *) exit 4;; esac
            ${persist.trimEnd('\n')}
            exit 0
        """.trimIndent() + "\n"
    }

    /** Applies the choice over su; the preference is only committed when the read-back matched. */
    fun apply(context: Context, allow: Boolean, onDone: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        val scriptText = script(allow)
        CoroutineScope(Dispatchers.IO).launch {
            val exit = runCatching {
                val child = ProcessBuilder("su", "-c", scriptText).start()
                child.outputStream.close()
                if (child.waitFor(30, TimeUnit.SECONDS)) child.exitValue() else { child.destroy(); -1 }
            }.getOrDefault(-1)
            val ok = exit == 0
            withContext(Dispatchers.Main) {
                if (ok) AerialSettings.chooseAdbInstallAllowed(allow)
                onDone(ok)
            }
        }
    }
}
