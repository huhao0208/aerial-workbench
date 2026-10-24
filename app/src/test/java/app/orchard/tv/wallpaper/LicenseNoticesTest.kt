package app.orchard.tv.wallpaper

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LicenseNoticesTest {
    private fun server(): PhoneServer {
        val constructor = PhoneServer::class.java.getDeclaredConstructor(Context::class.java, String::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(RuntimeEnvironment.getApplication(), "license-test")
    }
    private fun session(uri: String): NanoHTTPD.IHTTPSession = Proxy.newProxyInstance(
        NanoHTTPD.IHTTPSession::class.java.classLoader, arrayOf(NanoHTTPD.IHTTPSession::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "getUri" -> uri
            "getMethod" -> NanoHTTPD.Method.GET
            else -> null
        }
    } as NanoHTTPD.IHTTPSession

    @Test fun licensedTextIsIncludedAndReadableFromAuthenticatedPhonePage() {
        val response = server().serve(session("/license-test/licenses"))
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        val body = response.data.bufferedReader().use { it.readText() }
        for (text in listOf("Copyright (c) 2026 pengchujin", "The Inter Project Authors", "GeoNames",
            "Classpath", "nanohttpd", "com.android.tools:desugar_jdk_libs:2.1.5")) assertTrue(text, body.contains(text))
    }
    @Test fun licenseRouteDoesNotBypassExistingTokenBoundary() {
        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, server().serve(session("/licenses")).status)
        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, server().serve(session("/wrong-token/licenses")).status)
    }
}
