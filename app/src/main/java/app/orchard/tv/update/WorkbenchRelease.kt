package app.orchard.tv.update

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.json.JSONObject

/** Public APK-only distribution. Never accept a repository or credential from a theme or URL. */
data class WorkbenchRelease(val code: Long, val name: String, val assets: Map<String, Asset>) {
    data class Asset(val url: String, val sha256: String, val size: Long)

    companion object {
        const val REPO = "huhao0208/aerial-workbench"
        const val PAGE = "https://github.com/$REPO/releases/latest"
        const val MANIFEST = "$PAGE/download/update.json"
        const val MAX_APK = 64L * 1024 * 1024

        fun parse(text: String): WorkbenchRelease {
            require(text.toByteArray(Charsets.UTF_8).size <= 16 * 1024)
            val data = JSONObject(text)
            require(data.getInt("schema") == 1 && data.getString("package") == "app.hzf.aerial")
            val code = data.getLong("versionCode"); require(code in 1..Int.MAX_VALUE.toLong())
            val name = data.getString("versionName")
            require(name.matches(Regex("[0-9]+\\.[0-9]+-wb\\.[0-9]+")))
            val entries = data.getJSONObject("assets")
            val assets = listOf("standard", "guard").associateWith { flavor ->
                val item = entries.getJSONObject(flavor)
                val size = item.getLong("size"); require(size in 1..MAX_APK)
                val hash = item.getString("sha256"); require(hash.matches(Regex("[a-f0-9]{64}")))
                val url = item.getString("url"); val uri = URI(url)
                require(uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.userInfo == null && uri.query == null && uri.fragment == null)
                require(uri.rawPath.matches(Regex("/$REPO/releases/download/[A-Za-z0-9._-]+/Aerial-Workbench-[A-Za-z0-9._-]+-$flavor\\.apk")))
                Asset(url, hash, size)
            }
            return WorkbenchRelease(code, name, assets)
        }
    }
}

/** Follow only GitHub's HTTPS download redirects, with bounded response size at the caller. */
internal object ReleaseHttp {
    fun open(url: String): HttpURLConnection {
        var next = url
        repeat(6) {
            val uri = URI(next)
            require(uri.scheme == "https" && uri.userInfo == null && uri.port == -1)
            require(uri.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com"))
            val c = URL(next).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.useCaches = false
            c.connectTimeout = 10_000; c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "Aerial-Workbench")
            val status = try { c.responseCode } catch (failure: Exception) { c.disconnect(); throw failure }
            if (status == 200) return c
            val location = c.getHeaderField("Location")
            c.disconnect()
            if (status !in listOf(301, 302, 303, 307, 308) || location == null) error("HTTP $status")
            next = uri.resolve(location).toString()
        }
        error("Too many redirects")
    }

    fun manifest(): String {
        val c = open(WorkbenchRelease.MANIFEST)
        return try {
            c.inputStream.use { input ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    require(bytes.size() + n <= 16 * 1024)
                    bytes.write(buffer, 0, n)
                }
                bytes.toString("UTF-8")
            }
        } finally { c.disconnect() }
    }
}
