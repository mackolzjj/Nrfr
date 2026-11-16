package com.github.nrfr.manager

// 单 APK：Shizuku 直连 CarrierConfig，受限时委托 shell 权限回退；不用 am instrument shell（会 force-stop 闪退）。

import android.content.Context
import android.os.PersistableBundle
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.CarrierConfigManager as AndroidCarrierConfigManager
import com.github.nrfr.model.SimCardInfo
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object CarrierConfigManager {
    /**
     * 不同厂商 dumpsys carrier_config 的 phoneId 行格式不同：
     * AOSP/OPPO 常见 "Phone Id = 0"，MTK/小米部分机型是 "phoneId=0" 或 "phoneId: 0"。
     * 这里统一转成宽松匹配，避免换机型后整份解析失效。
     */
    private val PHONE_ID_PATTERN = Regex("""(?i)^[\s>]*phone[ _]?id\s*[=:]\s*(\d+)""")
    private val LOGICAL_SLOT_PATTERNS = listOf(
        Regex("""(?i)logical\s+sim\s+slot\s+(\d+)\s*[:=]\s*subId\s*[:=]\s*(\d+)"""),
        Regex("""(?i)slot\s*[:=]\s*(\d+)[,\s]+subId\s*[:=]\s*(\d+)"""),
        Regex("""(?i)phoneId\s*[:=]\s*(\d+)[,\s]+subId\s*[:=]\s*(\d+)""")
    )
    private const val PREFS_SESSION = "nrfr_session"
    private const val KEY_CLEARED_BOOT_COUNT = "cleared_boot_count"

    /** 本进程内对「无覆盖却有角标」再强清一次即可，避免每次刷新都阻塞。 */
    @Volatile
    private var orphanScrubDone = false

    /**
     * shell 通道熔断：新版 Shizuku 与编译时 API（13.1.5）的 newProcess 行为若不一致，
     * 子进程可能挂起导致 waitFor() 永不返回。一旦超时就熔断之后的 shell 调用，
     * 让首屏读取改用公开 API/隐藏 Binder 兜底，绝不因为一次卡死拖垮整个界面。
     */
    @Volatile
    private var shellCircuitOpenUntil = 0L
    private const val SHELL_TIMEOUT_MS = 6_000L
    private const val SHELL_COOLDOWN_MS = 60_000L

    init {
        HiddenApiBypass.addHiddenApiExemptions("L")
        HiddenApiBypass.addHiddenApiExemptions("I")
    }

    fun getSimCards(context: Context): List<SimCardInfo> {
        // 首屏只做"读取"：回写（ensureClearedAfterReboot）与清残留角标（scrubOrphanBadges）
        // 已改由 RebootResync 在后台线程调用，绝不在打开界面时同步执行，
        // 否则清角标内的多次 sleep 重试 + shell 会直接卡住首屏加载。
        //
        // isub / carrier_config 各只 dump 一次并全程复用：原实现在一次 getSimCards 中
        // 重复调用 getCurrentConfigsByDumpsys 三次，每次都是一个无超时的 shell 子进程。
        val isubSnapshot = getIsubSnapshotByDumpsys()
        val configsByPhoneId = getCurrentConfigsByDumpsys()
        runCatching { rememberLiveOverrides(context, configsByPhoneId) }

        val simCards = mutableListOf<SimCardInfo>()

        for (slotIndex in 0..1) {
            val slot = slotIndex + 1
            val subId = isubSnapshot?.slotToSubId?.get(slotIndex)
                ?: getSubIdForSlotViaApi(context, slotIndex)
                ?: continue
            val config = configsByPhoneId[slotIndex]
                ?: getCurrentConfigByLoader(context, subId)
                ?: getCurrentConfigByPublicApi(context, subId)
            val carrierName = getCarrierNameBySubId(
                context,
                subId,
                slotIndex,
                config,
                isubSnapshot
            )
            simCards.add(SimCardInfo(slot, subId, carrierName, config))
        }

        return simCards
    }

    /**
     * 每次开机后首次把已保存的卡1/卡2覆盖写回去（不清空）。
     * 同一开机周期内不重复全量回写，避免打断当前会话刚保存的设置。
     */
    fun ensureClearedAfterReboot(context: Context): Boolean {
        if (!ShizukuHelper.hasPermission()) {
            return false
        }
        val bootCount = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.BOOT_COUNT,
            -1
        )
        val prefs = context.getSharedPreferences(PREFS_SESSION, Context.MODE_PRIVATE)
        val lastCleared = prefs.getInt(KEY_CLEARED_BOOT_COUNT, -2)
        if (bootCount == lastCleared) {
            return false
        }
        // 只有真正回写成功才标记本开机周期已处理；失败则保留，等下一次
        // sticky 监听 / 闹钟再触发时重试。原实现无论成败都标记，导致失败后永不重试。
        if (!reapplySavedOverrides(context, prefs)) {
            return false
        }
        prefs.edit().putInt(KEY_CLEARED_BOOT_COUNT, bootCount).apply()
        orphanScrubDone = true
        return true
    }

    /**
     * 对全部 SIM 执行还原：清覆盖配置，并阻塞清除状态栏品牌/SPN 角标。
     */
    fun restoreAllToUnset(context: Context) {
        if (!ShizukuHelper.hasPermission()) {
            throw IllegalStateException("Shizuku 未启动或未授权")
        }
        for (slotIndex in 0..1) {
            val subId = getSubIdForSlotViaApi(context, slotIndex) ?: continue
            runCatching { resetCarrierConfig(context, subId) }
            runCatching {
                DelegatedCarrierConfigOverride.runWithShellPermission {
                    SubscriptionHiddenApi.syncAfterRestoreBlocking(context, subId)
                }
            }
            runCatching {
                SubscriptionHiddenApi.clearBrandThoroughly(context, subId)
            }
        }
    }

    private fun slotIndexForSubId(context: Context, subId: Int): Int? {
        for (slotIndex in 0..1) {
            if (getSubIdForSlotViaApi(context, slotIndex) == subId) {
                return slotIndex
            }
        }
        return getIsubSnapshotByDumpsys()?.slotToSubId?.entries
            ?.firstOrNull { it.value == subId }
            ?.key
    }

    private fun rememberSavedOverride(
        context: Context,
        subId: Int,
        countryCode: String?,
        carrierName: String?
    ) {
        val slot = slotIndexForSubId(context, subId) ?: return
        context.getSharedPreferences(PREFS_SESSION, Context.MODE_PRIVATE).edit()
            .putBoolean("slot_${slot}_set", true)
            .putString("slot_${slot}_cc", countryCode.orEmpty())
            .putString("slot_${slot}_name", carrierName.orEmpty())
            .apply()
    }

    private fun clearSavedOverride(context: Context, subId: Int) {
        val slot = slotIndexForSubId(context, subId) ?: return
        context.getSharedPreferences(PREFS_SESSION, Context.MODE_PRIVATE).edit()
            .putBoolean("slot_${slot}_set", false)
            .remove("slot_${slot}_cc")
            .remove("slot_${slot}_name")
            .apply()
    }

    private fun rememberLiveOverrides(
        context: Context,
        configsByPhoneId: Map<Int, Map<String, String>>
    ) {
        val prefs = context.getSharedPreferences(PREFS_SESSION, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var changed = false
        for (slotIndex in 0..1) {
            val config = configsByPhoneId[slotIndex] ?: continue
            val countryCode = config["国家码"].orEmpty()
            val carrierName = config["运营商名称"].orEmpty()
            if (countryCode.isEmpty() && carrierName.isEmpty()) {
                continue
            }
            editor.putBoolean("slot_${slotIndex}_set", true)
            editor.putString("slot_${slotIndex}_cc", countryCode)
            editor.putString("slot_${slotIndex}_name", carrierName)
            changed = true
        }
        if (changed) {
            editor.apply()
        }
    }

    /**
     * @return 是否至少成功回写了一个槽位。全部失败（如 subId 暂不可用、写入抛错）时返回 false，
     *         调用方据此决定是否标记本开机周期为已处理，从而支持后续重试。
     */
    private fun reapplySavedOverrides(
        context: Context,
        prefs: android.content.SharedPreferences
    ): Boolean {
        var anySuccess = false
        var hadPending = false
        for (slotIndex in 0..1) {
            if (!prefs.getBoolean("slot_${slotIndex}_set", false)) {
                continue
            }
            val subId = getSubIdForSlotViaApi(context, slotIndex)
            val countryCode = prefs.getString("slot_${slotIndex}_cc", null)?.takeIf { it.isNotEmpty() }
            val carrierName = prefs.getString("slot_${slotIndex}_name", null)?.takeIf { it.isNotEmpty() }
            if (countryCode == null && carrierName == null) {
                continue
            }
            hadPending = true
            if (subId == null) {
                // 槽位有保存的配置，但当前拿不到 subId（SIM 未就绪）→ 本轮视为失败
                continue
            }
            val ok = runCatching { setCarrierConfig(context, subId, countryCode, carrierName) }.isSuccess
            if (ok) anySuccess = true
        }
        // 没有待回写的槽位（用户未保存过配置）也视为完成，避免无意义地反复重试；
        // 有待回写槽位则必须至少一个成功才算完成。
        return !hadPending || anySuccess
    }

    /**
     * 无 CarrierConfig 覆盖时，再强清一次残留角标（本进程最多一次）。
     */
    fun scrubOrphanBadges(context: Context) {
        if (orphanScrubDone || !ShizukuHelper.hasPermission()) {
            return
        }
        val configsByPhoneId = getCurrentConfigsByDumpsys()
        var didClear = false
        for (slotIndex in 0..1) {
            val subId = getSubIdForSlotViaApi(context, slotIndex) ?: continue
            val config = configsByPhoneId[slotIndex]
                ?: getCurrentConfigByLoader(context, subId)
                ?: getCurrentConfigByPublicApi(context, subId)
            if (config.isNotEmpty()) {
                continue
            }
            didClear = true
            runCatching {
                DelegatedCarrierConfigOverride.runWithShellPermission {
                    SubscriptionHiddenApi.syncAfterRestoreBlocking(context, subId)
                }
            }
            runCatching {
                SubscriptionHiddenApi.clearBrandThoroughly(context, subId)
            }
        }
        if (didClear) {
            orphanScrubDone = true
        }
    }

    private data class IsubSnapshot(
        val slotToSubId: Map<Int, Int>,
        val subIdToCarrierName: Map<Int, String>
    )

    fun setCarrierConfig(
        context: Context,
        subId: Int,
        countryCode: String?,
        carrierName: String? = null
    ): CarrierConfigOperationResult {
        val bundle = PersistableBundle()

        if (!countryCode.isNullOrEmpty() && countryCode.length == 2) {
            bundle.putString(
                AndroidCarrierConfigManager.KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING,
                countryCode.lowercase()
            )
        }

        if (!carrierName.isNullOrEmpty()) {
            bundle.putBoolean(AndroidCarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL, true)
            bundle.putString(AndroidCarrierConfigManager.KEY_CARRIER_NAME_STRING, carrierName)
            // 与 AOSP CTS 一致，提高 SPN 覆盖成功率（尤其副卡）
            bundle.putBoolean(AndroidCarrierConfigManager.KEY_FORCE_HOME_NETWORK_BOOL, true)
        }

        overrideCarrierConfig(context, subId, bundle)
        rememberSavedOverride(context, subId, countryCode, carrierName)
        return CarrierConfigOperationResult()
    }

    fun resetCarrierConfig(context: Context, subId: Int): CarrierConfigOperationResult {
        overrideCarrierConfig(context, subId, null)
        clearSavedOverride(context, subId)
        return CarrierConfigOperationResult()
    }

    /**
     * @return true 表示走了异步 instrumentation 回退（调用方会在返回前等待配置生效）
     */
    private fun overrideCarrierConfig(
        context: Context,
        subId: Int,
        bundle: PersistableBundle?
    ): Boolean {
        var usedInstrumentation = false
        try {
            TelephonyHiddenApi.overrideConfig(subId, bundle, persistent = false)
            syncSubscriptionAfterOverride(context, subId, bundle)
            waitUntilConfigMatches(context, subId, bundle)
            return false
        } catch (error: SecurityException) {
            if (!error.message.orEmpty().contains("cannot be invoked by shell", ignoreCase = true)) {
                throw TelephonyHiddenApi.unwrapCause(error)
            }
        } catch (error: Throwable) {
            val message = TelephonyHiddenApi.unwrapCause(error).message.orEmpty()
            if (!message.contains("cannot be invoked by shell", ignoreCase = true)) {
                throw TelephonyHiddenApi.unwrapCause(error)
            }
        }

        val delegatedError = runCatching {
            DelegatedCarrierConfigOverride.overrideConfigWithFallback(
                context,
                subId,
                bundle,
                persistent = false
            )
        }
        if (delegatedError.isSuccess) {
            syncSubscriptionAfterOverride(context, subId, bundle)
            waitUntilConfigMatches(context, subId, bundle)
            return false
        }

        if (!PrivilegedCarrierConfigRunner.overrideConfig(context, subId, bundle)) {
            throw IllegalStateException("无法启动后台服务应用配置")
        }
        usedInstrumentation = true
        // instrumentation 进程会立即退出，主进程延迟再同步一次 SPN
        if (bundle == null) {
            SubscriptionHiddenApi.syncAfterRestore(context, subId)
        } else {
            SubscriptionHiddenApi.syncAfterOverride(context, subId, bundle)
        }
        if (!waitUntilConfigMatches(context, subId, bundle, timeoutMs = 20_000L)) {
            throw IllegalStateException("配置未及时生效，请稍后下拉刷新")
        }
        return usedInstrumentation
    }

    private fun waitUntilConfigMatches(
        context: Context,
        subId: Int,
        expectedBundle: PersistableBundle?,
        timeoutMs: Long = 5_000L
    ): Boolean {
        val phoneId = getPhoneIdForSubId(context, subId) ?: return true
        val expected = expectedBundle?.let { parseConfigBundle(it) } ?: emptyMap()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (configMatches(readOverrideConfigForPhoneId(context, phoneId), expected)) {
                return true
            }
            Thread.sleep(250)
        }
        return configMatches(readOverrideConfigForPhoneId(context, phoneId), expected)
    }

    private fun readOverrideConfigForPhoneId(context: Context, phoneId: Int): Map<String, String> {
        getCurrentConfigsByDumpsys()[phoneId]?.let { return it }

        val subId = getIsubSnapshotByDumpsys()?.slotToSubId?.get(phoneId)
            ?: getSubIdForSlotViaApi(context, phoneId)
            ?: return emptyMap()
        return getCurrentConfigByLoader(context, subId)
            ?: getCurrentConfigByPublicApi(context, subId)
    }

    private fun configMatches(current: Map<String, String>, expected: Map<String, String>): Boolean {
        if (expected.isEmpty()) {
            return current.isEmpty()
        }
        return expected.all { (key, value) -> current[key] == value }
    }

    private fun getPhoneIdForSubId(context: Context, subId: Int): Int? {
        getIsubSnapshotByDumpsys()?.slotToSubId?.entries?.firstOrNull { it.value == subId }?.key?.let {
            return it
        }
        for (slotIndex in 0..1) {
            if (getSubIdForSlotViaApi(context, slotIndex) == subId) {
                return slotIndex
            }
        }
        return null
    }

    private fun syncSubscriptionAfterOverride(context: Context, subId: Int, bundle: PersistableBundle?) {
        if (bundle == null) {
            SubscriptionHiddenApi.syncAfterRestore(context, subId)
        } else {
            SubscriptionHiddenApi.syncAfterOverride(context, subId, bundle)
        }
    }

    private fun ensureShizukuReady() {
        if (!ShizukuHelper.hasPermission()) {
            throw IllegalStateException("Shizuku 未启动或未授权")
        }
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun getIsubSnapshotByDumpsys(): IsubSnapshot? {
        return try {
            if (!ShizukuHelper.hasPermission()) {
                return null
            }
            parseDumpsysIsub(runShellCommand("dumpsys isub"))
        } catch (e: Exception) {
            null
        }
    }

    private fun parseDumpsysIsub(output: String): IsubSnapshot {
        val slotToSubId = mutableMapOf<Int, Int>()
        LOGICAL_SLOT_PATTERNS.forEach { pattern ->
            pattern.findAll(output).forEach { match ->
                val slot = match.groupValues[1].toIntOrNull() ?: return@forEach
                val subId = match.groupValues[2].toIntOrNull() ?: return@forEach
                slotToSubId.putIfAbsent(slot, subId)
            }
        }

        val subIdToCarrierName = mutableMapOf<Int, String>()
        output.lineSequence().forEach { line ->
            if (!line.contains("SubscriptionInfoInternal: id=")) {
                return@forEach
            }
            val subId = Regex("""\bid=(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@forEach
            val carrierName = Regex("""\bcarrierName=([^\s]+)""")
                .find(line)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            val displayName = Regex("""\bdisplayName=([^\s]+)""")
                .find(line)?.groupValues?.get(1)
                ?.replace(Regex("""\d+$"""), "")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val mcc = Regex("""\bmcc=(\d+)""").find(line)?.groupValues?.get(1)
            val mnc = Regex("""\bmnc=(\d+)""").find(line)?.groupValues?.get(1)
            val name = displayName
                ?: carrierName
                ?: resolveCarrierNameByMccMnc(
                    if (mcc != null && mnc != null) mcc + mnc.padStart(2, '0') else null
                )
            if (!name.isNullOrBlank()) {
                subIdToCarrierName[subId] = name
            }
            val slotIndex = Regex("""\bsimSlotIndex=(\d+)""")
                .find(line)?.groupValues?.get(1)?.toIntOrNull()
            if (slotIndex != null && slotIndex >= 0) {
                slotToSubId.putIfAbsent(slotIndex, subId)
            }
        }

        return IsubSnapshot(slotToSubId, subIdToCarrierName)
    }

    /**
     * 取槽位对应的 subId。按可靠性从高到低依次尝试：
     * 1. 公开 API getActiveSubscriptionInfoForSimSlotIndex
     * 2. 公开 API getActiveSubscriptionInfoList + simSlotIndex 匹配
     * 3. 隐藏静态 API SubscriptionManager.getSubId
     * 4. 隐藏 API getSubscriptionIds（部分新平台改名）
     * 小米/HyperOS 上 dumpsys 解析可能失败，这条公开 API 链路是主要兜底。
     */
    private fun getSubIdForSlotViaApi(context: Context, slotIndex: Int): Int? {
        val manager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            ?: return null
        val invalid = SubscriptionManager.INVALID_SUBSCRIPTION_ID

        // 1) 公开 API：按槽位直接取
        runCatching {
            manager.getActiveSubscriptionInfoForSimSlotIndex(slotIndex)?.subscriptionId
                ?.takeIf { it != invalid }
        }.getOrNull()?.let { return it }

        // 2) 公开 API：遍历活跃订阅，按 simSlotIndex 匹配
        runCatching {
            manager.activeSubscriptionInfoList
                ?.firstOrNull { it.simSlotIndex == slotIndex }
                ?.subscriptionId
                ?.takeIf { it != invalid }
        }.getOrNull()?.let { return it }

        // 3) 隐藏静态 API：getSubId(int)
        runCatching {
            val method = SubscriptionManager::class.java.getDeclaredMethod(
                "getSubId",
                Int::class.javaPrimitiveType
            )
            val subIds = method.invoke(null, slotIndex) as? IntArray ?: return@runCatching null
            subIds.firstOrNull { it != invalid }
        }.getOrNull()?.let { return it }

        // 4) 隐藏静态 API：getSubscriptionIds(int)（新平台改名）
        runCatching {
            val method = SubscriptionManager::class.java.getDeclaredMethod(
                "getSubscriptionIds",
                Int::class.javaPrimitiveType
            )
            val subIds = method.invoke(null, slotIndex) as? IntArray ?: return@runCatching null
            subIds.firstOrNull { it != invalid }
        }.getOrNull()?.let { return it }

        return null
    }

    private fun getCurrentConfigByLoader(context: Context, subId: Int): Map<String, String>? {
        return try {
            val config = TelephonyHiddenApi.getConfigForSubId(context.packageName, subId)
                ?: return null
            parseConfigBundle(config)
        } catch (e: Exception) {
            null
        }
    }

    private fun getCurrentConfigByPublicApi(context: Context, subId: Int): Map<String, String> {
        return try {
            val manager = context.getSystemService(AndroidCarrierConfigManager::class.java)
                ?: return emptyMap()
            val config = manager.getConfigForSubId(subId) ?: return emptyMap()
            parseConfigBundle(config)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun parseConfigBundle(config: PersistableBundle): Map<String, String> {
        val result = mutableMapOf<String, String>()

        config.getString(AndroidCarrierConfigManager.KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING)?.let {
            result["国家码"] = it
        }

        if (config.getBoolean(AndroidCarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL, false)) {
            config.getString(AndroidCarrierConfigManager.KEY_CARRIER_NAME_STRING)?.let {
                result["运营商名称"] = it
            }
        }

        return result
    }

    private fun getCurrentConfigsByDumpsys(): Map<Int, Map<String, String>> {
        return try {
            if (!ShizukuHelper.hasPermission()) {
                return emptyMap()
            }
            parseDumpsysCarrierConfig(runShellCommand("dumpsys carrier_config"))
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun parseDumpsysCarrierConfig(output: String): Map<Int, Map<String, String>> {
        val result = mutableMapOf<Int, Map<String, String>>()
        var currentPhoneId: Int? = null
        var readingOverrideConfig = false
        val rawValues = mutableMapOf<String, String>()

        fun flushCurrentConfig() {
            val phoneId = currentPhoneId ?: return
            val config = mutableMapOf<String, String>()
            rawValues["sim_country_iso_override_string"]?.takeIf { it.isNotBlank() }?.let {
                config["国家码"] = it
            }
            if (rawValues["carrier_name_override_bool"] == "true") {
                rawValues["carrier_name_string"]?.takeIf { it.isNotBlank() }?.let {
                    config["运营商名称"] = it
                }
            }
            result[phoneId] = config
            rawValues.clear()
        }

        output.lineSequence().forEach { line ->
            PHONE_ID_PATTERN.find(line)?.let { match ->
                flushCurrentConfig()
                currentPhoneId = match.groupValues[1].toIntOrNull()
                readingOverrideConfig = false
                return@forEach
            }

            val trimmed = line.trim()
            if (trimmed.startsWith("mOverrideConfigs")) {
                readingOverrideConfig = !trimmed.endsWith("null")
                if (!readingOverrideConfig) {
                    rawValues.clear()
                }
                return@forEach
            }

            if (!readingOverrideConfig) {
                return@forEach
            }

            if (trimmed.startsWith("m") && trimmed.endsWith(":")) {
                readingOverrideConfig = false
                return@forEach
            }

            val separatorIndex = trimmed.indexOf(" = ")
            if (separatorIndex > 0) {
                rawValues[trimmed.substring(0, separatorIndex)] =
                    trimmed.substring(separatorIndex + 3)
            }
        }

        flushCurrentConfig()
        return result
    }

    private fun getCarrierNameBySubId(
        context: Context,
        subId: Int,
        slotIndex: Int,
        currentConfig: Map<String, String>,
        isubSnapshot: IsubSnapshot?
    ): String {
        currentConfig["运营商名称"]?.takeIf { it.isNotBlank() }?.let { return it }

        isubSnapshot?.subIdToCarrierName?.get(subId)?.takeIf { it.isNotBlank() }?.let { return it }

        val subscriptionManager =
            context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        subscriptionManager?.let { manager ->
            val subscriptionInfo = runCatching {
                manager.getActiveSubscriptionInfo(subId)
            }.getOrNull() ?: runCatching {
                manager.getActiveSubscriptionInfoForSimSlotIndex(slotIndex)
            }.getOrNull()
            subscriptionInfo?.let { info ->
                resolveCarrierNameByMccMnc(
                    String.format("%03d%02d", info.mcc, info.mnc)
                )?.let { return it }

                info.displayName?.toString()?.trim()
                    ?.replace(Regex("""\d+$"""), "")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
            }
        }

        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return ""

        return try {
            val subTelephony = telephonyManager.createForSubscriptionId(subId)
            subTelephony.simOperatorName.takeIf { it.isNotBlank() }
                ?: resolveCarrierNameByMccMnc(subTelephony.simOperator)
                ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun resolveCarrierNameByMccMnc(operatorNumeric: String?): String? {
        if (operatorNumeric.isNullOrBlank()) {
            return null
        }

        return when {
            operatorNumeric.startsWith("46015") -> "中国广电"
            operatorNumeric.startsWith("46003") ||
                operatorNumeric.startsWith("46005") ||
                operatorNumeric.startsWith("46011") -> "中国电信"
            operatorNumeric.startsWith("46001") ||
                operatorNumeric.startsWith("46006") ||
                operatorNumeric.startsWith("46009") -> "中国联通"
            operatorNumeric.startsWith("46000") ||
                operatorNumeric.startsWith("46002") ||
                operatorNumeric.startsWith("46007") ||
                operatorNumeric.startsWith("46008") -> "中国移动"
            else -> null
        }
    }

    private fun runShellCommand(command: String): String {
        // 熔断期内不再起 shell 子进程，直接失败，让调用方走兜底读取路径。
        if (System.currentTimeMillis() < shellCircuitOpenUntil) {
            throw IllegalStateException("shell 通道暂不可用（熔断冷却中）")
        }

        val shellProcess = newShizukuProcess(arrayOf("sh", "-c", command))
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutThread = readAsync(shellProcess.inputStream, stdout)
        val stderrThread = readAsync(shellProcess.errorStream, stderr)

        // 关键修复：Shizuku 的 ShizukuRemoteProcess 未重写带超时的 waitFor(long, TimeUnit)，
        // 直接调用会走基类轮询 exitValue()，远程进程卡住时无法可靠超时。
        // 这里用独立守护线程执行阻塞式 waitFor()，主线程用 CountDownLatch 限时等待；
        // 超时则 destroyForcibly()（会路由到 Shizuku 的 destroy）并熔断后续 shell 调用。
        val done = CountDownLatch(1)
        val waitThread = Thread({
            try {
                shellProcess.waitFor()
            } catch (_: Throwable) {
            } finally {
                done.countDown()
            }
        }, "nrfr-shell-wait").apply { isDaemon = true }
        waitThread.start()

        val finished = done.await(SHELL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            runCatching { shellProcess.destroyForcibly() }
            runCatching { stdoutThread.join(500) }
            runCatching { stderrThread.join(500) }
            shellCircuitOpenUntil = System.currentTimeMillis() + SHELL_COOLDOWN_MS
            throw IllegalStateException("shell 命令超时，已终止子进程并熔断通道")
        }

        stdoutThread.join(1_000)
        stderrThread.join(1_000)

        val exitCode = shellProcess.exitValue()
        if (exitCode != 0) {
            throw IllegalStateException(
                listOf(stdout.toString(), stderr.toString())
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                    .ifBlank { "执行 shell 命令失败，退出码: $exitCode" }
            )
        }

        return stdout.toString()
    }

    private fun newShizukuProcess(command: Array<String>): Process {
        val newProcess = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        newProcess.isAccessible = true
        return newProcess.invoke(null, command, null, null) as Process
    }

    private fun readAsync(inputStream: InputStream, output: StringBuilder): Thread {
        return Thread {
            inputStream.bufferedReader().use { reader ->
                output.append(reader.readText())
            }
        }.apply { start() }
    }

    /** 供诊断导出使用：以 shell 权限执行只读 dumpsys 命令，失败时返回错误文本而非抛异常。 */
    internal fun captureShellForDiagnostics(command: String): String {
        if (!ShizukuHelper.hasPermission()) {
            return "<未授权 Shizuku，无法执行: $command>"
        }
        return try {
            runShellCommand(command)
        } catch (e: Throwable) {
            "<执行失败: ${TelephonyHiddenApi.unwrapCause(e).message ?: e.javaClass.simpleName}>"
        }
    }

    data class CarrierConfigOperationResult(
        val warnings: List<String> = emptyList()
    )
}

internal fun Throwable.toSimCardsUserMessage(): String {
    var current: Throwable = this
    while (current.cause != null) {
        current = current.cause!!
    }
    val message = current.message.orEmpty()
    if (current is SecurityException) {
        return "权限不足，请检查 Shizuku 是否已授权"
    }
    return message.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
        ?: "读取 SIM 配置失败"
}
