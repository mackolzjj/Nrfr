package com.github.nrfr.manager

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.github.nrfr.receiver.BootCleanupReceiver
import com.github.nrfr.receiver.ReapplyAlarmReceiver
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 开机回写的统一调度器。
 *
 * 解决三个问题：
 * 1. 同进程内多个触发源（sticky 监听 / Activity / 闹钟）并发时只跑一个回写；
 * 2. 进程被冻结/杀掉后，靠精确闹钟在多个时间点重新拉起进程补写；
 * 3. 每次闹钟拉起进程时，Application 会重新注册 sticky 监听，形成"再试一次"的闭环。
 *
 * 真正的"是否已回写过"判定在 CarrierConfigManager.ensureClearedAfterReboot 内按 BOOT_COUNT 去重，
 * 且只在写入成功时才标记，所以多次触发是安全的：成功后不再刷写，失败则下次继续重试。
 */
object RebootResync {
    private const val TAG = "NrfrResync"
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "nrfr-resync") }
    private val running = AtomicBoolean(false)

    /** 请求一次回写检查；同进程内并发调用会被合并。 */
    fun triggerResyncNow(context: Context) {
        if (!running.compareAndSet(false, true)) {
            Log.d(TAG, "resync already running, skip")
            return
        }
        val app = context.applicationContext
        executor.execute {
            try {
                val changed = CarrierConfigManager.ensureClearedAfterReboot(app)
                Log.i(TAG, "resync finished, applied=$changed")
            } catch (t: Throwable) {
                Log.w(TAG, "resync failed", t)
            } finally {
                running.set(false)
            }
        }
    }

    /**
     * 开机后安排多个兜底闹钟。
     * 即使 Shizuku 延迟很久才自启、或进程被冻结，闹钟也能在后续时间点重新拉起进程补写。
     */
    fun scheduleBootAlarms(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(AlarmManager::class.java) ?: return
        val canScheduleExact =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
            } else {
                true
            }

        val delaysMs = longArrayOf(60_000L, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L)
        delaysMs.forEachIndexed { index, delay ->
            val intent = Intent(app, ReapplyAlarmReceiver::class.java).apply {
                action = ReapplyAlarmReceiver.ACTION_REAPPLY
            }
            val pendingIntent = PendingIntent.getBroadcast(
                app,
                BootCleanupReceiver.JOB_ID + 100 + index,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = SystemClock.elapsedRealtime() + delay
            runCatching {
                if (canScheduleExact) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
            }.onFailure { Log.w(TAG, "schedule alarm #$index failed", it) }
        }
        Log.i(TAG, "scheduled ${delaysMs.size} resync alarms, exact=$canScheduleExact")
    }
}
