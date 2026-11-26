package dev.mineport.launcher.install

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 从 .zip 整合包导入版本目录。
 *
 * 只处理包含 `versions/<id>/<id>.json` 的标准布局（Modrinth / CurseForge 整合包、
 * 以及手工打包的版本）；不处理 overrides/ 与 mods/，模组仍走实例里的「安装模组」。
 */
object ZipVersionImporter {

    fun import(
        context: Context,
        zipUri: Uri,
        versionsDir: File,
        workDir: File
    ): Result<String> = runCatching {
        val tmp = File(workDir, "zip-import").apply {
            deleteRecursively()
            mkdirs()
        }
        extract(context, zipUri, tmp)

        val versionsRoot = File(tmp, "versions")
        require(versionsRoot.isDirectory) {
            "zip 里没有 versions/<id>/<id>.json；请把整合包解包后的 versions 目录打进去"
        }
        val dirs = versionsRoot.listFiles().orEmpty().filter { it.isDirectory }
        val ids = dirs.mapNotNull { dir ->
            val json = File(dir, dir.name + ".json")
            if (json.isFile) dir.name else null
        }
        require(ids.isNotEmpty()) { "versions 目录里没有 <id>.json，无法识别版本号" }
        require(ids.size == 1) { "zip 里包含多个版本（${ids.joinToString()}），请一次导入一个" }

        val id = ids.first()
        val source = File(versionsRoot, id)
        val target = File(versionsDir, id).apply {
            deleteRecursively()
            mkdirs()
        }
        source.copyRecursively(target, overwrite = true)
        tmp.deleteRecursively()
        id
    }

    private fun extract(context: Context, uri: Uri, dest: File) {
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && !name.startsWith("__MACOSX/")) {
                        val out = File(dest, name)
                        val canonical = out.canonicalPath
                        require(canonical.startsWith(dest.canonicalPath + File.separator)) {
                            "zip 里含非法路径：$name"
                        }
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: error("无法打开 zip（没有读取权限）")
    }
}
