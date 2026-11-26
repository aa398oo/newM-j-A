package dev.mineport.launcher.download

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class DownloadTask(
    val label: String,
    val urls: List<String>,
    val target: File,
    val sha1: String? = null
)

data class DownloadOutcome(
    val task: DownloadTask,
    val ok: Boolean,
    val error: String? = null
)

/**
 * 文件级并发队列（DOWNLOADS.md 第九节）。
 *
 * - 初始 8 路并发，用 Semaphore 控制，不走裸线程池；
 * - 单个文件失败只影响它自己（换源由 FileDownloader 负责），队列继续；
 * - 每个任务失败后再试一次，两次都失败才记错误，便于「重试此文件/跳过」。
 */
class DownloadQueue(concurrency: Int = 8) {

    private val permits = Semaphore(concurrency.coerceIn(1, 16))

    suspend fun run(
        tasks: List<DownloadTask>,
        onProgress: (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> }
    ): List<DownloadOutcome> = coroutineScope {
        val total = tasks.size
        val done = AtomicInteger(0)
        tasks.map { task ->
            async {
                permits.withPermit {
                    var outcome = attempt(task)
                    if (!outcome.ok) outcome = attempt(task)
                    onProgress(done.incrementAndGet(), total, task.label)
                    outcome
                }
            }
        }.awaitAll()
    }

    private suspend fun attempt(task: DownloadTask): DownloadOutcome {
        val result = FileDownloader.download(task.urls, task.target, task.sha1)
        return if (result.isSuccess) {
            DownloadOutcome(task, ok = true)
        } else {
            DownloadOutcome(task, ok = false, error = result.exceptionOrNull()?.message)
        }
    }
}
