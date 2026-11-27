package vip.workbuddyrelay.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** 前台服务：持有本机 HTTP 服务，避免 App 退到后台后被回收。 */
class RelayService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var keepAlive: MediaPlayer? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cfg = Store.config
        val notification = buildNotification(cfg)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFY_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFY_ID, notification)
        }
        if (server == null) {
            server = RelayServer()
            if (!server!!.start(cfg)) {
                stopSelf()
                return START_NOT_STICKY
            }
            acquireWakeLock()
            if(cfg.keepAliveAudio) startKeepAliveAudio()
            Thread({
                server?.refreshModels()
            }, "warmup-catalog").apply { isDaemon = true }.start()
        }
        running = true
        return START_STICKY
    }

    /**
     * ColorOS / MIUI 会即时冻结纯后台进程（连前台服务也不放过），但一般不冻结正在播放音频的应用。
     * 这里循环播放一段 8kHz 静音，音量置 0，不申请音频焦点，只用于让系统的媒体状态保持 active。
     */
    private fun startKeepAliveAudio() {
        try {
            if (keepAlive != null) return
            val p = MediaPlayer()
            val afd = resources.openRawResourceFd(R.raw.silence) ?: return
            p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            p.isLooping = true
            p.setVolume(0f, 0f)
            p.prepare()
            p.start()
            keepAlive = p
            Logs.add("保活静音播放已启动")
        } catch (e: Throwable) {
            Logs.add("保活音频启动失败：${e.message}")
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        running = false
        try {
            keepAlive?.stop()
            keepAlive?.release()
        } catch (_: Throwable) {
        }
        keepAlive = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WorkBuddyRelay:service").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Throwable) {
            Logs.add("WakeLock 获取失败：${e.message}")
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "WorkBuddy Relay 服务", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(cfg: Config): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            Notification.Builder(this)
        }
        val text = "监听 http://${if (cfg.allowLan) "0.0.0.0" else "127.0.0.1"}:${cfg.port}/v1"
        return builder
            .setContentTitle("WorkBuddy Relay 运行中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "workbuddy_relay"
        private const val NOTIFY_ID = 1001

        @Volatile var server: RelayServer? = null
        @Volatile var running = false

        fun start(ctx: Context) {
            val i = Intent(ctx, RelayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RelayService::class.java))
        }
    }
}
