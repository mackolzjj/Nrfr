package com.github.nrfr

import android.app.Application
import android.util.Log
import com.github.nrfr.manager.RebootResync
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku

/**
 * 进程级常驻入口。
 *
 * 以前回写监听只注册在 MainActivity 里，App 一退到后台/进程被冻结，
 * Shizuku 连上时没有任何组件能捕获，这就是"重启后失效、点开 App 又好了"的根因。
 *
 * 这里改用 Shizuku 的 sticky binder 监听：
 * - 只要 App 进程活着（哪怕是被开机广播/闹钟拉起的），监听就常驻；
 * - sticky 语义保证注册时若 Shizuku 已就绪会立即回调，不依赖"恰好在连接那一刻注册到"。
 */
class NrfrApplication : Application() {
    private val binderListener = Shizuku.OnBinderReceivedListener {
        // Shizuku binder 就绪/已就绪 -> 请求一次开机回写（内部按 BOOT_COUNT 去重）
        RebootResync.triggerResyncNow(this)
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead")
    }

    override fun onCreate() {
        super.onCreate()
        HiddenApiBypass.addHiddenApiExemptions("L")
        HiddenApiBypass.addHiddenApiExemptions("I")

        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        }.onFailure { Log.w(TAG, "register Shizuku listeners failed", it) }

        // 兜底：若注册时已就绪但 sticky 回调因时序未触发，主动检查一次
        RebootResync.triggerResyncNow(this)
    }

    companion object {
        private const val TAG = "NrfrApp"
    }
}
