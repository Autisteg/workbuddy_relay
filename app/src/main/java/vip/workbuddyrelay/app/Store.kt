package vip.workbuddyrelay.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Account(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val domain: String,
    val uid: String,
    val enterpriseId: String,
    val nickname: String,
    /** 登录时选择的区域；与 domain 推断结果冲突时以此为准。 */
    val global: Boolean = Account.globalOf(domain)
) {
    fun json() = JSONObject()
        .put("auth", JSONObject().put("accessToken", accessToken).put("refreshToken", refreshToken)
            .put("expiresAt", expiresAt).put("domain", domain).put("global", global))
        .put("account", JSONObject().put("uid", uid).put("enterpriseId", enterpriseId).put("nickname", nickname))

    /** 同一账号的唯一键：区域 + uid。 */
    val key: String get() = (if (global) "global:" else "cn:") + (uid.ifBlank { domain })

    val regionLabel: String get() = if (global) "国际版" else "国内版"

    val displayName: String get() = nickname.ifBlank { uid.ifBlank { "未命名账号" } }

    companion object {
        fun globalOf(domain: String): Boolean =
            domain.lowercase().let { it == "workbuddy.ai" || it.endsWith(".workbuddy.ai") }

        fun parse(o: JSONObject): Account {
            val a = o.optJSONObject("auth") ?: o
            val u = o.optJSONObject("account") ?: o
            require(a.optString("accessToken").isNotBlank()) { "缺少 accessToken" }
            var expiry = a.optLong("expiresAt")
            if (expiry > 100000000000L) expiry /= 1000
            val domain = a.optString("domain")
            // 显式记录的区域优先；没有时按 domain 推断。
            val global = if (a.has("global")) a.optBoolean("global") else globalOf(domain)
            return Account(
                a.optString("accessToken"), a.optString("refreshToken"), expiry, domain,
                u.optString("uid"), u.optString("enterpriseId"), u.optString("nickname"), global
            )
        }
    }
}

data class Config(
    var port: Int = 8318,
    var allowLan: Boolean = false,
    var apiKey: String = "sk-wb-" + Crypto.uuidNoDash(),
    var keepAliveAudio: Boolean = true
)

object Store {
    private lateinit var dir: File
    @Volatile var config = Config(); private set

    /** 已登录账号，可能是国内版与国际版各一个。 */
    @Volatile var accounts: List<Account> = emptyList(); private set

    /** 当前用于转发请求的账号。 */
    @Volatile var activeKey: String = ""; private set

    @Volatile var models = JSONObject(); private set
    @Volatile var modelsAt = 0L; private set
    @Volatile var modelsError = ""
    @Volatile var credits: Long? = null; private set
    @Volatile var creditsAt = 0L; private set
    @Volatile var lastCheckin = ""

    /** 兼容旧调用的单账号视图。 */
    val account: Account? get() = active ?: accounts.firstOrNull()

    val active: Account? get() = accounts.firstOrNull { it.key == activeKey } ?: accounts.firstOrNull()

    fun accountsFor(global: Boolean): List<Account> = accounts.filter { it.global == global }

