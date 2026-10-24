package app.orchard.tv.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackgroundCleanerTest {
    private val self = "app.hzf.aerial"

    @Test fun names_only_valid_foreign_packages_sorted_and_deduped() {
        assertEquals(
            "am force-stop com.a.b || true; am force-stop com.x.y || true; true",
            BackgroundCleaner.command(listOf("com.x.y", "com.a.b", "com.a.b"), self),
        )
    }

    @Test fun never_names_aerial_or_non_package_shapes() {
        assertNull(BackgroundCleaner.command(listOf(self), self))
        assertNull(BackgroundCleaner.command(listOf("x y", "a;rm", "-"), self))
        assertEquals(
            "am force-stop ok.app || true; true",
            BackgroundCleaner.command(listOf("ok.app", "a;b", self, "x y"), self),
        )
    }
}
