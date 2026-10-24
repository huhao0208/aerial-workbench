package app.orchard.tv.ui.theme

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.StringReader
import kotlin.math.pow

/** A data-only skin. It cannot name files, launch components, or execute code. */
data class ThemeDefinition(
    val name: String = "Aerial",
    val colors: Map<String, Int> = defaultColors,
    val clockScale: Float = 1f,
    val panelRoundness: Float = 1f,
    val layout: Map<String, Boolean> = emptyMap(),
) {
    fun color(role: String): Int = colors.getValue(role)

    fun json(): JSONObject = JSONObject().put("version", 1).put("name", name)
        .put("colors", JSONObject().apply { colors.forEach { (k, v) -> put(k, "#%08X".format(v)) } })
        .put("metrics", JSONObject().put("clock_scale", clockScale.toDouble()).put("panel_roundness", panelRoundness.toDouble()))
        .put("layout", JSONObject().apply { layout.forEach { (k, v) -> put(k, v) } })

    fun validate(): ThemeDefinition {
        requireTheme(name.trim().isNotEmpty() && name.codePointCount(0, name.length) <= 32 && name.none { it.isISOControl() }, "name")
        requireTheme(colors.keys == defaultColors.keys, "colors")
        for (role in listOf("focus", "on_focus", "text", "app_focus", "clock")) requireTheme(colors.getValue(role) ushr 24 == 255, role)
        requireTheme(color("glass") ushr 24 >= 230, "glass")
        requireTheme(clockScale.isFinite() && clockScale in .85f..1f, "clock_scale")
        requireTheme(panelRoundness.isFinite() && panelRoundness in .75f..1.15f, "panel_roundness")
        requireTheme(layout.keys.all { it in layoutKeys }, "layout")
        requireTheme(contrast(color("focus"), color("on_focus")) >= 4.5, "focus", "theme_contrast")
        // The image behind a panel may be black or white. Both extremes must remain readable.
        for (backdrop in listOf(0xff000000.toInt(), 0xffffffff.toInt())) {
            val surface = composite(color("glass"), backdrop)
            for (role in listOf("text", "secondary")) requireTheme(contrast(composite(color(role), surface), surface) >= 4.5, role, "theme_contrast")
        }
        requireTheme(contrast(color("clock"), 0xff202022.toInt()) >= 4.5, "clock", "theme_contrast")
        return copy(name = name.trim(), colors = colors.toMap(), layout = layout.toMap())
    }

    companion object {
        const val MAX_BYTES = 16 * 1024
        val defaultColors = linkedMapOf(
            "focus" to 0xfff5f5f7.toInt(), "on_focus" to 0xff1d1d1f.toInt(),
            "text" to 0xffffffff.toInt(), "secondary" to 0x99ebebf5.toInt(),
            "glass" to 0xe6202022.toInt(), "app_focus" to 0xffffffff.toInt(), "clock" to 0xffffffff.toInt(),
        )
        val layoutKeys = setOf("one_row_home", "peek_next_row", "home_plate", "show_all_names", "clock_right", "large_clock")

        fun parse(raw: String): ThemeDefinition {
            requireTheme(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES, "file")
            val root = try {
                JsonReader(StringReader(raw)).use { reader ->
                    reader.isLenient = false
                    val value = readObject(reader, 0)
                    requireTheme(reader.peek() == JsonToken.END_DOCUMENT, "file")
                    value
                }
            } catch (e: ThemeIssue) { throw e } catch (_: Exception) { throw ThemeIssue("file", "theme_invalid") }
            keys(root, setOf("version", "name", "colors", "metrics", "layout"))
            requireTheme((root.opt("version") as? Number)?.toDouble() == 1.0, "version")
            val name = root.opt("name") as? String ?: throw ThemeIssue("name", "theme_invalid")
            val colors = defaultColors.toMutableMap()
            if (root.has("colors")) {
                val c = root.opt("colors") as? JSONObject ?: throw ThemeIssue("colors", "theme_invalid")
                keys(c, defaultColors.keys)
                c.keys().forEach { role ->
                    val hex = c.opt(role) as? String ?: throw ThemeIssue(role, "theme_invalid")
                    requireTheme(Regex("^#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$").matches(hex), role)
                    val number = hex.substring(1).toLong(16).toInt()
                    colors[role] = if (hex.length == 7) number or 0xff000000.toInt() else number
                }
            }
            var clock = 1f
            var roundness = 1f
            if (root.has("metrics")) {
                val m = root.opt("metrics") as? JSONObject ?: throw ThemeIssue("metrics", "theme_invalid")
                keys(m, setOf("clock_scale", "panel_roundness"))
                if (m.has("clock_scale")) clock = (m.opt("clock_scale") as? Number)?.toFloat() ?: throw ThemeIssue("clock_scale", "theme_invalid")
                if (m.has("panel_roundness")) roundness = (m.opt("panel_roundness") as? Number)?.toFloat() ?: throw ThemeIssue("panel_roundness", "theme_invalid")
            }
            val layout = linkedMapOf<String, Boolean>()
            if (root.has("layout")) {
                val l = root.opt("layout") as? JSONObject ?: throw ThemeIssue("layout", "theme_invalid")
                keys(l, layoutKeys)
                l.keys().forEach { k -> layout[k] = l.opt(k) as? Boolean ?: throw ThemeIssue(k, "theme_invalid") }
            }
            return ThemeDefinition(name, colors, clock, roundness, layout).validate()
        }

        private fun keys(o: JSONObject, allowed: Set<String>) {
            requireTheme(o.keys().asSequence().all { it in allowed }, "fields")
        }

        /** Reject duplicate members, arrays, null, trailing data, and excessive nesting. */
        private fun readObject(r: JsonReader, depth: Int): JSONObject {
            requireTheme(depth <= 3, "file")
            r.beginObject()
            val seen = hashSetOf<String>()
            val result = JSONObject()
            while (r.hasNext()) {
                val key = r.nextName()
                requireTheme(seen.add(key) && seen.size <= 16, "fields")
                val value: Any = when (r.peek()) {
                    JsonToken.BEGIN_OBJECT -> readObject(r, depth + 1)
                    JsonToken.STRING -> r.nextString()
                    JsonToken.BOOLEAN -> r.nextBoolean()
                    JsonToken.NUMBER -> r.nextDouble().also { requireTheme(it.isFinite(), key) }
                    else -> throw ThemeIssue(key, "theme_invalid")
                }
                result.put(key, value)
            }
            r.endObject()
            return result
        }

        private fun composite(fg: Int, bg: Int): Int {
            val alpha = (fg ushr 24) / 255.0
            fun channel(shift: Int) = ((((fg ushr shift) and 255) * alpha + ((bg ushr shift) and 255) * (1 - alpha)).toInt() shl shift)
            return 0xff000000.toInt() or channel(16) or channel(8) or channel(0)
        }
        private fun luminance(c: Int): Double {
            fun channel(shift: Int): Double {
                val v = ((c ushr shift) and 255) / 255.0
                return if (v <= .04045) v / 12.92 else ((v + .055) / 1.055).pow(2.4)
            }
            return channel(16) * .2126 + channel(8) * .7152 + channel(0) * .0722
        }
        private fun contrast(a: Int, b: Int): Double {
            val x = luminance(a); val y = luminance(b)
            return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
        }
    }
}

class ThemeIssue(val field: String, val code: String) : IllegalArgumentException(code)
private fun requireTheme(ok: Boolean, field: String, code: String = "theme_invalid") {
    if (!ok) throw ThemeIssue(field, code)
}
