package vip.workbuddyrelay.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    fun baseUrlFollowsRegion() {
        assertEquals(Upstream.CN, Upstream.base(cn()))
        assertEquals(Upstream.GLOBAL, Upstream.base(gl()))
    }

    @Test
    fun extractModelsUsesCliRosterFromAgentContainer() {
        // /v3/config 真实结构：data.models + data.agent.agents（name=cli）
        val body = JSONObject(
            """{"code":0,"data":{"models":[
                 {"id":"glm-5.3","name":"GLM-5.3","maxInputTokens":1000000,"maxOutputTokens":48000,"credits":"x0.79"},
                 {"id":"gpt-5.5","name":"GPT-5.5","maxInputTokens":400000,"maxOutputTokens":32000,"credits":"x1.2"},
                 {"id":"hidden","name":"Hidden","maxInputTokens":1000,"maxOutputTokens":100,"disabled":true}
               ],"agent":{"agents":[{"name":"cli","models":["glm-5.3","hidden"]}]}}}"""
        )
        val out = Upstream.extractModels(body)
        assertEquals(1, out.length())
        val m = out.getJSONObject(0)
        assertEquals("glm-5.3", m.getString("id"))
        assertEquals(1000000L, m.getLong("context_window"))
        assertEquals(48000L, m.getLong("max_output_tokens"))
        assertEquals(0.79, m.getDouble("multiplier"), 0.0001)
    }

    @Test
    fun extractModelsFallsBackToAllModelsWithoutCliRoster() {
        val body = JSONObject(
            """{"code":0,"data":{"models":[
                 {"id":"gpt-5.5","maxInputTokens":400000,"maxOutputTokens":32000,"credits":"x1.2"},
                 {"id":"router-auto","maxInputTokens":1000,"maxOutputTokens":10}
               ]}}"""
        )
        val out = Upstream.extractModels(body)
        assertEquals(1, out.length())
        assertEquals("gpt-5.5", out.getJSONObject(0).getString("id"))
    }

    @Test
    fun extractModelsOnUnauthenticatedConfigReturnsEmpty() {
        // 未鉴权时 data.models 为 null，必须安全返回空而不是崩溃
        val body = JSONObject("""{"code":0,"data":{"agent":{"agents":null},"models":null}}""")
        assertEquals(0, Upstream.extractModels(body).length())
    }

    @Test
    fun creditMultiplierParsesUpstreamCreditsString() {
        assertEquals(0.79, Upstream.creditMultiplier("x0.79")!!, 0.0001)
        assertEquals(1.2, Upstream.creditMultiplier("X1.2")!!, 0.0001)
        assertEquals(0.0, Upstream.creditMultiplier("x0.00")!!, 0.0001)
        // 没有倍率就不猜
        assertNull(Upstream.creditMultiplier(""))
        assertNull(Upstream.creditMultiplier("free"))
    }

    @Test
    fun modelsPathIsV3ConfigForBothRegions() {
        // 旧的 personal/models 在国际域返回 500，两个区域都改用 /v3/config
        assertEquals("/v3/config", Upstream.modelsPath(cn()))
        assertEquals("/v3/config", Upstream.modelsPath(gl()))
    }
}
