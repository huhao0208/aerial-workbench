package app.orchard.tv.system

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TclRootPictureSessionTest {
    private class Child(response: String, private val onFlush: () -> Unit = {}) : Process() {
        var running = true
        var destroys = 0
        val sent = object : ByteArrayOutputStream() { override fun flush() { onFlush() } }
        private val received = ByteArrayInputStream(response.toByteArray())
        override fun getOutputStream() = sent
        override fun getInputStream() = received
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun isAlive() = running
        override fun waitFor() = 0
        override fun exitValue(): Int = if (running) throw IllegalThreadStateException() else 0
        override fun destroy() { destroys++; running = false }
        fun commands() = sent.toString().trim().lines()
    }

    @Test fun startupReadsOnceOffCallerThreadAndReleasesIdleRoot() = runBlocking {
        val child = Child("WB-BACKLIGHT OK 48 1\n")
        var starts = 0
        var worker = Thread.currentThread()
        val caller = worker
        val session = TclRootPictureSession(startProcess = { starts++; worker = Thread.currentThread(); child })
        val state = session.prepare("fixed-shell")
        assertEquals(TclRootBacklight.Panel(.48f, 1), state)
        assertEquals(state, session.prepare("fixed-shell"))
        assertEquals(1, starts)
        assertNotSame(caller, worker)
        assertEquals(listOf("GET", "EXIT"), child.commands())
        assertEquals(1, child.destroys)
        assertNull(session.request("fixed-shell", "STEP 1"))
        assertEquals(1, starts)
    }

    @Test fun aMenuOpenedDuringPreparationReusesTheSameRootProcess() = runBlocking {
        lateinit var session: TclRootPictureSession
        val child = Child("WB-BACKLIGHT OK 48 1\nWB-BACKLIGHT OK 60 0\n") { session.panelActive(true) }
        var starts = 0
        session = TclRootPictureSession(startProcess = { starts++; child })
        assertEquals(.48f, session.prepare("fixed-shell")!!.brightness)
        assertTrue(child.running)
        assertEquals(TclRootBacklight.Panel(.60f, 0), session.request("fixed-shell", "GET"))
        assertEquals(1, starts)
        session.panelActive(false)
        session.close()
        assertEquals(listOf("GET", "GET", "EXIT"), child.commands())
        assertFalse(child.running)
    }

    @Test fun reopeningMenuReadsExternalChangesInsteadOfTheStartupCache() = runBlocking {
        val children = ArrayDeque(listOf(
            Child("WB-BACKLIGHT OK 20 1\n"),
            Child("WB-BACKLIGHT OK 75 0\n"),
            Child("WB-BACKLIGHT OK 35 1\n"),
        ))
        val started = mutableListOf<Child>()
        val session = TclRootPictureSession(startProcess = { children.removeFirst().also { started += it } })
        assertEquals(TclRootBacklight.Panel(.20f, 1), session.prepare("fixed-shell"))
        session.panelActive(true)
        assertEquals(TclRootBacklight.Panel(.75f, 0), session.request("fixed-shell", "GET"))
        session.panelActive(false)
        session.close()
        session.panelActive(true)
        assertEquals(TclRootBacklight.Panel(.35f, 1), session.request("fixed-shell", "GET"))
        session.panelActive(false)
        session.close()
        assertEquals(3, started.size)
        assertTrue(started.all { it.commands() == listOf("GET", "EXIT") && !it.running })
    }

    @Test fun aFailedLiveReadDoesNotReturnAnOldCachedValue() = runBlocking {
        val children = ArrayDeque(listOf(Child("WB-BACKLIGHT OK 20 1\n"), Child("WB-BACKLIGHT ERR\n")))
        val session = TclRootPictureSession(startProcess = { children.removeFirst() })
        assertNotNull(session.prepare("fixed-shell"))
        session.panelActive(true)
        assertNull(session.request("fixed-shell", "GET"))
        session.panelActive(false)
        session.close()
    }

    @Test fun denialDoesNotLoopOrThrowIntoHome() = runBlocking {
        var starts = 0
        val session = TclRootPictureSession(startProcess = { starts++; throw IOException("denied") })
        assertNull(session.prepare("fixed-shell"))
        assertNull(session.prepare("fixed-shell"))
        session.panelActive(true)
        assertNull(session.request("fixed-shell", "GET"))
        assertEquals(1, starts)
    }

    @Test fun aSilentAuthorizationHasADeadlineAndBackoff() = runBlocking {
        var elapsed = 0L
        var starts = 0
        val child = Child("")
        val session = TclRootPictureSession(
            startProcess = { starts++; child },
            now = { elapsed.also { elapsed += 5_000 } },
        )
        assertNull(session.prepare("fixed-shell"))
        assertFalse(child.running)
        session.panelActive(true)
        assertNull(session.request("fixed-shell", "GET"))
        assertEquals(1, starts)
        assertEquals(listOf("GET", "EXIT"), child.commands())
    }

    @Test fun foregroundKeepsOneConnectionAcrossMenuReopensWithoutPolling() = runBlocking {
        val child = Child("WB-BACKLIGHT OK 40 1\nWB-BACKLIGHT OK 55 0\n")
        var starts = 0
        val session = TclRootPictureSession(startProcess = { starts++; child })
        session.foregroundActive(true)
        session.prepare("fixed-shell")
        session.close()
        kotlinx.coroutines.delay(100)
        assertTrue(child.running)
        assertEquals(listOf("GET"), child.commands())
        session.panelActive(true)
        assertEquals(TclRootBacklight.Panel(.55f, 0), session.request("fixed-shell", "GET"))
        session.panelActive(false)
        session.close()
        assertTrue(child.running)
        session.foregroundActive(false)
        session.close()
        assertEquals(1, starts)
        assertFalse(child.running)
        assertEquals(listOf("GET", "GET", "EXIT"), child.commands())
    }

    @Test fun returningHomePreparesANewConnectionBeforeTheMenu() = runBlocking {
        val first = Child("WB-BACKLIGHT OK 40 1\n")
        val second = Child("WB-BACKLIGHT OK 75 0\n")
        val children = ArrayDeque(listOf(first, second))
        val session = TclRootPictureSession(startProcess = { children.removeFirst() })
        session.foregroundActive(true)
        session.prepare("fixed-shell")
        session.foregroundActive(false)
        session.close()
        assertFalse(first.running)
        session.foregroundActive(true)
        assertEquals(TclRootBacklight.Panel(.75f, 0), session.prepare("fixed-shell"))
        assertTrue(second.running)
        session.foregroundActive(false)
        session.close()
        assertFalse(second.running)
    }

    @Test fun cancellationCleansUpBeforeItPropagates() = runBlocking {
        val child = Child("") { throw CancellationException("Home destroyed") }
        val session = TclRootPictureSession(startProcess = { child })
        try {
            session.prepare("fixed-shell")
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertFalse(child.running)
            assertEquals(1, child.destroys)
        }
    }
}
