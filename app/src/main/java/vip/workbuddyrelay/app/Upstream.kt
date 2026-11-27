package vip.workbuddyrelay.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Upstream {
    const val CN="https://copilot.tencent.com"
    const val GLOBAL="https://www.workbuddy.ai"
    fun base(a:Account)=if(a.global)GLOBAL else CN
    fun common(global:Boolean=false):MutableMap<String,String> {val origin=if(global)GLOBAL else "https://www.codebuddy.cn";return linkedMapOf("Content-Type" to "application/json","Accept" to "application/json, text/plain, */*","X-Requested-With" to "XMLHttpRequest","Origin" to origin,"Referer" to "$origin/","User-Agent" to "CLI/2.63.2 CodeBuddy/2.63.2")}
    fun headers(a:Account):MutableMap<String,String> =common(a.global).apply{
        put("Authorization","Bearer ${a.accessToken}")
        if(a.uid.isNotBlank())put("X-User-Id",a.uid)else put("X-No-User-Id","1")
        if(a.enterpriseId.isNotBlank())put("X-Enterprise-Id",a.enterpriseId)else put("X-No-Enterprise-Id","1")
        if(a.domain.isNotBlank())put("X-Domain",a.domain)else put("X-No-Department-Info","1")
        put("X-Product","SaaS")
    }
    fun open(url:String,method:String,headers:Map<String,String>,body:String?=null):HttpURLConnection {
        val c=URL(url).openConnection() as HttpURLConnection
        c.requestMethod=method;c.connectTimeout=20000;c.readTimeout=120000;c.instanceFollowRedirects=false
        headers.forEach{(k,v)->c.setRequestProperty(k,v)};c.setRequestProperty("Accept-Encoding","identity")
        try{if(body!=null){c.doOutput=true;val bytes=body.toByteArray(Charsets.UTF_8);c.setFixedLengthStreamingMode(bytes.size);c.outputStream.use{it.write(bytes)}};return c}catch(e:Exception){c.disconnect();throw e}
    }
    /** 读取完整响应体并做统一的错误检查；不假设 data 的形状。 */
    fun root(url:String,method:String,headers:Map<String,String>,body:String?=null):JSONObject {
        val c=open(url,method,headers,body)
        try {val status=c.responseCode
            if(status !in 200..299)throw RelayError(if(status in listOf(401,403,402,429))status else 502,"上游 HTTP $status")
            val text=c.inputStream.bufferedReader().use{it.readText()}
            val parsed=try{JSONObject(text)}catch(_:Exception){throw RelayError(502,"上游响应不是 JSON 对象")}
            // 有些端点（国际版模型目录）没有 code 字段，只在存在时才校验。
            if(parsed.has("code")){val code=parsed.optInt("code",0);if(code!=0)throw RelayError(502,"上游业务错误 $code","business_error")}
            return parsed
        }finally{c.disconnect()}
    }
    fun envelope(url:String,method:String,headers:Map<String,String>,body:String?=null):JSONObject {
        return root(url,method,headers,body).optJSONObject("data")?:throw RelayError(502,"上游响应缺少 data")
    }
    @Synchronized fun ready(force:Boolean=false):Account {
        val a=Store.active?:throw RelayError(401,"请先在 WorkBuddy Relay 中登录","login_required")
        if(!force&&a.expiresAt>System.currentTimeMillis()/1000+300)return a
        if(a.refreshToken.isBlank()){if(force||a.expiresAt>0&&a.expiresAt<=System.currentTimeMillis()/1000)throw RelayError(401,"令牌过期，请重新登录","login_required");return a}
        val h=common(a.global);h["X-Refresh-Token"]=a.refreshToken;h["X-Auth-Refresh-Source"]="workbuddy";if(a.enterpriseId.isNotBlank())h["X-Enterprise-Id"]=a.enterpriseId
        val d=envelope(base(a)+"/v2/plugin/auth/token/refresh","POST",h,"")
        val token=d.str("accessToken");if(token.isBlank())throw RelayError(401,"刷新失败，请重新登录")
        val n=a.copy(accessToken=token,refreshToken=d.str("refreshToken").ifBlank{a.refreshToken},domain=d.str("domain").ifBlank{a.domain},expiresAt=if(d.optLong("expiresIn")>0)System.currentTimeMillis()/1000+d.getLong("expiresIn")else maxOf(a.expiresAt,System.currentTimeMillis()/1000+300))
        Store.replaceRefreshed(a,n);Logs.add("访问令牌已刷新");return n
    }
    private fun billingHeaders(a:Account)=headers(a).apply { put("Accept","application/json") }
    fun credits():Long {
        var a=ready()
        fun request():Long {
            val fmt=java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",java.util.Locale.US)
            val now=fmt.format(java.util.Date());val end=fmt.format(java.util.Date(System.currentTimeMillis()+365L*101*24*3600*1000))
            val body=JSONObject().put("PageNumber",1).put("PageSize",100).put("ProductCode","p_tcaca").put("Status",JSONArray().put(0).put(3)).put("PackageEndTimeRangeBegin",now).put("PackageEndTimeRangeEnd",end)
            val d=envelope((if(a.global)GLOBAL else "https://www.codebuddy.cn")+"/v2/billing/meter/get-user-resource","POST",billingHeaders(a),body.toString())
            val root=d.optJSONObject("Response")?.optJSONObject("Data")?:d;val rows=root.optJSONArray("Accounts")?:d.optJSONArray("Accounts")?:JSONArray();var total=0L
            for(i in 0 until rows.length()){val x=rows.optJSONObject(i)?:continue;val cycle=x.optLong("CycleCapacitySize");val remain=if(cycle>0)x.optLong("CycleCapacityRemain")else if(x.has("CycleCapacityRemain")||x.has("CycleCapacityUsed"))x.optLong("CycleCapacityRemain")else x.optLong("CapacityRemain");total+=remain.coerceAtLeast(0)}
            Store.saveCredits(a,total);return total
        }
        return try{request()}catch(e:RelayError){if(e.status!=401)throw e;a=ready(true);request()}
    }
    fun checkin():String {
        var a=ready()
        fun request():String {return try{envelope((if(a.global)GLOBAL else "https://www.codebuddy.cn")+"/v2/billing/meter/daily-checkin","POST",billingHeaders(a),"{}");Store.lastCheckin="签到成功";Logs.add("每日签到成功");"签到成功"}catch(e:RelayError){if(e.message.contains("已签到")||e.message.contains("today",true)||e.message.contains("already",true)){Store.lastCheckin="今日已签到";Logs.add("今日已签到");"今日已签到"}else throw e}}
        return try{request()}catch(e:RelayError){if(e.status!=401)throw e;a=ready(true);request()}
    }
    /**
     * 模型目录端点。两个区域都用 /v3/config（同域返回 200），
     * 旧的 /console/enterprises/personal/models 只有国内域存在，国际域直接 500。
     */
    fun modelsPaths(acc:Account):List<String> = listOf("/v3/config")
    fun modelsPath(acc:Account):String = "/v3/config"
    fun models():JSONArray {
        var a=ready()
        val body=try{root(base(a)+modelsPath(a),"GET",configHeaders(a))}
        catch(e:RelayError){
            if(e.status!=401)throw e
            a=ready(true)
            root(base(a)+modelsPath(a),"GET",configHeaders(a))
        }
        val out=extractModels(body)
        if(out.length()==0){
            val raw=body.toString()
            Logs.add("模型诊断：/v3/config 未解析出模型（${raw.length}B；data.models=" +
                (body.optJSONObject("data")?.opt("models")?.let{it.javaClass.simpleName}?:"null") + "）")
            val manual=Store.manualFor(a)
            if(manual.isNotEmpty()){Logs.add("使用手动配置的 ${manual.size} 个模型");return Store.modelsFor(a)}
            throw RelayError(502,"该账号目录没有返回模型。可在「手动添加模型」里填写模型 ID。")
        }
        Store.saveModels(a,out)
        Logs.add("模型目录(/v3/config)：${out.length()} 个")
        return out
    }
    /** /v3/config 的返回按渠道区分，用 CLI 渠道身份读取 cli 名单。 */
    private fun configHeaders(a:Account)=headers(a).apply{
        put("User-Agent","CLI/2.63.2 CodeBuddy/2.63.2")
        put("Accept","application/json")
    }
    /**
     * 解析 /v3/config：
     *  data.models 是模型数组，data.agent.agents（或 data.agents）里有 name=cli 的名单；
     *  每个模型的 credits 是形如 "x0.79" 的字符串，倍率由此而来。
     */
    fun extractModels(d:JSONObject):JSONArray {
        val data=d.optJSONObject("data")?:d
        val all=data.optJSONArray("models")?:JSONArray()
        val agents=data.optJSONObject("agent")?.optJSONArray("agents")
            ?:data.optJSONArray("agents")?:JSONArray()
        val ids=mutableListOf<String>()
        for(i in 0 until agents.length()){val agent=agents.optJSONObject(i)?:continue
            if(agent.str("name")=="cli"){val ms=agent.optJSONArray("models")?:JSONArray()
                for(j in 0 until ms.length())ms.optString(j).takeIf{it.isNotBlank()}?.let{ids.add(it)};break}}
        if(ids.isEmpty()){for(i in 0 until all.length()){val m=all.optJSONObject(i)?:continue
            if(m.optBoolean("disabled"))continue
            // 路由型模型（auto 之类）每请求解析成不同模型，不适合作为固定条目。
            if(m.str("id").endsWith("-auto"))continue
            if(m.str("id").isNotBlank())ids.add(m.str("id"))}}
        val out=JSONArray()
        for(id in ids){
            val m=(0 until all.length()).mapNotNull{all.optJSONObject(it)}.firstOrNull{it.str("id")==id}?:continue
            if(m.optBoolean("disabled"))continue
            val item=JSONObject()
                .put("id",id).put("object","model").put("owned_by","workbuddy").put("created",0)
                .put("name",m.str("name").ifBlank{id})
                .put("context_window",m.optLong("maxInputTokens"))
                .put("max_output_tokens",m.optLong("maxOutputTokens"))
                .put("supported_efforts",m.optJSONObject("reasoning")?.optJSONArray("supportedEfforts")?:JSONArray())
            // credits 形如 "x0.79"，倍率完全由上游给出。
            creditMultiplier(m.str("credits"))?.let{item.put("multiplier",it).put("credits",m.str("credits"))}
            if(m.has("supportsImages"))item.put("supports_images",m.optBoolean("supportsImages"))
            out.put(item)
        }
        return out
    }
    /** 从 "x0.79" 这样的上游字符串里取倍率；取不到就不写，绝不猜。 */
    fun creditMultiplier(raw:String):Double? {
        val m=Regex("x\\s*([0-9]*\\.?[0-9]+)",RegexOption.IGNORE_CASE).find(raw)?:return null
        val v=m.groupValues[1].toDoubleOrNull()?:return null
        return if(v>=0)v else null
    }
    fun chat(body:JSONObject):HttpURLConnection {
        var a=ready();var c=open(base(a)+"/v2/chat/completions","POST",headers(a),body.toString())
        if(c.responseCode==401){c.disconnect();a=ready(true);c=open(base(a)+"/v2/chat/completions","POST",headers(a),body.toString())}
        if(c.responseCode !in 200..299){val status=c.responseCode;c.disconnect();throw RelayError(if(status in listOf(401,403,402,429))status else 502,"上游 HTTP $status")}
        if(!c.contentType.orEmpty().contains("text/event-stream",true)){c.disconnect();throw RelayError(502,"上游未返回 SSE 响应")};return c
    }
}
object Login {
    private fun noAuth(global:Boolean)=Upstream.common(global).apply{for(k in listOf("Authorization","User-Id","Enterprise-Id","Department-Info"))put("X-No-$k","true")}
    fun start(global:Boolean):JSONObject {
        val base=if(global)Upstream.GLOBAL else Upstream.CN
        val d=Upstream.envelope(base+"/v2/plugin/auth/state?platform=workbuddy","POST",noAuth(global),"{}")
        if(d.str("state").isBlank()||!d.str("authUrl").startsWith("https://"))throw RelayError(502,"未取得有效授权链接")
        val p=JSONObject().put("state",d.getString("state")).put("authUrl",d.getString("authUrl")).put("global",global).put("created",System.currentTimeMillis()/1000)
        Store.savePending(p);return p
    }
    fun poll():Boolean {
        val p=Store.pending()?:throw RelayError(400,"请先创建授权链接")
        if(System.currentTimeMillis()/1000-p.optLong("created")>600){Store.savePending(null);throw RelayError(400,"授权已超时，请重新创建")}
        val global=p.optBoolean("global");val base=if(global)Upstream.GLOBAL else Upstream.CN
        val c=Upstream.open(base+"/v2/plugin/auth/token?state="+URLEncoder.encode(p.getString("state"),"UTF-8"),"GET",noAuth(global))
        val root=try{val s=if(c.responseCode in 200..299)c.inputStream else c.errorStream;JSONObject(s?.bufferedReader()?.use{it.readText()}?:"{}")}finally{c.disconnect()}
        if(root.optInt("code")==11217)return false
        if(root.optInt("code",-1)!=0)throw RelayError(502,"授权查询业务错误 ${root.optInt("code",-1)}")
        val t=root.optJSONObject("data")?:return false
        if(t.str("accessToken").isBlank())return false
        val token=t.getString("accessToken")
        val domain=t.str("domain").ifBlank{URL(base).host}
        val h=Upstream.common(global).apply{put("Authorization","Bearer $token");put("X-Domain",domain)}
        // Official external-link auth resolves account via /v2/plugin/accounts.
        val d=Upstream.envelope(base+"/v2/plugin/accounts","GET",h)
        val accounts=d.optJSONArray("accounts")?:throw RelayError(502,"没有返回账号信息")
        var account:JSONObject?=null
        for(i in 0 until accounts.length()){val u=accounts.getJSONObject(i);if(u.optBoolean("pluginEnabled",true)&&(account==null||u.optBoolean("lastLogin")))account=u}
        val u=account?:throw RelayError(403,"账号未启用插件访问")
        var expiry=t.optLong("expiresAt");if(expiry>100000000000L)expiry/=1000;if(expiry==0L)expiry=System.currentTimeMillis()/1000+t.optLong("expiresIn",3600)
        // 区域以本次授权时选择的为准，避免 domain 缺失时被推断成国内版覆盖掉国际版登录。
        val saved=Account(token,t.str("refreshToken"),expiry,domain,u.str("uid"),u.str("enterpriseId"),u.str("nickname"),global)
        Store.saveAccount(saved)
        Store.savePending(null)
        val region=if(global)"国际版" else "国内版"
        Logs.add("WorkBuddy ${region}授权成功：${saved.displayName}")
        return true
    }
}
