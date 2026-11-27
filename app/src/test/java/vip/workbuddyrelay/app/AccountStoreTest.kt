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

    @Test
    fun cnUsesPersonalModelsPath() {
        assertEquals("/console/enterprises/personal/models", Upstream.modelsPath(cn()))
    }

    @Test
    fun globalUsesEnterpriseConfigModelsPath() {
        // 国际域上 personal/models 会返回 HTTP 500，必须走 config/models。
        val withEnterprise = gl().copy(enterpriseId = "ent-1")
        assertEquals("/console/enterprises/ent-1/config/models", Upstream.modelsPath(withEnterprise))
        // 没有企业 ID 时回退 personal，仍然是 config/models 而不是旧的 personal/models。
        assertEquals("/console/enterprises/personal/config/models", Upstream.modelsPath(gl()))
    }

    @Test
    fun baseUrlFollowsRegion() {
        assertEquals(Upstream.CN, Upstream.base(cn()))
        assertEquals(Upstream.GLOBAL, Upstream.base(gl()))
    }

    @Test
    fun parseCatalogHandlesArrayDataForGlobal() {
        // 国际版：data 本身是模型数组，没有 agents。
        val global = JSONObject(
            """{"data":[{"id":"m1","tags":["chat"]},{"id":"m2","tags":["chat"]}],"agents":[{"name":"cli"}]}"""
        )
        val (models, agents) = Upstream.parseCatalog(global)
        assertEquals(2, models.length())
        assertEquals(1, agents.length())
        assertEquals("m1", models.getJSONObject(0).getString("id"))
    }

    @Test
    fun parseCatalogHandlesObjectDataForCn() {
        val cnBody = JSONObject(
            """{"code":0,"data":{"models":[{"id":"c1"}],"agents":[{"name":"cli","models":["c1"]}]}}"""
        )
        val (models, agents) = Upstream.parseCatalog(cnBody)
        assertEquals(1, models.length())
        assertEquals("c1", agents.getJSONObject(0).getJSONArray("models").getString(0))
    }

    @Test
    fun parseCatalogToleratesMissingData() {
        val (models, agents) = Upstream.parseCatalog(JSONObject("""{"code":0}"""))
        assertEquals(0, models.length())
        assertEquals(0, agents.length())
    }
}
