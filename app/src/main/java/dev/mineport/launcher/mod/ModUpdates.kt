package dev.mineport.launcher.mod

import java.io.File

/**
 * 已装模组的更新检测。
 *
 * 只做启发式判断：Modrinth 上该 slug 的最新 `version_number` 是否出现在文件名里。
 * 拿不到最新版本（slug 猜错、网络失败、项目不存在）时一律当作「未知」，
 * 不误报「可更新」——宁可漏报也不给用户假情报。
 */
object ModUpdates {

    data class Status(
        val fileName: String,
        val slug: String,
        /** 查不到时为 null（视为未知，不判为可更新）。 */
        val latest: String?,
    )

    /**
     * 从 jar 文件名推 slug：
     * `sodium-fabric-0.5.11.jar` → `sodium`
     * `iris-1.7.0+mc1.20.1.jar` → `iris`
     * 没有分隔符时整段当 slug。
     */
    internal fun slugOf(fileName: String): String {
        val base = fileName.removeSuffix(".jar")
        val cut = base.indexOf('-')
        return if (cut > 0) base.substring(0, cut) else base
    }

    /**
     * 逐个查最新版本。
     *
     * @param limit 最多查几个（Modrinth 是公共 API，别一口气发几十个请求）
     */
    suspend fun check(modsDir: File, limit: Int = 8): List<Status> {
        val jars = modsDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }
            ?.sortedBy { it.name }
            ?: return emptyList()
        return jars.take(limit).map { jar ->
            val slug = slugOf(jar.name)
            val latest = runCatching {
                ModrinthClient.allVersions(slug).firstOrNull()?.version_number
            }.getOrNull()
            Status(jar.name, slug, latest)
        }
    }

    /** 文件名里不含最新版本号 → 视为可更新；未知一律 false。 */
    fun isOutdated(status: Status): Boolean {
        val latest = status.latest ?: return false
        if (latest.isBlank()) return false
        return !status.fileName.contains(latest)
    }

    /** 给 UI 用的一句话结论。 */
    fun summarize(statuses: List<Status>): String {
        if (statuses.isEmpty()) return "没有可检测的模组"
        val outdated = statuses.count { isOutdated(it) }
        val unknown = statuses.count { it.latest == null }
        return when {
            outdated == 0 && unknown == 0 -> "全部已是最新"
            outdated == 0 -> "没有发现新版本（${unknown} 个无法确认）"
            else -> "$outdated 个模组有新版本" + if (unknown > 0) "（${unknown} 个无法确认）" else ""
        }
    }
}
