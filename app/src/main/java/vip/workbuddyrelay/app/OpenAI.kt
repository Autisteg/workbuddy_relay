package vip.workbuddyrelay.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.util.UUID

object OpenAI {
    fun error(message:String,code:String="upstream_error")=JSONObject().put("error",JSONObject().put("message",message).put("type",if(code=="invalid_request_error")code else "api_error").put("code",code))
    fun prepare(src:JSONObject):JSONObject {
        val o=JSONObject(src.toString());if(o.str("model").isBlank())throw RelayError(400,"model 不能为空","invalid_request_error")
        val messages=o.optJSONArray("messages")?:throw RelayError(400,"messages 必须是数组","invalid_request_error")
        if(messages.length()==0)throw RelayError(400,"messages 不能为空","invalid_request_error")
        if(o.optInt("n",1)!=1)throw RelayError(400,"当前仅支持 n=1","invalid_request_error")
        o.put("stream",true);o.remove("stream_options")
        val tc=o.opt("tool_choice")
        if(tc is JSONObject){when(tc.str("type").lowercase()){
            "none"->{o.remove("tool_choice");o.remove("tools");o.remove("functions")}
            "auto","required"->o.put("tool_choice",tc.str("type").lowercase())
            "function"->o.put("tool_choice",(tc.optJSONObject("function")?.str("name")?:tc.str("name")).ifBlank{"auto"})
            else->o.remove("tool_choice")
        }}else if(tc is String){if(tc.trim().equals("none",true)){o.remove("tool_choice");o.remove("tools");o.remove("functions")}}else if(tc!=null)o.remove("tool_choice")
        val ranks=listOf("off","minimal","low","medium","high","xhigh","max")
        val key=if(o.has("reasoning_effort"))"reasoning_effort" else "reasoningEffort"
        val rank=ranks.indexOf(o.str(key).trim().lowercase())
        if(rank>=0){val models=Store.modelsFor();for(i in 0 until models.length()){val m=models.getJSONObject(i);if(m.str("id")==o.str("model")){val a=m.optJSONArray("supported_efforts")?:break;val supported=(0 until a.length()).map{a.getString(it)}.filter{ranks.indexOf(it.lowercase())>=0}.sortedBy{ranks.indexOf(it.lowercase())};if(supported.isNotEmpty())o.put(key,supported.lastOrNull{ranks.indexOf(it.lowercase())<=rank}?:supported.first());break}}}
        return o
    }
    /** SSE event reader supports CRLF, multiline data and an unterminated final event. */
    fun events(reader:BufferedReader,accept:(String)->Boolean) {
        val data=StringBuilder();var has=false
        while(true){val line=reader.readLine()
            if(line==null||line.isEmpty()){
                if(has){val payload=data.toString();data.setLength(0);has=false;if(!accept(payload))return}
                if(line==null)return
            }else if(line.startsWith("data:")){if(has)data.append('\n');data.append(line.substring(5).removePrefix(" "));has=true;if(data.length>8*1024*1024)throw RelayError(502,"SSE 帧超过限制")}
        }
    }
    fun normalize(src:JSONObject):JSONObject {
        if(src.has("error"))throw RelayError(502,"上游流返回错误")
        val out=JSONObject()
        for(k in listOf("id","object","created","model","system_fingerprint","service_tier"))if(src.has(k)&&!src.isNull(k))out.put(k,src.get(k))
        if(!out.has("id"))out.put("id","chatcmpl-workbuddy");out.put("object","chat.completion.chunk")
        val choices=src.optJSONArray("choices")?:throw RelayError(502,"上游 SSE 缺少 choices")
        val normalized=JSONArray()
        for(i in 0 until choices.length()){
            val c=choices.getJSONObject(i);val d=c.optJSONObject("delta")?:c.optJSONObject("message")?:JSONObject();val nd=JSONObject()
            for(k in listOf("role","content","reasoning_content","refusal"))if(d.str(k).isNotEmpty())nd.put(k,d.get(k))
            if((d.optJSONArray("tool_calls")?.length()?:0)>0)nd.put("tool_calls",d.getJSONArray("tool_calls"))
            val f=d.optJSONObject("function_call");if(f!=null&&(f.str("name").isNotEmpty()||f.str("arguments").isNotEmpty()))nd.put("function_call",f)
            normalized.put(JSONObject().put("index",c.optInt("index",i)).put("delta",nd).put("finish_reason",c.str("finish_reason").ifBlank{null}?:JSONObject.NULL))
        }
        out.put("choices",normalized);out.put("usage",src.opt("usage")?:JSONObject.NULL);return out
    }
    class Aggregate {
        private val result=JSONObject().put("id","chatcmpl-"+UUID.randomUUID()).put("created",System.currentTimeMillis()/1000).put("object","chat.completion")
        private class Choice {var role="assistant";val content=StringBuilder();val reasoning=StringBuilder();val refusal=StringBuilder();var finish="";val tools=sortedMapOf<Int,JSONObject>();val legacy=JSONObject()}
        private val choices=sortedMapOf<Int,Choice>()
        private var size=0
        var finished=false;private set
        fun add(chunk:JSONObject){
            size+=chunk.toString().length;if(size>32*1024*1024)throw RelayError(502,"聚合响应超过限制")
            for(k in listOf("id","model","created","system_fingerprint","service_tier"))if(chunk.has(k))result.put(k,chunk.get(k))
            if(!chunk.isNull("usage"))result.put("usage",chunk.get("usage"))
            val cs=chunk.optJSONArray("choices")?:JSONArray()
            for(i in 0 until cs.length()){
                val c=cs.getJSONObject(i);val acc=choices.getOrPut(c.optInt("index",i)){Choice()};val d=c.optJSONObject("delta")?:JSONObject()
                if(d.str("role").isNotEmpty())acc.role=d.str("role")
                acc.content.append(d.str("content"));acc.reasoning.append(d.str("reasoning_content"));acc.refusal.append(d.str("refusal"))
                if(c.str("finish_reason").isNotEmpty()){acc.finish=c.str("finish_reason");finished=true}
                val calls=d.optJSONArray("tool_calls")?:JSONArray()
                for(j in 0 until calls.length()){val delta=calls.getJSONObject(j);val call=acc.tools.getOrPut(delta.optInt("index",j)){JSONObject().put("type","function").put("function",JSONObject().put("arguments",""))};for(k in listOf("id","type"))if(delta.str(k).isNotBlank())call.put(k,delta.str(k));val f=delta.optJSONObject("function");if(f!=null)mergeFunction(call.getJSONObject("function"),f)}
                d.optJSONObject("function_call")?.let{mergeFunction(acc.legacy,it)}
            }
        }
        private fun mergeFunction(target:JSONObject,delta:JSONObject){if(delta.str("name").isNotEmpty())target.put("name",delta.str("name"));if(delta.has("arguments"))target.put("arguments",target.str("arguments")+delta.str("arguments"))}
        fun result():JSONObject {
            if(choices.isEmpty()||choices.values.any{it.finish.isBlank()})throw RelayError(502,"上游 SSE 提前结束，未收到完整响应","incomplete_stream")
            val a=JSONArray();for((index,c)in choices){val m=JSONObject().put("role",c.role).put("content",if(c.content.isEmpty()&&c.tools.isNotEmpty())JSONObject.NULL else c.content.toString());if(c.reasoning.isNotEmpty())m.put("reasoning_content",c.reasoning.toString());if(c.refusal.isNotEmpty())m.put("refusal",c.refusal.toString());if(c.tools.isNotEmpty())m.put("tool_calls",JSONArray(c.tools.values.toList()));if(c.legacy.length()>0)m.put("function_call",c.legacy);a.put(JSONObject().put("index",index).put("message",m).put("finish_reason",c.finish))};return result.put("choices",a)
        }
    }
}
