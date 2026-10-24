package app.orchard.tv.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import app.orchard.tv.BuildConfig
import android.os.Build
import android.util.Log
import androidx.core.content.getSystemService
import app.orchard.tv.apps.AppCatalog
import app.orchard.tv.apps.CustomIcons
import app.orchard.tv.apps.SourceLooks
import app.orchard.tv.apps.TileArtFactory
import app.orchard.tv.ui.theme.ThemeStore
import app.orchard.tv.ui.theme.ThemeDefinition
import app.orchard.tv.ui.theme.ThemeIssue
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.Diagnostics
import app.orchard.tv.system.currentNetwork
import app.orchard.tv.settings.FocusStyle
import app.orchard.tv.weather.Weather
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.security.SecureRandom

/**
 * The page a phone opens after scanning the TV's QR code: choose a wallpaper and the screensaver,
 * send photos, give an app's tile a picture of one's own, and name the weather's city. Choosing
 * pictures or typing a place with a remote is slow; with a phone it is not.
 *
 * It listens on the local network only while the wallpaper sheet is open on the TV (and a few
 * minutes after, so an upload can finish), and every address carries a random token shown only in
 * the QR code, so another device on the network cannot use it by guessing.
 */
class PhoneServer private constructor(private val context: Context, private val token: String) : NanoHTTPD(PORT) {

