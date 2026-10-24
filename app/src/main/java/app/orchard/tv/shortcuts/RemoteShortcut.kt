package app.orchard.tv.shortcuts

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import app.orchard.tv.BuildConfig
import app.orchard.tv.R
import app.orchard.tv.apps.AppCatalog
import app.orchard.tv.apps.AppEntry
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

class ShortcutFailure(val reason: String) : IllegalArgumentException(reason)

/** One explicit hardware-key binding, stored separately from themes and app arrangements. */
object RemoteShortcut {
    var version by mutableIntStateOf(0); private set
    const val BRIDGE = "com.tcl.tshop"
    private fun prefs(context: Context) = context.getSharedPreferences("remote_shortcuts", Context.MODE_PRIVATE)
    fun revision(context: Context) = prefs(context).getLong("revision", 0)
    fun selected(context: Context) = prefs(context).getString("app", "").orEmpty()
    fun candidates() = AppCatalog.loaded.filter { it.source == null && it.folder == null && it.shortcut == null && it.packageName != BRIDGE }
        .sortedBy { it.label }

    fun bridgeReady(context: Context) = BuildConfig.WORKBENCH && runCatching {
        val c = ComponentName(BRIDGE, "com.tcl.tshop.activitys.WelcomeActivity")
        val info = context.packageManager.getActivityInfo(c, 0)
        info.enabled && info.exported && context.packageManager.checkSignatures(context.packageName, BRIDGE) == PackageManager.SIGNATURE_MATCH
    }.getOrDefault(false)

    fun label(context: Context): String {
        val app = selected(context)
        if (app.isEmpty()) return context.getString(R.string.shortcut_none)
        return runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(app, 0)).toString() }
            .getOrElse { context.getString(R.string.shortcut_missing) }
    }

    @Synchronized fun select(context: Context, key: String, expected: Long = revision(context)) {
        if (!BuildConfig.WORKBENCH || (key.isNotEmpty() && !bridgeReady(context))) throw ShortcutFailure("bridge")
        if (expected != revision(context)) throw ShortcutFailure("stale")
        val app: AppEntry? = if (key.isEmpty()) null else (candidates().firstOrNull { it.key == key }
            ?: throw ShortcutFailure("app"))
        if (app != null) {
            val info = runCatching { context.packageManager.getActivityInfo(app.component, 0) }.getOrNull()
            if (info == null || !info.enabled || !info.exported || info.packageName != app.packageName) throw ShortcutFailure("app")
        }
        val p = prefs(context); val before = p.all
        if (!p.edit().putString("app", key)
                .putString("component", app?.component?.flattenToString().orEmpty())
                .putBoolean("leanback", app?.leanback ?: false).putLong("revision", expected + 1).commit()) {
            val restore = p.edit()
            for (name in listOf("app", "component", "leanback", "revision")) {
                restore.remove(name)
                when (val value = before[name]) {
                    is String -> restore.putString(name, value)
                    is Boolean -> restore.putBoolean(name, value)
                    is Long -> restore.putLong(name, value)
                }
            }
            restore.commit()
            throw ShortcutFailure("storage")
        }
        version++
    }

    @Synchronized fun state(context: Context) = JSONObject().put("revision", revision(context)).put("app", selected(context))
        .put("label", label(context)).put("ready", bridgeReady(context))
        .put("apps", org.json.JSONArray(candidates().map { JSONObject().put("key", it.key).put("label", it.label) }))

    fun launch(context: Context) {
        if (!BuildConfig.WORKBENCH || !bridgeReady(context)) return
        val p = prefs(context); val key = selected(context)
        if (key.isEmpty()) return
        val component = ComponentName.unflattenFromString(p.getString("component", "").orEmpty())
        val explicit = component?.takeIf { it.packageName == key }?.let { c ->
            runCatching { context.packageManager.getActivityInfo(c, 0) }.getOrNull()?.takeIf { it.enabled && it.exported }?.let {
                Intent(Intent.ACTION_MAIN).setComponent(c).addCategory(if (p.getBoolean("leanback", false)) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            }
        }
        val target = explicit ?: context.packageManager.getLeanbackLaunchIntentForPackage(key)
            ?: context.packageManager.getLaunchIntentForPackage(key)
        try {
            if (target == null) throw android.content.ActivityNotFoundException()
            context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        } catch (_: Exception) { Toast.makeText(context, R.string.shortcut_missing, Toast.LENGTH_LONG).show() }
    }
}

/** NoDisplay trampoline also works when Home is behind a video app. No new HOME/guard behavior. */
class ShoppingShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RemoteShortcut.launch(this)
        finish()
    }
}
