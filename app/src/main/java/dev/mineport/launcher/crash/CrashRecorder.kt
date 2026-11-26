package dev.mineport.launcher.crash

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Java 层未捕获异常的落盘记录。
 *
 * 为什么需要：真机没有 adb 时，闪退只会弹一个系统框，什么都留不下。
 * 这里把最后一次未捕获异常写到 filesDir/crash/last-crash.txt，
 * 设置页可以读出来复制。注意：native 信号崩溃（SIGSEGV/SIGILL）
 * 不走这个处理器，那种情况仍然只能靠 logcat。
 */
object CrashRecorder {

    private const val MAX_CHARS = 200_000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(app, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, "crash").apply { mkdirs() }
        val file = File(dir, "last-crash.txt")
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine("时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine("线程: " + thread.name)
            appendLine("设备: " + Build.MANUFACTURER + " " + Build.MODEL +
                " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")" +
                " / " + Build.SUPPORTED_ABIS.joinToString())
            appendLine("应用: " + context.packageName + " " + versionName(context))
            appendLine()
            appendLine(stack)
        }
        file.writeText(if (text.length > MAX_CHARS) text.take(MAX_CHARS) else text)
    }

    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    fun read(context: Context): String? {
        val file = File(File(context.filesDir, "crash"), "last-crash.txt")
        return if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    }

    fun clear(context: Context) {
        File(File(context.filesDir, "crash"), "last-crash.txt").delete()
    }
}
