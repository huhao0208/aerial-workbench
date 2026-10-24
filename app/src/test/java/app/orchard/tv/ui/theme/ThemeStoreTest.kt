package app.orchard.tv.ui.theme

import android.content.Context
import org.robolectric.RuntimeEnvironment
import app.orchard.tv.settings.AerialSettings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ThemeStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        val prefs = context.getSharedPreferences("settings",0)
        prefs.edit().clear().putBoolean("one_row_home",true).putBoolean("large_clock",false).commit()
        AerialSettings.reloadHomeSettings(prefs)
        ThemeStore.init(context)
    }
    @Test fun atomicThemeLayoutAndUndoSurviveReload() {
        val theme=ThemeDefinition("My theme",layout=mapOf("large_clock" to true,"one_row_home" to false))
        ThemeStore.import(theme.json().toString(),0)
        assertTrue(AerialSettings.largeClock); assertFalse(AerialSettings.oneRowHome)
        ThemeStore.init(context); assertEquals("My theme",ThemeStore.current.name)
        ThemeStore.undo(1)
        assertFalse(AerialSettings.largeClock); assertTrue(AerialSettings.oneRowHome)
        assertEquals("aerial",ThemeStore.activeId)
    }
    @Test fun failedImportAndStaleWriteKeepThemeAndLayout() {
        ThemeStore.select("warm",0)
        assertThrows(ThemeIssue::class.java) { ThemeStore.select("forest",0) }
        assertThrows(ThemeIssue::class.java) { ThemeStore.import("{}",1) }
        assertEquals("warm",ThemeStore.activeId); assertEquals(1L,ThemeStore.revision)
        assertTrue(AerialSettings.oneRowHome)
        ThemeStore.init(context); assertEquals("warm",ThemeStore.activeId)
    }
    @Test fun switchingPresetsKeepsTheSavedCustomTheme() {
        ThemeStore.import(ThemeDefinition("Custom").json().toString(),0)
        ThemeStore.select("forest",1); ThemeStore.init(context)
        ThemeStore.select("custom",2); assertEquals("Custom",ThemeStore.current.name)
    }
    @Test fun damagedThemeFallsBackWithoutRemovingTheSourceOrOtherPreferences() {
        val prefs = context.getSharedPreferences("settings", 0)
        prefs.edit().putString("theme_json", "{damaged")
            .putString("theme_id", "custom").putString("custom_theme_json", "invalid")
            .putString("theme_undo", "invalid").putString("parental_test_sentinel", "keep").commit()
        ThemeStore.init(context)
        assertEquals(ThemeDefinition(), ThemeStore.current)
        assertEquals("aerial", ThemeStore.activeId)
        assertNull(ThemeStore.custom)
        assertFalse(ThemeStore.canUndo)
        assertEquals("{damaged", prefs.getString("theme_json", null))
        assertEquals("keep", prefs.getString("parental_test_sentinel", null))
    }

    @Test fun tvLayoutChangeInvalidatesPhoneDraftAndSurvivesReload() {
        AerialSettings.chooseLargeClock(true)
        assertTrue(AerialSettings.largeClock)
        assertEquals(1L, ThemeStore.revision)
        assertThrows(ThemeIssue::class.java) { ThemeStore.import(ThemeDefinition().json().toString(), 0) }
        ThemeStore.init(context)
        assertTrue(ThemeStore.state(context).getJSONObject("layout").getBoolean("large_clock"))
        assertEquals(1L, ThemeStore.revision)
        AerialSettings.chooseLargeClock(true)
        assertEquals(1L, ThemeStore.revision)
    }

}