    override fun serve(session: IHTTPSession): Response {
        val prefix = "/$token"
        val uri = session.uri
        if (uri != prefix && !uri.startsWith("$prefix/")) return text(Response.Status.NOT_FOUND, "Not found")
        val path = uri.removePrefix(prefix).ifEmpty { "/" }
        return runCatching {
            when {
                path == "/" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page())
                path == "/api/state" -> json(state())
                session.method == Method.GET && path == "/api/theme/export" -> {
                    val raw = runBlocking(Dispatchers.Main) { ThemeStore.current.copy(layout = ThemeStore.homeValues()).json().toString(2) }
                    text(Response.Status.OK, raw).apply {
                        mimeType = "application/json; charset=utf-8"
                        addHeader("Content-Disposition", "attachment; filename=Aerial-theme.json")
                        addHeader("Cache-Control", "no-store")
                    }
                }
                session.method == Method.POST && path == "/api/theme" -> theme(session)
                session.method == Method.POST && path == "/api/shortcut" -> shortcut(session)
                path == "/api/diagnostics" -> text(Response.Status.OK, Diagnostics.report(context))
                path.startsWith("/thumb/") -> thumb(path.removePrefix("/thumb/"))
                session.method == Method.POST && path == "/api/wallpaper" -> {
                    val id = form(session)["id"].orEmpty()
                    onMain { Wallpapers.select(id) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/screensaver" -> {
                    val mode = ScreensaverMode.of(form(session)["mode"])
                    onMain { Wallpapers.chooseScreensaver(mode) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/focus" -> {
                    val style = FocusStyle.of(form(session)["style"])
                    onMain { AerialSettings.chooseFocusStyle(style) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/delete" -> {
                    val id = form(session)["id"].orEmpty()
                    onMain { Wallpapers.deletePhoto(id) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/upload" -> upload(session)
                path.startsWith("/tile/") -> tile(java.net.URLDecoder.decode(path.removePrefix("/tile/"), "UTF-8"))
                session.method == Method.POST && path == "/api/icon" -> icon(session)
                session.method == Method.POST && path == "/api/source/name" -> {
                    val f = form(session)
                    val source = AppCatalog.pinnedSources.firstOrNull { it.key == f["app"] }?.source
                    // On the main thread before answering, so the state sent back has the new name.
                    if (source != null) runBlocking(Dispatchers.Main) { SourceLooks.rename(source.id, f["name"]) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/icon/reset" -> {
                    val pkg = form(session)["app"].orEmpty()
                    if (installed(pkg)) { CustomIcons.clear(pkg); onMain { CustomIcons.changed() } }
                    json(state())
                }
                session.method == Method.POST && path == "/api/weather/city" -> {
                    val name = form(session)["name"].orEmpty()
                    val found = name.isNotBlank() && runBlocking(Dispatchers.Main) { Weather.choose(name) }
                    json(state().put("found", found))
                }
                session.method == Method.POST && path == "/api/weather/auto" -> {
                    onMain { Weather.useNetworkLocation() }
                    json(state())
                }
                else -> text(Response.Status.NOT_FOUND, "Not found")
            }
        }.getOrElse {
            Log.w(TAG, "request failed", it)
            text(Response.Status.INTERNAL_ERROR, "error")
        }
    }

    private fun page(): String = context.assets.open("web/index.html").bufferedReader().use { it.readText() }
        .replace("{{DEVICE}}", Build.MODEL.replace("_", " "))

    private fun state(): JSONObject {
        val list = JSONArray()
        val selected = Wallpapers.selectedId
        Wallpapers.items.forEach { w ->
            list.put(JSONObject().put("id", w.id).put("kind", w.kind.name.lowercase()).put("title", w.title).put("caption", w.caption ?: ""))
        }
        return JSONObject()
            .put("theme", runBlocking(Dispatchers.Main) { ThemeStore.state(context) })
            .put("shortcuts", app.orchard.tv.shortcuts.RemoteShortcut.state(context))
            .put("selected", selected)
            .put("latestDaily", Wallpapers.LATEST_DAILY)
            .put("rotatePhotos", Wallpapers.ROTATE_PHOTOS)
            .put("screensaver", Wallpapers.screensaver.key)
            .put("screensaverFollows", Wallpapers.screensaverFollows)
            .put("focusStyle", AerialSettings.focusStyle.key)
            .put("wallpapers", list)
            .put("apps", JSONArray().apply {
                // Pinned inputs first: they can also be named here.
                (AppCatalog.pinnedSources + AppCatalog.loaded.sortedBy { it.label }).forEach { a ->
                    // The stamp makes the phone fetch the tile again once its picture changes.
                    // An input's label as Home will show it once it has arranged the new look.
                    val label = a.source?.let { src ->
                        SourceLooks.of(src.id)?.let { it.name ?: it.device?.let { d -> context.getString(d.label) } } ?: src.label
                    } ?: a.label
                    put(JSONObject().put("key", a.key).put("label", label).put("custom", CustomIcons.has(a.key)).put("stamp", CustomIcons.stamp(a.key))
                        .apply { a.source?.let { put("source", true).put("port", it.label).put("name", SourceLooks.of(it.id)?.name ?: "").put("look", SourceLooks.of(it.id).hashCode()) } })
                }
            })
            .put("weather", JSONObject().apply {
                put("place", Weather.place ?: "")
                put("chosen", Weather.chosen)
                Weather.now?.let { put("celsius", it.celsius); put("kind", it.kind.name.lowercase()); put("day", it.day) }
            })
    }

    private fun theme(session: IHTTPSession): Response {
        val length = session.headers["content-length"]?.toLongOrNull()
        if (length == null || length !in 1..(ThemeDefinition.MAX_BYTES * 4L))
            return themeError("file", "theme_invalid", Response.Status.BAD_REQUEST)
        return try {
            // Consume the bounded form before rejecting an origin. Closing NanoHTTPD with an
            // unread request body can reset the socket before the 403 reaches the client.
            val f = form(session)
            val origin = session.headers["origin"]
            if (origin != null && runCatching { java.net.URI(origin).rawAuthority == session.headers["host"] }.getOrDefault(false).not())
                return text(Response.Status.FORBIDDEN, "Forbidden")
            val expected = f["revision"]?.toLongOrNull() ?: throw ThemeIssue("revision", "theme_invalid")
            if (f["action"] == "validate") {
                val parsed = ThemeDefinition.parse(f["definition"].orEmpty())
                return json(JSONObject().put("definition", parsed.json())).apply { addHeader("Cache-Control", "no-store") }
            }
            onMain {
                when (f["action"]) {
                    "select" -> ThemeStore.select(f["id"].orEmpty(), expected)
                    "save" -> ThemeStore.import(f["definition"].orEmpty(), expected)
                    "undo" -> ThemeStore.undo(expected)
                    else -> throw ThemeIssue("action", "theme_invalid")
                }
            }
            json(state()).apply { addHeader("Cache-Control", "no-store") }
        } catch (e: ThemeIssue) {
            themeError(e.field, e.code, if (e.code == "theme_stale") Response.Status.CONFLICT else Response.Status.BAD_REQUEST)
        } catch (_: Exception) {
            themeError("storage", "theme_storage", Response.Status.INTERNAL_ERROR)
        }
    }

    private fun shortcut(session: IHTTPSession): Response {
        val size = session.headers["content-length"]?.toLongOrNull()
        if (size == null || size !in 1..4096) return themeError("shortcut", "invalid", Response.Status.BAD_REQUEST)
        return try {
            val f = form(session)
            val origin = session.headers["origin"]
            if (origin != null && runCatching { java.net.URI(origin).rawAuthority == session.headers["host"] }.getOrDefault(false).not())
                return text(Response.Status.FORBIDDEN, "Forbidden")
            if (f.keys.any { it !in setOf("app", "revision") } || !f.containsKey("app"))
                return themeError("shortcut", "invalid", Response.Status.BAD_REQUEST)
            val revision = f["revision"]?.toLongOrNull() ?: return themeError("shortcut", "invalid", Response.Status.BAD_REQUEST)
            app.orchard.tv.shortcuts.RemoteShortcut.select(context, f.getValue("app"), revision)
            json(state()).apply { addHeader("Cache-Control", "no-store") }
        } catch (e: app.orchard.tv.shortcuts.ShortcutFailure) {
            themeError("shortcut", e.reason, when (e.reason) {
                "stale" -> Response.Status.CONFLICT
                "storage" -> Response.Status.INTERNAL_ERROR
                else -> Response.Status.BAD_REQUEST
            })
        } catch (_: Exception) { themeError("shortcut", "storage", Response.Status.INTERNAL_ERROR) }
    }

    private fun themeError(field: String, error: String, status: Response.Status): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", JSONObject().put("error", error).put("field", field).toString())
            .apply { addHeader("Cache-Control", "no-store") }

    /** An app on the TV or a pinned input: the only tiles a picture may be sent for. */
    private fun installed(pkg: String) =
        (CustomIcons.isValidKey(pkg) && AppCatalog.loaded.any { it.key == pkg }) || AppCatalog.pinnedSources.any { it.key == pkg }

    private fun tile(pkg: String): Response {
        val app = (AppCatalog.pinnedSources + AppCatalog.loaded).firstOrNull { it.key == pkg } ?: return text(Response.Status.NOT_FOUND, "")
        val bmp = TileArtFactory.flatten(app.art)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        val bytes = out.toByteArray()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "max-age=600")
        }
    }

    private fun icon(session: IHTTPSession): Response {
        if (tooLarge(session)) return text(Response.Status.BAD_REQUEST, "too large")
        val files = HashMap<String, String>()
        session.parseBody(files)
        val pkg = session.parameters["app"]?.firstOrNull().orEmpty()
        if (!installed(pkg)) return text(Response.Status.BAD_REQUEST, "unknown app")
        val f = files.values.map(::File).firstOrNull { it.exists() && it.length() > 0 }
            ?: return text(Response.Status.BAD_REQUEST, "no picture")
        val saved = f.inputStream().use { CustomIcons.save(pkg, it, context.cacheDir) }
        if (saved) onMain { CustomIcons.changed() }
        return json(state().put("saved", saved))
    }

    private fun thumb(id: String): Response {
        val bmp = Wallpapers.thumbnail(java.net.URLDecoder.decode(id, "UTF-8")) ?: return text(Response.Status.NOT_FOUND, "")
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        val bytes = out.toByteArray()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "max-age=600")
        }
    }

    private fun upload(session: IHTTPSession): Response {
        if (tooLarge(session)) return text(Response.Status.BAD_REQUEST, "too large")
        val files = HashMap<String, String>()
        session.parseBody(files)
        var added = 0
        // NanoHTTPD stores each part in a temp file; several photos arrive as photo, photo1, photo2...
        files.values.forEach { path ->
            val f = File(path)
            if (f.exists() && f.length() > 0) {
                val id = f.inputStream().use { input -> runBlocking(Dispatchers.Main) { Wallpapers.addPhoto(input) } }
                if (id != null) added++
            }
        }
        return json(state().put("added", added))
    }

    // NanoHTTPD writes the whole body to the cache before we see it; keep a stray or hostile
    // upload from filling the TV's storage (#57).
    private fun tooLarge(session: IHTTPSession): Boolean =
        (session.headers["content-length"]?.toLongOrNull() ?: 0L) > MAX_UPLOAD

    private fun form(session: IHTTPSession): Map<String, String> {
        session.parseBody(HashMap())
        return session.parameters.mapValues { it.value.firstOrNull().orEmpty() }
    }

    private fun onMain(block: () -> Unit) = runBlocking(Dispatchers.Main) { block() }

    private fun json(o: JSONObject) = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString())

