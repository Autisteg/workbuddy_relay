package vip.workbuddyrelay.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var panel: LinearLayout
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var link: TextView
    private lateinit var accountsView: TextView
    private lateinit var global: Switch
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false
    private var polling = false

    /** 程序化设置开关时不要触发切换回调。 */
    private var suppressToggle = false

    /** 按区域切换当前账号；该区域没有账号时提示先登录。 */
    private fun switchRegion(globalRegion: Boolean) {
        val target = Store.accounts.firstOrNull { it.global == globalRegion }
        if (target == null) {
            val region = if (globalRegion) "国际版" else "国内版"
            Logs.add("尚未登录$region 账号，请先完成授权")
            refresh()
            return
        }
        if (target.key != Store.active?.key) {
            Store.setActive(target)
            Logs.add("已切换到 ${target.regionLabel} 账号：${target.displayName}")
        }
        refresh()
        work { Upstream.models() }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (destroyed || !polling) return
            worker.execute {
                try {
                    val done = Login.poll()
                    runOnUiThread {
                        if (done) {
                            polling = false
                            link.text = "授权完成"
                            refresh()
                            work { Upstream.models() }
                        } else if (!destroyed) handler.postDelayed(this, 3000)
                    }
                } catch (e: Throwable) {
                    runOnUiThread { polling = false; status.text = safeMessage(e); refresh() }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Store.init(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 10)

        val scroll = ScrollView(this)
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 40) }
        scroll.addView(panel)
        setContentView(scroll)

        text("WorkBuddy Relay", 26f)
        text("OpenAI 兼容接口 · 本机默认端口 8318", 14f)
        status = text("", 16f)

        button("启动服务") { try { RelayService.start(this); handler.postDelayed({ refresh() }, 700) } catch (e: Throwable) { status.text = safeMessage(e) } }
        button("停止服务") { RelayService.stop(this); handler.postDelayed({ refresh() }, 400) }
        button("复制接口地址") { copy("Base URL", "http://127.0.0.1:${Store.config.port}/v1") }
        button("复制本地 API Key") { copy("API Key", Store.config.apiKey) }

        text("账号", 18f)
        accountsView = text("", 13f)
        button("管理账号（切换 / 删除）") { manageAccounts() }

        global = Switch(this).apply {
            text = "国际版 workbuddy.ai（关闭为国内版）"
            setOnCheckedChangeListener { _, checked ->
                if (suppressToggle) return@setOnCheckedChangeListener
                switchRegion(checked)
            }
        }
        panel.addView(global)
        button("创建设备授权链接") {
            polling = false
            handler.removeCallbacks(poll)
            // 授权区域跟随开关；若该区域已有账号，仍然会另外保存为新账号而不覆盖。
            val g = global.isChecked
            work { val p = Login.start(g); runOnUiThread { showLink(p); startPolling() } }
        }
        link = text("", 13f)
        link.setTextIsSelectable(true)
        button("打开授权页面") {
            try {
                val p = Store.pending() ?: throw RelayError(400, "请先创建授权链接")
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.getString("authUrl"))))
            } catch (e: Throwable) { status.text = safeMessage(e) }
        }
        button("继续检查授权") { startPolling() }
        button("取消本次授权") { polling = false; handler.removeCallbacks(poll); Store.savePending(null); link.text = "已取消授权检查" }
        button("导入 WorkBuddy 凭据 JSON") {
            val input = EditText(this).apply {
                hint = "支持 auth/account 嵌套或扁平 JSON，可加 \"global\": true 指定国际版"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            AlertDialog.Builder(this).setTitle("导入自己的账号凭据").setView(input)
                .setPositiveButton("导入") { _, _ ->
                    val raw = input.text.toString()
                    input.setText("")
                    work {
                        val a = Account.parse(JSONObject(raw))
                        Store.saveAccount(a)
                        Upstream.models()
                    }
                }.setNegativeButton("取消", null).show()
        }

        button("立即签到领积分") { work { val result = Upstream.checkin(); Upstream.credits(); runOnUiThread { status.text = result } } }
        button("刷新积分") { work { val value = Upstream.credits(); runOnUiThread { status.text = "当前积分：$value" } } }
        button("更新动态模型") { work { Upstream.models() } }
        button("手动添加模型（目录为空时用）") { addManualModel() }
        button("查看模型") {
            val models = Store.modelsFor()
            AlertDialog.Builder(this).setTitle("动态模型（${models.length()}）").setMessage(
                (0 until models.length()).joinToString("\n") {
                    val m = models.getJSONObject(it)
                    val rate = listOf("multiplier", "rate", "倍率", "inputMultiplier", "outputMultiplier")
                        .firstOrNull { k -> m.has(k) && !m.isNull(k) }?.let { k -> "倍率 ${m.get(k)}" } ?: "倍率：上游未提供"
                    "${m.str("id")} · $rate · 输入 ${m.optLong("context_window")} / 输出 ${m.optLong("max_output_tokens")}"
                }.ifBlank { "登录后更新模型列表" }
            ).setPositiveButton("关闭", null).show()
        }
        button("监听和保活设置") { settings() }
        button("清除全部账号") {
            AlertDialog.Builder(this).setMessage("清除本机所有账号凭据和模型缓存？")
                .setPositiveButton("全部清除") { _, _ ->
                    polling = false; handler.removeCallbacks(poll)
                    Store.savePending(null); Store.clearAccounts(); refresh()
                }.setNegativeButton("取消", null).show()
        }

        log = text("", 12f)
        Logs.listener = { runOnUiThread { if (!destroyed) log.text = Logs.dump() } }
        refresh()
        try {
            Store.pending()?.let {
                if (System.currentTimeMillis() / 1000 - it.optLong("created") < 600) {
                    global.isChecked = it.optBoolean("global")
                    showLink(it)
                }
            }
        } catch (_: Exception) { }
    }

    private fun manageAccounts() {
        val list = Store.accounts
        if (list.isEmpty()) {
            AlertDialog.Builder(this).setMessage("还没有登录任何账号").setPositiveButton("知道了", null).show()
            return
        }
        val labels = list.map { a ->
            val mark = if (a.key == Store.active?.key) "✓ " else "　"
            "$mark[${a.regionLabel}] ${a.displayName}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("选择要使用的账号")
            .setItems(labels) { _, index ->
                val target = list[index]
                Store.setActive(target)
                Logs.add("已切换到 ${target.regionLabel} 账号：${target.displayName}")
                refresh()
                work { Upstream.models() }
                deleteOption(target)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun deleteOption(target: Account) {
        AlertDialog.Builder(this)
            .setMessage("当前使用：[${target.regionLabel}] ${target.displayName}\n要删除这个账号吗？")
            .setPositiveButton("删除该账号") { _, _ ->
                Store.removeAccount(target)
                Logs.add("已删除账号：${target.displayName}")
                refresh()
            }
            .setNegativeButton("保留", null)
            .show()
    }

    /** 国际版等场景下上游目录为空时，允许直接填写模型 ID。 */
    private fun addManualModel() {
        val a = Store.active
        if (a == null) {
            status.text = "请先登录"
            return
        }
        val current = Store.manualFor(a)
        val input = EditText(this).apply {
            hint = "每行一个模型 ID，例如 claude-sonnet-4.5"
            setText(current.joinToString("\n"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
        }
        AlertDialog.Builder(this)
            .setTitle("手动模型（${a.regionLabel}）")
            .setMessage("上游目录没有返回模型时，可在这里填写模型 ID，保存后会出现在 /v1/models 里。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val ids = input.text.toString().split('\n')
                Store.saveManualModels(a, ids)
                Logs.add("已保存 ${Store.manualFor(a).size} 个手动模型")
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLink(p: JSONObject) {
        val region = if (p.optBoolean("global")) "国际版" else "国内版"
        link.text = "[$region]\n" + p.str("authUrl")
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        status.text = "等待浏览器授权，最多 10 分钟…"
        handler.post(poll)
    }

    private fun work(action: () -> Unit) {
        status.text = "处理中…"
        worker.execute {
            try {
                action()
                runOnUiThread { refresh() }
            } catch (e: Throwable) {
                val message = safeMessage(e)
                Logs.add(message)
                runOnUiThread { refresh(); status.text = message }
            }
        }
    }

    private fun refresh() {
        val a = Store.active
        // 开关反映当前使用的账号区域；程序化同步不触发切换逻辑。
        if (::global.isInitialized) {
            suppressToggle = true
            global.isChecked = a?.global ?: global.isChecked
            suppressToggle = false
        }
        val credit = Store.credits?.toString() ?: "未查询"
        val accountLine = when {
            a == null -> "账号：未登录"
            else -> "账号：[${a.regionLabel}] ${a.displayName}（uid ${a.uid.ifBlank { "-" }}）"
        }
        status.text = buildString {
            appendLine("服务：${if (RelayService.running) "运行中" else "已停止"}")
            appendLine(accountLine)
            appendLine("积分：$credit${if (Store.lastCheckin.isNotBlank()) " · ${Store.lastCheckin}" else ""}")
            appendLine("模型：${Store.modelsFor().length()} 个")
            append("http://127.0.0.1:${Store.config.port}/v1")
        }
        if (Store.accounts.isEmpty()) {
            accountsView.text = "尚未登录。可分别登录国内版与国际版各一个账号。"
        } else {
            accountsView.text = Store.accounts.joinToString("\n") { acc ->
                val mark = if (acc.key == Store.active?.key) "● 使用中" else "○"
                "$mark [${acc.regionLabel}] ${acc.displayName}"
            } + "\n共 ${Store.accounts.size} 个账号"
        }
        if (::log.isInitialized) log.text = Logs.dump()
    }

    private fun text(value: String, size: Float): TextView =
        TextView(this).apply { text = value; textSize = size; setPadding(0, 10, 0, 10); panel.addView(this) }

    private fun button(label: String, action: () -> Unit) {
        panel.addView(Button(this).apply { text = label; setOnClickListener { action() } })
    }

    private fun copy(label: String, value: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "已复制 $label", Toast.LENGTH_SHORT).show()
    }

    private fun settings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 10, 28, 10) }
        val port = EditText(this).apply { setText(Store.config.port.toString()); inputType = InputType.TYPE_CLASS_NUMBER; hint = "端口" }
        val lan = Switch(this).apply { text = "允许局域网访问（需 API Key）"; isChecked = Store.config.allowLan }
        val audio = Switch(this).apply { text = "可选静音音频保活"; isChecked = Store.config.keepAliveAudio }
        box.addView(port); box.addView(lan); box.addView(audio)
        AlertDialog.Builder(this).setTitle("保存后重启服务生效").setView(box)
            .setPositiveButton("保存") { _, _ ->
                val p = port.text.toString().toIntOrNull()
                if (p == null || p !in 1024..65535) {
                    status.text = "端口需在 1024–65535"
                } else {
                    Store.config.port = p
                    Store.config.allowLan = lan.isChecked
                    Store.config.keepAliveAudio = audio.isChecked
                    Store.saveConfig()
                    refresh()
                }
            }.setNegativeButton("取消", null).show()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refresh()
    }

    override fun onDestroy() {
        destroyed = true
        polling = false
        handler.removeCallbacksAndMessages(null)
        Logs.listener = null
        worker.shutdown()
        super.onDestroy()
    }
}
