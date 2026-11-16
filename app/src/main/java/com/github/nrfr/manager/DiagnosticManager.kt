package com.github.nrfr.manager

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.github.nrfr.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一键采集诊断报告，便于在新机型（如小米 HyperOS 4）上定位 dumpsys 格式不兼容问题。
 * 报告写入应用 cache 目录，通过 FileProvider 分享。
 */
object DiagnosticManager {
    private const val FILE_AUTHORITY_SUFFIX = ".fileprovider"

    /** 采集纯文本报告。可直接在后台线程调用。 */
    fun collectReport(context: Context): String {
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        sb.appendLine("===== Nrfr 诊断报告 =====")
        sb.appendLine("生成时间: $now")
        sb.appendLine("App 版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("设备厂商: ${Build.MANUFACTURER}")
        sb.appendLine("设备型号: ${Build.MODEL} / ${Build.DEVICE} / ${Build.PRODUCT}")
        sb.appendLine("系统版本: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Shizuku Binder: ${ShizukuHelper.isBinderAvailable()}")
        sb.appendLine("Shizuku 已授权: ${ShizukuHelper.hasPermission()}")
        sb.appendLine()

        sb.appendLine("===== 已解析的 SIM 卡 =====")
        val sims = runCatching { CarrierConfigManager.getSimCards(context) }.getOrNull()
        if (sims.isNullOrEmpty()) {
            sb.appendLine("（未解析到 SIM 卡，可能是 dumpsys 格式不兼容或未授权 Shizuku）")
        } else {
            sims.forEach { sim ->
                sb.appendLine("SIM${sim.slot}: subId=${sim.subId}, 名称=${sim.carrierName}")
                sb.appendLine("  覆盖配置: ${sim.currentConfig}")
            }
        }
        sb.appendLine()

        sb.appendLine("===== dumpsys isub =====")
        sb.appendLine(CarrierConfigManager.captureShellForDiagnostics("dumpsys isub"))
        sb.appendLine()

        sb.appendLine("===== dumpsys carrier_config =====")
        sb.appendLine(CarrierConfigManager.captureShellForDiagnostics("dumpsys carrier_config"))
        sb.appendLine()

        sb.appendLine("===== dumpsys telephony.registry =====")
        sb.appendLine(CarrierConfigManager.captureShellForDiagnostics("dumpsys telephony.registry"))
        sb.appendLine()

        sb.appendLine("===== 报告结束 =====")
        return sb.toString()
    }

    /**
     * 采集报告写入 cache 文件并返回分享 Intent（ACTION_SEND）。
     * 调用方用 startActivity(Intent.createChooser(...)) 即可。请在 IO 线程调用。
     */
    fun buildShareIntent(context: Context): Intent {
        val report = collectReport(context)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(context.cacheDir, "nrfr_diagnostic_$timestamp.txt")
        file.writeText(report, Charsets.UTF_8)
        val authority = context.packageName + FILE_AUTHORITY_SUFFIX
        val uri = FileProvider.getUriForFile(context, authority, file)

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Nrfr 诊断报告 ${Build.MODEL}")
            putExtra(Intent.EXTRA_TEXT, "Nrfr 诊断报告，请附到 Issue 中。")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, "分享诊断报告")
    }
}
