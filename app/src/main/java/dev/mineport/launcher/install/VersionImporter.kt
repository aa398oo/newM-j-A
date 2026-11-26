package dev.mineport.launcher.install

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * 从系统文件选择器（SAF）导入一个「已经安装好的版本目录」。
 *
 * 使用场景：Forge / NeoForge / 第三方整合包在桌面端用官方 installer 装好后，
 * 把 `versions/<id>/` 整个目录拷到手机上，由本类复制进应用私有目录，
 * 后续由 MinecraftLauncher 按普通版本识别（<id>.json + <id>.jar + libraries）。
 *
 * 只复制版本目录本身；libraries 与 assets 仍需要通过「下载」页安装同版本补齐。
 */
object VersionImporter {

    fun import(context: Context, treeUri: Uri, versionsDir: File): Result<String> = runCatching {
        val resolver = context.contentResolver
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        val displayName = queryName(resolver, rootUri)?.trim().orEmpty()
        require(displayName.isNotEmpty()) { "无法读取目录名，请确认选择的是 versions/<id> 这一层" }
        require(displayName.none { it == '/' || it == '\\' }) { "目录名不合法：$displayName" }

        val target = File(versionsDir, displayName)
        target.mkdirs()

        val copied = copyChildren(context, treeUri, rootUri, target)
        require(copied > 0) { "目录是空的，或没有读取权限" }
        require(File(target, "$displayName.json").isFile) {
            "目录里没有 $displayName.json；请选择包含 <id>.json 的版本目录（不是 versions 的上一层）"
        }
        displayName
    }

    private fun copyChildren(context: Context, treeUri: Uri, parentUri: Uri, dest: File): Int {
        val resolver = context.contentResolver
        val parentDocId = DocumentsContract.getDocumentId(parentUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        var count = 0
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val docId = cursor.getString(0)
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2).orEmpty()
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val dir = File(dest, name)
                    dir.mkdirs()
                    count += copyChildren(context, treeUri, childUri, dir)
                } else {
                    resolver.openInputStream(childUri)?.use { input ->
                        File(dest, name).outputStream().use { output ->
                            input.copyTo(output)
                        }
                        count++
                    }
                }
            }
        }
        return count
    }

    private fun queryName(
        resolver: android.content.ContentResolver,
        uri: Uri
    ): String? = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}
