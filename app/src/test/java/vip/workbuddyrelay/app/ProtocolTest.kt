package vip.workbuddyrelay.app

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject

class ProtocolTest {
    private fun request(tc:Any)=JSONObject().put("model","test").put("messages",org.json.JSONArray().put(JSONObject().put("role","user").put("content","hello"))).put("tools",org.json.JSONArray().put(JSONObject().put("type","function"))).put("tool_choice",tc).put("stream",false)
    @Test fun namedToolAndNone(){val named=OpenAI.prepare(request(JSONObject("""{"type":"function","function":{"name":"weather"}}""")));assertEquals("weather",named.getString("tool_choice"));assertTrue(named.getBoolean("stream"));val none=OpenAI.prepare(request("none"));assertFalse(none.has("tools"));assertFalse(none.has("tool_choice"))}
    @Test fun preservesToolsMessagesAndMultimodal(){val src=request("required");src.getJSONArray("messages").put(JSONObject("""{"role":"tool","tool_call_id":"call1","content":"result"}"""));val out=OpenAI.prepare(src);assertEquals("call1",out.getJSONArray("messages").getJSONObject(1).getString("tool_call_id"));assertTrue(out.has("tools"))}
    @Test fun sseMultilineCrLfAndFinalEvent(){val got=mutableListOf<String>();OpenAI.events("data: one\r\ndata: two\r\n\r\n: ping\r\ndata: last".reader().buffered()){got.add(it);true};assertEquals(listOf("one\ntwo","last"),got)}
    @Test fun aggregateInterleavedToolsReasoningAndUsage(){
        val a=OpenAI.Aggregate()
        val frames=listOf(
            """{"id":"test","model":"m","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"thinking","tool_calls":[{"index":1,"id":"b","type":"function","function":{"name":"second","arguments":"{"}},{"index":0,"id":"a","function":{"name":"first","arguments":"{\"x\":"}}]},"finish_reason":""}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1}"}},{"index":1,"function":{"arguments":"}"}}]},"finish_reason":"tool_calls"}],"usage":{"total_tokens":9}}"""
        )
        frames.forEach{a.add(OpenAI.normalize(JSONObject(it)))}
        val out=a.result();val msg=out.getJSONArray("choices").getJSONObject(0).getJSONObject("message");val calls=msg.getJSONArray("tool_calls")
        assertEquals("a",calls.getJSONObject(0).getString("id"));assertFalse(calls.getJSONObject(0).has("index"));assertEquals("{\"x\":1}",calls.getJSONObject(0).getJSONObject("function").getString("arguments"));assertEquals("{}",calls.getJSONObject(1).getJSONObject("function").getString("arguments"));assertEquals("thinking",msg.getString("reasoning_content"));assertEquals(9,out.getJSONObject("usage").getInt("total_tokens"));assertTrue(msg.isNull("content"))
    }
    @Test fun normalizeNoise(){val c=OpenAI.normalize(JSONObject("""{"choices":[{"index":0,"delta":{"content":"","reasoning_content":"r","tool_calls":[],"function_call":null,"extra_fields":{}},"finish_reason":""}]}"""));val ch=c.getJSONArray("choices").getJSONObject(0);assertTrue(ch.isNull("finish_reason"));assertEquals(setOf("reasoning_content"),ch.getJSONObject("delta").keys().asSequence().toSet())}
    @Test fun incompleteStreamRejected(){val a=OpenAI.Aggregate();a.add(OpenAI.normalize(JSONObject("""{"choices":[{"delta":{"content":"partial"}}]}""")));try{a.result();fail("must reject premature EOF")}catch(e:RelayError){assertEquals("incomplete_stream",e.code)}}
    @Test fun refreshSecretOnlyOnRefresh(){val a=Account("access","refresh",123,"copilot.tencent.com","uid","ent","nickname");assertFalse(Upstream.headers(a).containsKey("X-Refresh-Token"));assertEquals("Bearer access",Upstream.headers(a)["Authorization"]);assertEquals("uid",Upstream.headers(a)["X-User-Id"])}
    @Test fun credentialFormatsAndRegions(){val a=Account.parse(JSONObject("""{"auth":{"accessToken":"a","expiresAt":1900000000000,"domain":"www.workbuddy.ai"},"account":{"uid":"u"}}"""));assertTrue(a.global);assertEquals(1900000000L,a.expiresAt);assertFalse(Account.globalOf("evilworkbuddy.ai"));assertFalse(Account.globalOf("workbuddy.ai.evil"));assertTrue(Account.globalOf("www.workbuddy.ai"));assertEquals("u",Account.parse(JSONObject("""{"accessToken":"a","uid":"u"}""")).uid)}
    @Test fun invalidRequestRejected(){try{OpenAI.prepare(JSONObject("""{"model":"x","messages":[]}"""));fail()}catch(e:RelayError){assertEquals(400,e.status)}}
}
