package dev.mineport.launcher.crash

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import dev.mineport.launcher.jvm.JvmLauncher
import dev.mineport.launcher.jvm.StartupTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断包：把设备信息、上次启动进度、上次 Java 崩溃与 JVM 版本打成一个文本文件，
 * 经 FileProvider 分享出去。没有 USB / adb 的用户也能把现场发给开发者。
 */
object DiagnosticsExporter {

    suspend fun export(context: Context): Result<File> = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "newM-j-A-diagnostics-$stamp.txt")
            val versionName = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "?"
            val pageSize = runCatching { JvmLauncher.nativeAbiInfo() }.getOrNull()
            val jvm = runCatching { JvmLauncher.nativeRuntimeVersion() }.getOrNull().orEmpty()

            file.writeText(
                buildString {
                    appendLine("newM-j-A 诊断包")
                    appendLine("时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                    appendLine("包名: " + context.packageName + " / 版本: " + versionName)
                    appendLine(
                        "设备: " + Build.MANUFACTURER + " " + Build.MODEL +
                            " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                    )
                    appendLine("ABI: " + Build.SUPPORTED_ABIS.joinToString())
                    appendLine("ABI/页大小: " + (pageSize ?: "?"))
                    appendLine("JVM: " + jvm.ifBlank { "未创建" })
                    appendLine()
                    appendLine("===== 上次启动进度 (startup.txt) =====")
                    appendLine(StartupTrace.read(context) ?: "（无记录）")
                    appendLine()
                    appendLine("===== 上次 Java 崩溃 (last-crash.txt) =====")
                    appendLine(CrashRecorder.read(context) ?: "（无记录）")
                }
            )
            Result.success(file)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 用系统分享面板把诊断包发出去。 */
    fun share(context: Context, file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "newM-j-A 诊断包")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享诊断包").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