    @Synchronized fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.filesDir, "relay").apply { mkdirs() }
        Logs.file = File(dir, "relay.log")
        try {
            val o = read("config.enc")
            if (o != null) config = Config(
                o.optInt("port", 8318), o.optBoolean("allowLan"), o.optString("apiKey", config.apiKey),
                o.optBoolean("keepAliveAudio", true)
            )
        } catch (_: Exception) { Logs.add("配置解密失败，请重新设置") }
        loadAccounts()
        loadModels()
        saveConfig()
    }

    private fun loadAccounts() {
        val list = LinkedHashMap<String, Account>()
        // 新格式：多账号
        try {
            val o = read("accounts.enc")
            val arr = o?.optJSONArray("accounts")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    runCatching { Account.parse(arr.getJSONObject(i)) }.getOrNull()?.let { list[it.key] = it }
                }
            }
            activeKey = o?.optString("active").orEmpty()
        } catch (_: Exception) { Logs.add("账号列表解密失败，请重新登录") }
        // 旧格式：单账号，迁移进来
        try {
            val legacy = read("account.enc")
            if (legacy != null) {
                val a = runCatching { Account.parse(legacy) }.getOrNull()
                if (a != null) list[a.key] = a
                AtomicFile(File(dir, "account.enc")).delete()
            }
        } catch (_: Exception) { }
        accounts = list.values.toList()
        if (activeKey.isBlank() || accounts.none { it.key == activeKey }) activeKey = accounts.firstOrNull()?.key ?: ""
        persistAccounts()
    }

    private fun loadModels() {
        models = JSONObject()
        try {
            val o = read("models.enc")
            models = o?.optJSONObject("by_account") ?: JSONObject()
            modelsAt = o?.optLong("at") ?: 0
        } catch (_: Exception) { }
    }

    private fun read(name: String): JSONObject? {
        val f = AtomicFile(File(dir, name))
        if (!f.baseFile.exists() && !File(dir, "$name.bak").exists()) return null
        return JSONObject(Crypto.aesDecrypt(String(f.readFully(), Charsets.UTF_8)))
    }

    private fun write(name: String, o: JSONObject) {
        val f = AtomicFile(File(dir, name))
        val bytes = Crypto.aesEncrypt(o.toString()).toByteArray()
        val stream = f.startWrite()
        try { stream.write(bytes); f.finishWrite(stream) } catch (e: Exception) { f.failWrite(stream); throw e }
    }

    @Synchronized fun saveConfig() {
        write("config.enc", JSONObject().put("port", config.port).put("allowLan", config.allowLan)
            .put("apiKey", config.apiKey).put("keepAliveAudio", config.keepAliveAudio))
    }

    /** 新增或更新一个账号；保留其它账号，不会把旧账号覆盖掉。 */
    @Synchronized fun saveAccount(a: Account?) {
        if (a == null) return
        val list = LinkedHashMap<String, Account>()
        accounts.forEach { list[it.key] = it }
        list[a.key] = a
        accounts = list.values.toList()
        activeKey = a.key
        clearModelsFor(a)
        persistAccounts()
    }

    /** 删除指定账号；不传则删除当前活动账号。 */
    @Synchronized fun removeAccount(target: Account? = active) {
        val key = target?.key ?: return
        accounts = accounts.filterNot { it.key == key }
        models.remove(key)
        if (activeKey == key) activeKey = accounts.firstOrNull()?.key ?: ""
        persistAccounts()
        persistModels()
    }

    @Synchronized fun clearAccounts() {
        accounts = emptyList(); activeKey = ""; models = JSONObject()
        AtomicFile(File(dir, "accounts.enc")).delete()
        AtomicFile(File(dir, "models.enc")).delete()
        credits = null; creditsAt = 0; lastCheckin = ""
    }

    @Synchronized fun setActive(a: Account) {
        if (accounts.none { it.key == a.key }) return
        activeKey = a.key
        credits = null; creditsAt = 0; lastCheckin = ""
        persistAccounts()
    }

    private fun persistAccounts() {
        val arr = JSONArray()
        accounts.forEach { arr.put(it.json()) }
        write("accounts.enc", JSONObject().put("accounts", arr).put("active", activeKey))
    }

    /** 令牌刷新后替换同一账号，不改变其它账号。 */
    @Synchronized fun replaceRefreshed(old: Account, new: Account) {
        if (accounts.none { it.key == old.key }) throw RelayError(401, "账号已更改，请重新登录", "login_required")
        val merged = new.copy(nickname = new.nickname.ifBlank { old.nickname })
        accounts = accounts.map { if (it.key == old.key) merged else it }
        if (activeKey == old.key) activeKey = merged.key
        persistAccounts()
    }

    private fun clearModelsFor(a: Account) {
        models.remove(a.key)
        persistModels()
    }

    @Synchronized fun saveModels(a: Account, list: JSONArray) {
        if (accounts.none { it.key == a.key }) return
        models.put(a.key, list)
        modelsAt = System.currentTimeMillis() / 1000
        modelsError = ""
        persistModels()
    }

    private fun persistModels() {
        write("models.enc", JSONObject().put("by_account", models).put("at", modelsAt))
    }

    /** 当前活动账号的模型；没有则回退到任意一个。 */
    fun modelsFor(a: Account? = active): JSONArray {
        if (a == null) return JSONArray()
        models.optJSONArray(a.key)?.let { return it }
        return JSONArray()
    }

    @Synchronized fun saveCredits(a: Account, remain: Long) {
        if (activeKey.isNotEmpty() && a.key != activeKey) return
        credits = remain
        creditsAt = System.currentTimeMillis() / 1000
    }

    @Synchronized fun savePending(o: JSONObject?) {
        if (o == null) AtomicFile(File(dir, "oauth.enc")).delete() else write("oauth.enc", o)
    }

    @Synchronized fun pending(): JSONObject? = read("oauth.enc")
}

fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")
class RelayError(val status: Int, override val message: String, val code: String = "upstream_error") : Exception(message)
fun safeMessage(e: Throwable): String = when (e) {
    is RelayError -> e.message
    is IllegalArgumentException -> "请求参数无效"
    else -> "操作失败（${e.javaClass.simpleName}），请检查网络或重新登录"
}
