package dev.mineport.launcher.performance

import android.content.Context
import android.os.Debug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 轻量性能采样器。
 *
 * 只采「不依赖系统权限就能读到」的指标：
 * - Java 堆 / native 堆（Debug.getNativeHeapAllocatedSize）
 * - 进程线程数（/proc/self/status 的 Threads）
 * - 进程 CPU 时间（/proc/self/stat 的 utime+stime，按 100Hz 折算秒）
 *
 * 不在这里统计帧率：游戏的 GL 渲染在 JVM 侧（LWJGL/GL4ES），宿主 View 不产帧，
 * Choreographer 量到的是空转的 UI 帧率，会给出错误结论。帧率用
 * `adb shell dumpsys SurfaceFlinger --latency <surface>` 量。
 *
 * 每次采样追加一行 JSON 到 filesDir/perf-<tag>.log，便于导出后画曲线。
 */
object PerfSampler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var logFile: File? = null
    private var lastCpuTicks: Long = -1
    private var lastAt: Long = 0

    @Volatile
    var lastLine: String? = null
        private set

    /** 开始采样；重复调用会先停掉上一次。 */
    fun start(context: Context, tag: String = "game", intervalMs: Long = 2000L) {
        stop()
        logFile = File(context.filesDir, "perf-" + tag + ".log").apply {
            parentFile?.mkdirs()
            runCatching { appendText("") }
        }
        lastCpuTicks = -1
        lastAt = 0
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                val line = runCatching { sample() }.getOrNull() ?: continue
                lastLine = line
                withContext(Dispatchers.IO) {
                    runCatching { logFile?.appendText(line + "\n") }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun sample(): String {
        val rt = Runtime.getRuntime()
        val javaUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val nativeMb = runCatching { Debug.getNativeHeapAllocatedSize() / (1024 * 1024) }.getOrDefault(-1L)
        val threads = readThreads()
        val cpuTicks = readCpuTicks()
        val now = System.currentTimeMillis()
        val cpuPercent = if (cpuTicks >= 0 && lastCpuTicks >= 0 && lastAt > 0 && now > lastAt) {
            // utime/stime 以 100Hz 计，除以经过的秒数即为占单核百分比。
            ((cpuTicks - lastCpuTicks).toDouble() / 100.0) / ((now - lastAt) / 1000.0) * 100.0
        } else -1.0
        lastCpuTicks = cpuTicks
        lastAt = now

        return buildString {
            append('{')
            append("\"t\":").append(now)
            append(",\"javaMb\":").append(javaUsedMb)
            append(",\"nativeMb\":").append(nativeMb)
            append(",\"threads\":").append(threads)
            append(",\"cpuPct\":").append(String.format("%.1f", cpuPercent))
            append('}')
        }
    }

    private fun readThreads(): Int =
        runCatching {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("Threads:") }
                    ?.substringAfter(':')?.trim()?.toIntOrNull()
            }
        }.getOrNull() ?: -1

    private fun readCpuTicks(): Long =
        runCatching {
            val stat = File("/proc/self/stat").readText()
            // 第 14、15 字段（comm 里可能有空格，所以从最后一个 ')' 之后切）。
            val tail = stat.substringAfterLast(')').trim().split(" ")
            // tail[0] 是第 3 字段 state，因此 utime 在 tail[11]、stime 在 tail[12]。
            val utime = tail.getOrNull(11)?.toLongOrNull() ?: 0L
            val stime = tail.getOrNull(12)?.toLongOrNull() ?: 0L
            utime + stime
        }.getOrDefault(-1L)
}
