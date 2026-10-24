package app.orchard.tv.ui.theme

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ThemeDefinitionTest {
    private fun rejected(raw: String) { assertThrows(ThemeIssue::class.java) { ThemeDefinition.parse(raw) } }
    @Test fun originalAndPresetValuesValidateAndRoundTrip() {
        ThemeStore.presets.forEach { assertEquals(it.definition.validate(), ThemeDefinition.parse(it.definition.json().toString())) }
        assertEquals(0xfff5f5f7.toInt(), ThemeDefinition().color("focus"))
        assertEquals(0x99ebebf5.toInt(), ThemeDefinition().color("secondary"))
    }
    @Test fun rejectsInvalidFieldsAndNonDataValues() {
        for (raw in listOf(
            "{\"version\":1,\"version\":1,\"name\":\"x\"}",
            "{\"version\":1,\"name\":\"x\",\"script\":\"anything\"}",
            "{\"version\":1,\"name\":\"x\",\"colors\":{\"focus\":\"file:///a\"}}",
            "{\"version\":1,\"name\":\"x\",\"layout\":{\"one_row_home\":\"false\"}}",
            "{\"version\":1,\"name\":\"x\",\"metrics\":{\"clock_scale\":1.2}}",
            "{\"version\":1,\"name\":\"x\",\"metrics\":{\"panel_roundness\":999}}",
            "{\"version\":1,\"name\":\"x\"}{}", "[]", "null",
            "{\"version\":1.1,\"name\":\"x\"}",
        )) rejected(raw)
        rejected(" ".repeat(ThemeDefinition.MAX_BYTES+1))
    }
    @Test fun rejectsInvisibleTextAndTransparentPrimaryColors() {
        rejected("""{"version":1,"name":"x","colors":{"focus":"#FFFFFF","on_focus":"#FFFFFF"}}""")
        rejected("""{"version":1,"name":"x","colors":{"text":"#102030"}}""")
        rejected("""{"version":1,"name":"x","colors":{"text":"#00FFFFFF"}}""")
        rejected("""{"version":1,"name":"x","colors":{"glass":"#00202022"}}""")
    }
}
