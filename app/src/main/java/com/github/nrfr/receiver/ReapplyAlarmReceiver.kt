package com.github.nrfr.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.nrfr.manager.RebootResync

/**
 * 兜底闹钟接收器。
 * 进程被冻结/杀掉后，闹钟会把进程重新拉起，Application.onCreate 会重新注册
 * Shizuku sticky 监听；这里再主动请求一次回写，形成闭环。
 */
class ReapplyAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_REAPPLY) return
        RebootResync.triggerResyncNow(context.applicationContext)
    }

    companion object {
        const val ACTION_REAPPLY = "com.github.nrfr.action.REAPPLY_OVERRIDES"
    }
}
