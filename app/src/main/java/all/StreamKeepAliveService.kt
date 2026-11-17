package all

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.github.aixtin.nyral.MainActivity
import io.github.aixtin.nyral.R

/**
 * 流式生成保活前台服务（v8）。
 * 生成开始时启动、结束时停止：挂前台通知钉住进程，持有 PARTIAL_WAKE_LOCK +
 * WIFI_MODE_FULL_HIGH_PERF，防止息屏 Doze / WiFi 休眠冻住后台、断掉长流式连接。
 * 只做保活，不跑业务逻辑；与定时岗位的 TaskService 各管各的通知。
 */
class StreamKeepAliveService : Service() {

    companion object {
        private const val TAG = "StreamKeepAlive"
        private const val NOTIF_ID = 2002
        private const val CHANNEL_ID = "dafeiyu_stream_keepalive"
        private const val LOCK_TAG = "dafeiyu:stream-keepalive"

        /** 生成开始时启动（后台线程也可调，内部包 try，失败只记日志不抛） */
        fun start(context: Context) {
            try {
                val intent = Intent(context, StreamKeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: SecurityException) {
                android.util.Log.w(TAG, "保活服务启动失败: ${e.message}")
            }
        }

        /** 生成结束时停止 */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, StreamKeepAliveService::class.java))
            } catch (e: SecurityException) {
                android.util.Log.w(TAG, "保活服务停止失败: ${e.message}")
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireLocks()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 幂等：已在前台时重复调用无副作用；只做保活，不处理业务 intent
        startForegroundCompat()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        releaseLocks()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIF_ID)
        } catch (_: Exception) {
        }
    }

    /** 持有唤醒锁 + WiFi 高性能锁：息屏也不冻进程、不断 WiFi */
    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "唤醒锁获取失败: ${e.message}")
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "WiFi 锁获取失败: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wifiLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.sk_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = getString(R.string.sk_channel_desc)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.sk_content))
            .setContentText(getString(R.string.sk_channel_desc))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    // 前台服务启动兜底：个别 ROM 抛非标准 RuntimeException/Binder 异常时仍不崩溃，
    // 故保留宽泛捕获（氢气定案：销毁/启动兜底路径崩溃是最差结果）。
    @Suppress("TooGenericExceptionCaught")
    private fun startForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID, buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (e: Exception) {
            // 兜底：个别 ROM 类型/权限异常时降级为无类型前台服务，避免崩溃
            try {
                startForeground(NOTIF_ID, buildNotification())
            } catch (e2: Exception) {
                android.util.Log.e(TAG, "startForeground 失败: ${e2.message}")
            }
        }
    }
}
