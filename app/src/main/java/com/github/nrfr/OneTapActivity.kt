package com.github.nrfr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.github.nrfr.manager.CarrierConfigManager
import com.github.nrfr.manager.ShizukuHelper
import com.github.nrfr.ui.screens.OneTapScreen
import com.github.nrfr.ui.theme.NrfrTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku

/**
 * 桌面「一键回写」快捷入口。
 *
 * 设计目标：把「启动 Shizuku → 打开 Nrfr → 等读取 → 点保存」压缩成一次点击。
 * 流程：
 * 1. Shizuku 未运行 -> 自动打开 Shizuku App，提示用户点一次「启动」；
 * 2. sticky binder 监听保证连上那一刻立即继续（不依赖恰好在连接瞬间注册）；
 * 3. 需要授权则自动请求，权限通过后强制回写已保存的覆盖（不受开机去重限制）；
 * 4. 全程在状态界面显示进度与结果。
 */
class OneTapActivity : ComponentActivity() {

    private var stage by mutableStateOf(OneTapStage.CHECKING)
    private var detail by mutableStateOf("")
    private var reapplyRunning = false
    private var permissionRequested = false

    private val binderListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { advance() }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            if (stage != OneTapStage.SUCCESS) {
                stage = OneTapStage.WAITING_FOR_SHIZUKU
            }
        }
    }
    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            runOnUiThread {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    advance()
                } else {
                    stage = OneTapStage.FAILED
                    detail = "未获得 Shizuku 授权，请在弹窗中允许。"
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        HiddenApiBypass.addHiddenApiExemptions("L")
        HiddenApiBypass.addHiddenApiExemptions("I")

        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        }

        setContent {
            NrfrTheme {
                OneTapScreen(
                    stage = stage,
                    detail = detail,
                    onOpenShizuku = { openShizuku() },
                    onRequestPermission = { requestPermission() },
                    onRetry = { retry() },
                    onOpenMain = { openMainApp() },
                    onDone = { finish() }
                )
            }
        }

        advance()
    }

    /** 根据当前 Shizuku 状态推进到下一阶段；可重复调用，内部自带去重。 */
    private fun advance() {
        if (reapplyRunning || stage == OneTapStage.SUCCESS) return

        if (!ShizukuHelper.isInstalled(this)) {
            stage = OneTapStage.NOT_INSTALLED
            return
        }
        if (!ShizukuHelper.isBinderAvailable()) {
            // 自动拉起 Shizuku，然后停在等待态；binder 连上时 sticky 监听会回调 advance()
            stage = OneTapStage.WAITING_FOR_SHIZUKU
            openShizuku()
            return
        }
        if (!ShizukuHelper.hasPermission()) {
            stage = OneTapStage.NEED_PERMISSION
            requestPermission()
            return
        }
        startReapply()
    }

    private fun openShizuku() {
        val ok = ShizukuHelper.openApp(this)
        if (!ok) {
            stage = OneTapStage.NOT_INSTALLED
        }
    }

    private fun requestPermission() {
        if (!ShizukuHelper.isBinderAvailable()) {
            stage = OneTapStage.WAITING_FOR_SHIZUKU
            openShizuku()
            return
        }
        // 自动请求一次；权限结果通过 permissionListener 接收。
        if (!permissionRequested) {
            permissionRequested = true
            ShizukuHelper.requestPermissionIfNeeded(PERM_REQUEST_CODE)
        }
    }

    private fun startReapply() {
        if (reapplyRunning) return
        if (!CarrierConfigManager.hasSavedOverrides(applicationContext)) {
            stage = OneTapStage.NO_SAVED_CONFIG
            return
        }
        reapplyRunning = true
        stage = OneTapStage.REAPPLYING
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CarrierConfigManager.forceReapplySavedOverrides(applicationContext) }
                    .getOrDefault(false)
            }
            reapplyRunning = false
            stage = if (result) OneTapStage.SUCCESS else OneTapStage.FAILED
            if (!result) detail = "回写未完成，请确认 SIM 已就绪后重试。"
        }
    }

    private fun retry() {
        permissionRequested = false
        detail = ""
        reapplyRunning = false
        stage = OneTapStage.CHECKING
        advance()
    }

    private fun openMainApp() {
        runCatching {
            packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        }
    }

    override fun onDestroy() {
        runCatching {
            Shizuku.removeBinderReceivedListener(binderListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        }
        super.onDestroy()
    }

    companion object {
        private const val PERM_REQUEST_CODE = 2048
    }
}

enum class OneTapStage {
    CHECKING,
    NOT_INSTALLED,
    WAITING_FOR_SHIZUKU,
    NEED_PERMISSION,
    REAPPLYING,
    SUCCESS,
    NO_SAVED_CONFIG,
    FAILED
}
