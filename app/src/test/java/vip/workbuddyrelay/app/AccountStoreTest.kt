package vip.workbuddyrelay.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多账号 / 区域相关纯逻辑测试，不访问网络，也不依赖 Android 存储。 */
class AccountStoreTest {

    private fun cn(uid: String = "cn-1") =
        Account("at-cn", "rt-cn", 0, "copilot.tencent.com", uid, "", "国内账号", global = false)

    private fun gl(uid: String = "gl-1") =
        Account("at-gl", "rt-gl", 0, "www.workbuddy.ai", uid, "", "Global Account", global = true)

    @Test
    fun regionFollowsExplicitFlagNotDomain() {
        // domain 缺失时必须沿用登录时选择的区域，不能因为空串被推断成国内版。
        val global = Account("at", "rt", 0, "", "u", "", "n", global = true)
        assertTrue(global.global)
        assertEquals("国际版", global.regionLabel)
        assertTrue(global.key.startsWith("global:"))
    }

    @Test
    fun legacyJsonWithoutGlobalFallsBackToDomain() {
        val legacy = JSONObject()
            .put("auth", JSONObject().put("accessToken", "at").put("domain", "www.workbuddy.ai"))
            .put("account", JSONObject().put("uid", "u1").put("nickname", "n"))
        assertTrue(Account.parse(legacy).global)

        val cnLegacy = JSONObject()
            .put("auth", JSONObject().put("accessToken", "at").put("domain", "copilot.tencent.com"))
            .put("account", JSONObject().put("uid", "u2"))
        assertFalse(Account.parse(cnLegacy).global)
    }

    @Test
    fun sameUidInDifferentRegionsAreDistinctAccounts() {
        assertEquals(cn("same").key, cn("same").key)
        assertNotEquals(cn("same").key, gl("same").key)
    }

    @Test
    fun regionIsPersistedToJson() {
        val roundTrip = Account.parse(gl().json())
        assertTrue(roundTrip.global)
        assertEquals("gl-1", roundTrip.uid)
        assertEquals("Global Account", roundTrip.nickname)
    }

    @Test
    fun displayNameFallsBackWhenNicknameBlank() {
        val a = Account("at", "rt", 0, "", "uid-9", "", "", global = false)
        assertEquals("uid-9", a.displayName)
    }
}
