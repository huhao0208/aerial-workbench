package app.orchard.tv.shortcuts

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import app.orchard.tv.BuildConfig
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class RemoteShortcutTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("remote_shortcuts", 0)
    @Before fun setup() {
        assumeTrue(BuildConfig.WORKBENCH)
        prefs.edit().clear().putString("app", "test.old").putString("component", "test.old/.Main")
            .putBoolean("leanback", true).putLong("revision", 3).putString("other", "keep").commit()
    }
    @Test fun clearBindingIsAtomicAndPreservesUnrelatedValues() {
        RemoteShortcut.select(context, "", 3)
        assertEquals("", RemoteShortcut.selected(context))
        assertEquals("", prefs.getString("component", null))
        assertEquals(4L, RemoteShortcut.revision(context))
        assertEquals("keep", prefs.getString("other", null))
    }
    @Test fun staleClientCannotOverwriteBinding() {
        assertThrows(ShortcutFailure::class.java) { RemoteShortcut.select(context, "", 2) }
        assertEquals("test.old", RemoteShortcut.selected(context))
        assertEquals(3L, RemoteShortcut.revision(context))
    }
    @Test fun arbitraryTargetCannotBeSaved() {
        assertThrows(ShortcutFailure::class.java) { RemoteShortcut.select(context, "evil.package/.Activity", 3) }
        assertEquals("test.old", RemoteShortcut.selected(context))
        assertEquals(3L, RemoteShortcut.revision(context))
    }
    @Test fun failedCommitRestoresInMemoryBindingAndRevision() {
        val before = prefs.all
        val backing = prefs
        val failing = object : SharedPreferences by backing {
            override fun edit(): SharedPreferences.Editor {
                val real = backing.edit()
                return object : SharedPreferences.Editor by real {
                    override fun putString(key: String, value: String?): SharedPreferences.Editor { real.putString(key, value); return this }
                    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { real.putBoolean(key, value); return this }
                    override fun putLong(key: String, value: Long): SharedPreferences.Editor { real.putLong(key, value); return this }
                    override fun remove(key: String): SharedPreferences.Editor { real.remove(key); return this }
                    override fun commit(): Boolean { real.commit(); return false }
                }
            }
        }
        val wrapped = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = if (name == "remote_shortcuts") failing else super.getSharedPreferences(name, mode)
        }
        assertThrows(ShortcutFailure::class.java) { RemoteShortcut.select(wrapped, "", 3) }
        assertEquals(before, prefs.all)
    }
}