    private fun text(status: Response.Status, msg: String) = newFixedLengthResponse(status, "text/plain; charset=utf-8", msg)

    companion object {
        val PORT = if (BuildConfig.WORKBENCH) 8792 else 8790
        private const val TAG = "AerialPhone"
        /** Enough for a batch of phone photos at full size. */
        private const val MAX_UPLOAD = 200L * 1024 * 1024

        @Volatile
        private var running: PhoneServer? = null

        /** Starts the server if needed and returns the address to put in the QR code, or null offline. */
        @Synchronized
        fun start(context: Context): String? {
            val ip = lanAddress(context) ?: return null
            val server = running ?: PhoneServer(context.applicationContext, newToken()).also {
                runCatching { it.start(SOCKET_READ_TIMEOUT, true) }.onFailure { return null }
                running = it
            }
            return "http://$ip:$PORT/${server.token}/"
        }

        @Synchronized
        fun stop() {
            running?.stop()
            running = null
        }

        private fun newToken(): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            return (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        }

        fun lanAddress(context: Context): String? {
            val cm = context.getSystemService<ConnectivityManager>() ?: return null
            val props = cm.getLinkProperties(cm.currentNetwork() ?: return null) ?: return null
            return props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }

        /** A QR code, dark modules on white, each module [scale] pixels square. */
        fun qr(text: String, scale: Int = 8): Bitmap {
            val matrix = QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
            val n = matrix.width
            val bmp = createBitmap(n * scale, n * scale)
            for (y in 0 until n) for (x in 0 until n) {
                if (matrix[x, y]) for (dy in 0 until scale) for (dx in 0 until scale) bmp.setPixel(x * scale + dx, y * scale + dy, Color.BLACK)
                else for (dy in 0 until scale) for (dx in 0 until scale) bmp.setPixel(x * scale + dx, y * scale + dy, Color.WHITE)
            }
            return bmp
        }
    }
}
