package dev.mineport.launcher.jvm

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动里程碑。
 *
 * JVM 在 native 层 abort 时，Java 层收不到任何异常，进程直接消失，CrashRecorder
 * 也来不及写。所以每次启动前先往 filesDir/crash/startup.txt 追加一行「到了哪一阶段」：
 *
 *     start → runtime-staged → plan-ok → jvm-creating → jvm-ready → main-invoked
 *
 * 崩溃后重启应用，设置页读这个文件就能判断卡在解包、组装参数、JVM 创建还是主类执行。
 * 只追加不清空，begin() 时也保留上一次的记录，方便连续两次崩溃对照。
 */
object StartupTrace {

    private const val DIR = "crash"
    private const val FILE_NAME = "startup.txt"
    private const val MAX_LINES = 80

    fun begin(context: Context) = append(context, "start")

    fun mark(context: Context, stage: String) = append(context, stage)

    /** 返回最近若干行，最新在后。 */
    fun read(context: Context): String? {
        val file = file(context)
        if (!file.isFile) return null
        return runCatching { file.readLines().takeLast(20).joinToString("\n") }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context) = File(context.filesDir, "$DIR/$FILE_NAME")

    private fun append(context: Context, stage: String) {
        val file = file(context)
        runCatching {
            file.parentFile?.mkdirs()
            val line = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) +
                    "  " + stage + "\n"
            file.appendText(line)
            // 只留最近 MAX_LINES 行，避免长期使用后无限增长。
            val lines = file.readLines()
            if (lines.size > MAX_LINES) {
                file.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
            }
        }
    }
}
