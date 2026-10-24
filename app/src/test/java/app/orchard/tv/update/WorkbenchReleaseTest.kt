package app.orchard.tv.update

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class WorkbenchReleaseTest {
    private fun manifest() = """{"schema":1,"package":"app.hzf.aerial","versionCode":50,"versionName":"1.41-wb.6","assets":{"standard":{"url":"https://github.com/huhao0208/aerial-workbench/releases/download/v1.41-wb.6/Aerial-Workbench-1.41-wb.6-standard.apk","size":2900000,"sha256":"${"a".repeat(64)}"},"guard":{"url":"https://github.com/huhao0208/aerial-workbench/releases/download/v1.41-wb.6/Aerial-Workbench-1.41-wb.6-guard.apk","size":2900000,"sha256":"${"b".repeat(64)}"}}}"""
    @Test fun validReleaseSelectsExactFlavor() {
        val release = WorkbenchRelease.parse(manifest())
        assertEquals(50L, release.code)
        assertTrue(release.assets.getValue("guard").url.endsWith("-guard.apk"))
    }
    @Test fun foreignHostOrRepositoryCannotSupplyCode() {
        for (data in listOf(manifest().replace("github.com", "example.com"), manifest().replace("huhao0208/", "other/"), manifest().replace("https:", "http:"), manifest().replace("huhao0208/aerial-workbench/", "huhao0208/aerial-workbench-releases/")))
            assertThrows(IllegalArgumentException::class.java) { WorkbenchRelease.parse(data) }
    }
    @Test fun otherPackageOrInvalidHashIsRejected() {
        for (data in listOf(manifest().replace("app.hzf.aerial", "app.orchard.tv"), manifest().replace("a".repeat(64), "not-a-hash")))
            assertThrows(IllegalArgumentException::class.java) { WorkbenchRelease.parse(data) }
    }
    @Test fun oversizedApkAndManifestAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { WorkbenchRelease.parse(manifest().replace("2900000", "999999999")) }
        assertThrows(IllegalArgumentException::class.java) { WorkbenchRelease.parse(" ".repeat(16385)) }
    }
}
