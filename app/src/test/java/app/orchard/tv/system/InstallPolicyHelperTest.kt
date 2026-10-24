package app.orchard.tv.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallPolicyHelperTest {
    @Test fun adb_choice_carries_pm_and_launcher_strategy() {
        val adb = InstallPolicyHelper.policy(true)
        assertTrue(adb.contains("\"name\":\"pmStrategy\",\"enable\":\"false\""))
        assertTrue(adb.contains("\"name\":\"launcherStrategy\",\"enable\":\"false\""))
        assertTrue(InstallPolicyHelper.policy(false).contains("\"name\":\"pmStrategy\",\"enable\":\"true\""))
    }

    @Test fun script_writes_policy_and_persists_only_when_allowed() {
        val allow = InstallPolicyHelper.script(true)
        assertTrue(allow.contains("\"pmStrategy\",\"enable\":\"false\""))
        assertTrue(allow.contains(InstallPolicyHelper.SCRIPT))
        assertTrue(allow.contains("mkdir -p /data/adb/service.d"))
        val deny = InstallPolicyHelper.script(false)
        assertTrue(deny.contains("rm -f ${InstallPolicyHelper.SCRIPT}"))
        assertFalse(deny.contains("mkdir -p /data/adb/service.d"))
        assertTrue(deny.contains("\"pmStrategy\",\"enable\":\"true\""))
    }
}
