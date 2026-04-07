package cn.xihan.qdds

import com.alibaba.fastjson2.JSON
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam
import java.io.File
import java.security.MessageDigest

enum class HookStatus {
    Pending,
    Hooked,
    MissingSymbol,
    Failed,
}

enum class InjectionStatus {
    Succeeded,
    Skipped,
    Failed,
}

data class HookFeatureId(
    val key: String,
    val displayName: String,
) {
    companion object {
        fun named(key: String, displayName: String) = HookFeatureId(key, displayName)

        fun fromTitle(group: String, title: String) = HookFeatureId(
            key = "$group:${stableHash("$group::$title")}",
            displayName = title
        )
    }
}

data class HookDiagnostic(
    val featureId: HookFeatureId,
    val packageName: String,
    val processName: String,
    val versionCode: Int,
    val status: HookStatus,
    val reason: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

data class InjectionDiagnostic(
    val stage: String,
    val packageName: String,
    val processName: String,
    val versionCode: Int,
    val status: InjectionStatus,
    val reason: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

object HookFeatures {
    val ModuleSettingsEntry = HookFeatureId.named("main.module_settings_entry", "Module settings entry")
    val StartPermissions = HookFeatureId.named("main.start_permissions", "启动前权限检查")
    val PostImageUrl = HookFeatureId.named("main.post_image_url", "发帖显示图片直链")
    val UnlockMemberBackground = HookFeatureId.named("main.unlock_member_background", "解锁会员卡专属背景")
    val FreeAdReward = HookFeatureId.named("main.free_ad_reward", "免广告领取奖励")
    val IgnoreFreeSubscribeLimit = HookFeatureId.named("main.ignore_free_subscribe_limit", "忽略限免批量订阅限制")
    val ExportEmoji = HookFeatureId.named("main.export_emoji", "一键导出表情包")
    val OldDailyRead = HookFeatureId.named("main.old_daily_read", "启用旧版每日导读")
    val DefaultImei = HookFeatureId.named("main.default_imei", "启用默认IMEI")
    val Cookie = HookFeatureId.named("main.cookie", "Cookie")
    val Debug = HookFeatureId.named("main.debug", "调试模式")
    val ReadingTimeSpeedFactor = HookFeatureId.named("read.reading_time_speed_factor", "阅读时间加倍")
    val RedirectReadingPageBackground = HookFeatureId.named("read.redirect_background_path", "重定向阅读页背景路径")
    val RedirectLocalStartImage = HookFeatureId.named("start.redirect_local_image", "重定向本地启动图")
    val SearchOption = HookFeatureId.named("view.search_option", "搜索配置")
    val HideBottom = HookFeatureId.named("view.hide_bottom", "主页底部导航")
    val BookDetailHide = HookFeatureId.named("view.book_detail_hide", "书籍详情")
    val ReadingPageChapter = HookFeatureId.named("read.page_chapter", "阅读页面章节相关")
    val ReadBookLastPage = HookFeatureId.named("read.book_last_page", "阅读页最后一页")
    val HideReadPage = HookFeatureId.named("view.hide_read_page", "阅读页面-隐藏控件")
    val CustomStartImage = HookFeatureId.named("start.custom_image", "自定义启动图")
    val CaptureOfficialLaunchMapList = HookFeatureId.named("start.capture_official_launch_map_list", "抓取官方启动图列表")
    val CustomBookShelfTopImage = HookFeatureId.named("bookshelf.custom_top_image", "自定义书架顶部图片")
    val QuickShield = HookFeatureId.named("shield.quick_shield", "书籍详情-快速屏蔽")
    val ChapterEndAd = HookFeatureId.named("adv.chapter_end", "阅读页-章末相关")
    val AsyncInitTask = HookFeatureId.named("intercept.async_init_task", "异步初始化任务")
}

object HookDiagnostics {
    const val MIN_API_VERSION = 101
    private const val INJECTION_STATUS_FILE_NAME = "hook_injection_status.json"

    private val registry = linkedMapOf<String, HookDiagnostic>()

    var sessionPackageName by mutableStateOf("")
        private set
    var sessionProcessName by mutableStateOf("")
        private set
    var sessionVersionCode by mutableStateOf(0)
        private set
    var sessionMode by mutableStateOf("legacy")
        private set
    var sessionActive by mutableStateOf(false)
        private set
    var diagnostics by mutableStateOf<List<HookDiagnostic>>(emptyList())
        private set
    var injectionStatus by mutableStateOf(loadInjectionDiagnostic())
        private set

    @Synchronized
    fun beginSession(
        packageName: String,
        versionCode: Int,
        mode: String = sessionMode,
        processName: String = sessionProcessName,
    ) {
        if (
            sessionPackageName != packageName ||
            sessionProcessName != processName ||
            sessionVersionCode != versionCode ||
            !sessionActive
        ) {
            registry.clear()
            diagnostics = emptyList()
        }
        sessionPackageName = packageName
        sessionProcessName = processName
        sessionVersionCode = versionCode
        sessionMode = mode
        sessionActive = true
    }

    @Synchronized
    fun markPending(featureId: HookFeatureId) {
        update(featureId, HookStatus.Pending)
    }

    @Synchronized
    fun markHooked(featureId: HookFeatureId) {
        update(featureId, HookStatus.Hooked)
    }

    @Synchronized
    fun markThrowable(featureId: HookFeatureId, throwable: Throwable) {
        val status = if (throwable.isMissingSymbol()) HookStatus.MissingSymbol else HookStatus.Failed
        val reason = throwable.toDiagnosticReason()
        update(featureId, status, reason)
        YLog.error(
            msg = "${featureId.displayName}[$status]: $reason",
            tag = YLog.Configs.tag
        )
    }

    @Synchronized
    fun recordInjection(
        stage: String,
        status: InjectionStatus,
        packageName: String = sessionPackageName.ifBlank { Option.targetPackageName() },
        processName: String = sessionProcessName.ifBlank { "unknown" },
        versionCode: Int = sessionVersionCode,
        reason: String = "",
    ) {
        val diagnostic = InjectionDiagnostic(
            stage = stage,
            packageName = packageName,
            processName = processName,
            versionCode = versionCode,
            status = status,
            reason = reason,
            updatedAt = System.currentTimeMillis()
        )
        injectionStatus = diagnostic
        runCatching {
            val file = File("${Option.basePath}/$INJECTION_STATUS_FILE_NAME")
            file.parentFile?.mkdirs()
            file.writeText(JSON.toJSONString(diagnostic))
        }.onFailure {
            YLog.error(
                msg = "persist injection diagnostic failed: ${it.toDiagnosticReason()}",
                tag = YLog.Configs.tag
            )
        }
    }

    @Synchronized
    fun refreshInjectionStatus() {
        injectionStatus = loadInjectionDiagnostic()
    }

    @Synchronized
    private fun update(featureId: HookFeatureId, status: HookStatus, reason: String = "") {
        registry[featureId.key] = HookDiagnostic(
            featureId = featureId,
            packageName = sessionPackageName,
            processName = sessionProcessName,
            versionCode = sessionVersionCode,
            status = status,
            reason = reason,
            updatedAt = System.currentTimeMillis()
        )
        diagnostics = registry.values.sortedBy { it.featureId.displayName }
    }

    private fun loadInjectionDiagnostic(): InjectionDiagnostic? = runCatching {
        val file = File("${Option.basePath}/$INJECTION_STATUS_FILE_NAME")
        if (!file.exists()) return null
        JSON.parseObject(file.readText(), InjectionDiagnostic::class.java)
    }.getOrNull()
}

inline fun trackHookFeature(featureId: HookFeatureId, block: () -> Unit) {
    HookDiagnostics.markPending(featureId)
    runCatching {
        block()
    }.onSuccess {
        HookDiagnostics.markHooked(featureId)
    }.onFailure {
        HookDiagnostics.markThrowable(featureId, it)
    }
}

inline fun PackageParam.trackFeature(featureId: HookFeatureId, block: PackageParam.() -> Unit) =
    trackHookFeature(featureId) { block(this) }

inline fun PackageParam.trackFeature(
    key: String,
    displayName: String,
    block: PackageParam.() -> Unit,
) = trackHookFeature(HookFeatureId.named(key, displayName)) { block(this) }

inline fun PackageParam.trackSelectedFeature(
    group: String,
    title: String,
    block: PackageParam.() -> Unit,
) = trackHookFeature(HookFeatureId.fromTitle(group, title)) { block(this) }

private fun stableHash(value: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
    return digest.joinToString(separator = "") { "%02x".format(it) }.take(12)
}

private fun Throwable.isMissingSymbol(): Boolean =
    generateSequence(this) { it.cause }.any { throwable ->
        val className = throwable::class.java.name
        val message = throwable.message.orEmpty()
        className.contains("ClassNotFoundException") ||
                className.contains("NoSuchMethod") ||
                className.contains("NoSuchField") ||
                className.contains("NotFound") ||
                message.contains("not found", ignoreCase = true) ||
                message.contains("no such", ignoreCase = true) ||
                message.contains("cannot find", ignoreCase = true) ||
                message.contains("failed to find", ignoreCase = true)
    }

fun Throwable.toDiagnosticReason(): String =
    generateSequence(this) { it.cause }
        .mapNotNull { throwable ->
            val name = throwable::class.java.simpleName.ifBlank { throwable::class.java.name }
            val message = throwable.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
            if (message.isBlank()) name else "$name: $message"
        }
        .firstOrNull()
        ?: "未知异常"
