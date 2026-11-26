package dev.mineport.launcher.jvm

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * 把打包进 APK 的运行时（`assets/runtime` 下所有文件）解到 `filesDir/runtime`。
 *
 * 为什么必须解出来：JVM 的 `libjvm.so` 要被 `dlopen` 按绝对路径加载，
 * asset 里的条目不是普通文件；`lib/modules` 也必须以真实路径出现在 `java.home` 下。
 *
 * 幂等：用「APK 安装时间」作为 stamp，安装包没变就跳过（JRE 几百 MB，不能每次启动都拷）。
 */
object RuntimeStager {

    private const val ASSET_ROOT = "runtime"
    private const val STAMP = ".staged"
    private const val TAG = "MineportRuntime"
    private const val BUFFER = 64 * 1024

    data class StageResult(val copied: Int, val bytes: Long, val skipped: Boolean)

    data class State(val staged: Boolean, val libjvmPresent: Boolean, val modulePresent: Boolean)

    fun ensure(context: Context, targetRoot: File): StageResult {
        targetRoot.mkdirs()
        val stampFile = File(targetRoot, STAMP)
        val expected = stampText(context)
        if (stampFile.isFile && stampFile.readText().trim() == expected) {
            return StageResult(0, 0, skipped = true)
        }

        val assets = context.assets
        var copied = 0
        var bytes = 0L
        try {
            val result = copyTree(assets, ASSET_ROOT, targetRoot)
            copied = result.first
            bytes = result.second
        } catch (e: IOException) {
            Log.e(TAG, "解包 assets/$ASSET_ROOT 失败", e)
            return StageResult(copied, bytes, skipped = false)
        }

        if (copied > 0) {
            stampFile.writeText(expected)
        }
        Log.i(TAG, "runtime staged: $copied files, $bytes bytes -> ${targetRoot.absolutePath}")
        return StageResult(copied, bytes, skipped = false)
    }

    fun inspect(targetRoot: File): State = State(
        staged = File(targetRoot, STAMP).isFile,
        libjvmPresent = File(targetRoot, "lib/server/libjvm.so").isFile || File(targetRoot, "libjvm.so").isFile,
        modulePresent = File(targetRoot, "lib/modules").isFile
    )

    private fun stampText(context: Context): String {
        val installTime = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (e: Exception) {
            0L
        }
        return installTime.toString()
    }

    /** 返回 (文件数, 字节数)。AssetManager 没有目录判断 API，用 list() 非空当作目录。 */
    private fun copyTree(assets: AssetManager, assetPath: String, target: File): Pair<Int, Long> {
        val children = try {
            assets.list(assetPath)
        } catch (e: IOException) {
            null
        }
        if (children.isNullOrEmpty()) {
            return if (copyFile(assets, assetPath, target)) 1 to target.length() else 0 to 0L
        }

        target.mkdirs()
        var files = 0
        var bytes = 0L
        for (child in children) {
            val childAsset = "$assetPath/$child"
            val childTarget = File(target, child)
            val nested = try {
                assets.list(childAsset)
            } catch (e: IOException) {
                null
            }
            val result = if (nested.isNullOrEmpty()) {
                if (copyFile(assets, childAsset, childTarget)) 1 to childTarget.length() else 0 to 0L
            } else {
                copyTree(assets, childAsset, childTarget)
            }
            files += result.first
            bytes += result.second
        }
        return files to bytes
    }

    private fun copyFile(assets: AssetManager, assetPath: String, target: File): Boolean {
        return try {
            assets.open(assetPath).use { input ->
                target.parentFile?.mkdirs()
                target.outputStream().use { output ->
                    input.copyTo(output, BUFFER)
                }
            }
            true
        } catch (e: FileNotFoundException) {
            // 空目录会被 list() 返回空数组，走到这里说明确实是空目录，跳过即可。
            false
        } catch (e: IOException) {
            Log.w(TAG, "拷贝 $assetPath 失败", e)
            false
        }
    }
}
