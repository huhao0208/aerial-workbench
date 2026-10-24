package app.orchard.tv.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.orchard.tv.R
import app.orchard.tv.settings.AerialSettings
import org.json.JSONObject

/** Parsed once at startup/selection. Rendering only reads immutable values. */
object ThemeStore {
    data class Preset(val id: String, @param:StringRes val title: Int, val definition: ThemeDefinition)
    val presets = listOf(
        Preset("aerial", R.string.theme_original, ThemeDefinition()),
        Preset("warm", R.string.theme_warm, ThemeDefinition("Warm", ThemeDefinition.defaultColors + mapOf(
            "focus" to 0xfff2d6ae.toInt(), "on_focus" to 0xff2a2118.toInt(), "app_focus" to 0xfff2d6ae.toInt(), "clock" to 0xffffebcd.toInt(), "glass" to 0xe6272322.toInt(),
        ))),
        Preset("forest", R.string.theme_forest, ThemeDefinition("Forest", ThemeDefinition.defaultColors + mapOf(
            "focus" to 0xffd0e1ca.toInt(), "on_focus" to 0xff1b281c.toInt(), "app_focus" to 0xffd0e1ca.toInt(), "clock" to 0xffe1eddb.toInt(), "glass" to 0xe61e2824.toInt(),
        ))),
    )
    private lateinit var prefs: SharedPreferences
    var current by mutableStateOf(ThemeDefinition()); private set
    var activeId by mutableStateOf("aerial"); private set
    var custom by mutableStateOf<ThemeDefinition?>(null); private set
    var revision by mutableStateOf(0L); private set
    var canUndo by mutableStateOf(false); private set

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val loaded = runCatching { prefs.getString("theme_json", null)?.let(ThemeDefinition::parse) }.getOrNull()
        current = loaded ?: ThemeDefinition()
        activeId = if (loaded == null) "aerial" else prefs.getString("theme_id", "aerial").orEmpty()
            .takeIf { it == "custom" || presets.any { p -> p.id == it } } ?: "aerial"
        custom = runCatching { prefs.getString("custom_theme_json", null)?.let(ThemeDefinition::parse) }.getOrNull()
        revision = prefs.getLong("theme_revision", 0)
        canUndo = validUndo() != null
    }

    fun select(id: String, expected: Long = revision) {
        val definition = presets.firstOrNull { it.id == id }?.definition ?: custom?.takeIf { id == "custom" }
            ?: throw ThemeIssue("id", "theme_invalid")
        save(definition, id, expected)
    }

    fun import(raw: String, expected: Long) = save(ThemeDefinition.parse(raw), "custom", expected)

    fun undo(expected: Long = revision) {
        val snapshot = validUndo() ?: throw ThemeIssue("undo", "theme_invalid")
        val theme = ThemeDefinition.parse(snapshot.getJSONObject("theme").toString())
        val layout = snapshot.getJSONObject("layout")
        val restored = ThemeDefinition.layoutKeys.associateWith { layout.getBoolean(it) }
        save(theme, snapshot.getString("id"), expected, restored)
    }

    private fun save(definition: ThemeDefinition, id: String, expected: Long, restore: Map<String, Boolean>? = null) {
        if (expected != revision) throw ThemeIssue("revision", "theme_stale")
        val next = definition.validate()
        val old = JSONObject().put("id", activeId).put("theme", current.json())
            .put("layout", JSONObject().apply { homeValues().forEach { (key, value) -> put(key, value) } })
        val keys = ThemeDefinition.layoutKeys + setOf("theme_undo", "theme_json", "theme_id", "theme_revision", "custom_theme_json")
        val before = keys.associateWith { prefs.all[it] }
        val editor = prefs.edit().putString("theme_undo", old.toString()).putString("theme_json", next.json().toString())
            .putString("theme_id", id).putLong("theme_revision", revision + 1)
        if (id == "custom") editor.putString("custom_theme_json", next.json().toString())
        (restore ?: next.layout).forEach { (key, value) -> editor.putBoolean(key, value) }
        if (!editor.commit()) {
            // SharedPreferences updates its in-memory map before a disk failure is reported.
            // Restore just the theme-owned keys; PIN and other preferences are never read here.
            prefs.edit().apply {
                before.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Long -> putLong(key, value)
                    else -> remove(key)
                } }
            }.apply()
            throw ThemeIssue("storage", "theme_storage")
        }
        current = next
        activeId = id
        if (id == "custom") custom = next
        revision++
        canUndo = true
        AerialSettings.reloadHomeSettings(prefs)
    }

    /** TV layout controls share the revision, so a phone draft cannot silently overwrite them. */
    fun chooseHome(key: String, value: Boolean) {
        require(key in ThemeDefinition.layoutKeys)
        if (homeValues()[key] == value) return
        val before = prefs.all[key]
        if (!prefs.edit().putBoolean(key, value).putLong("theme_revision", revision + 1).commit()) {
            prefs.edit().apply {
                if (before is Boolean) putBoolean(key, before) else remove(key)
                putLong("theme_revision", revision)
            }.apply()
            android.util.Log.w("AerialTheme", "Home setting was not saved")
            return
        }
        revision++
        AerialSettings.reloadHomeSettings(prefs)
    }

    private fun validUndo(): JSONObject? = runCatching {
        val raw = prefs.getString("theme_undo", null) ?: return null
        if (raw.toByteArray().size > ThemeDefinition.MAX_BYTES * 2) return null
        val obj = JSONObject(raw)
        ThemeDefinition.parse(obj.getJSONObject("theme").toString())
        check(obj.getString("id") == "custom" || presets.any { it.id == obj.getString("id") })
        val layout = obj.getJSONObject("layout")
        check(layout.keys().asSequence().toSet() == ThemeDefinition.layoutKeys)
        ThemeDefinition.layoutKeys.forEach { check(layout.get(it) is Boolean) }
        obj
    }.getOrNull()

    fun homeValues(): Map<String, Boolean> = mapOf(
        "one_row_home" to AerialSettings.oneRowHome, "peek_next_row" to AerialSettings.peekNextRow,
        "home_plate" to AerialSettings.homePlate, "show_all_names" to AerialSettings.showAllNames,
        "clock_right" to AerialSettings.clockRight, "large_clock" to AerialSettings.largeClock,
    )

    fun state(context: Context): JSONObject = JSONObject().put("id", activeId).put("revision", revision)
        .put("definition", current.json()).put("layout", JSONObject(homeValues())).put("canUndo", canUndo)
        .put("presets", org.json.JSONArray().apply {
            presets.forEach { put(JSONObject().put("id", it.id).put("name", context.getString(it.title))) }
            custom?.let { put(JSONObject().put("id", "custom").put("name", it.name)) }
        })
}
