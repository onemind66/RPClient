package me.kafuuneko.rpclient.libs.backup

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.Writer
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import me.kafuuneko.rpclient.libs.backup.BackupArchiveFormat.BUFFER_SIZE
import me.kafuuneko.rpclient.libs.backup.BackupArchiveFormat.MAX_BYTES
import me.kafuuneko.rpclient.libs.backup.BackupArchiveFormat.MAX_ENTRIES
import org.json.JSONObject

/** 归档路径与校验、流式 ZIP 读取及文件替换的公共操作。 */
internal object BackupArchiveFiles {
    /**
     * 验证 ZIP 的文件路径与 manifest 索引格式，但不把导出时的摘要当作恢复前置条件。
     *
     * Portable ZIP 是可编辑数据格式：内容修改、删除和新增文件都会使原始 SHA-256 索引
     * 过期。摘要在此仅是导出时的参考信息，不是签名或真实性证明。路径遍历、重复 ZIP
     * 条目、解压大小上限以及下游数据库/文档校验仍然强制执行。
     *
     * 必需的 schema、偏好目录和附件目录由 restoreArchive/replaceLiveData 单独校验；
     * 因此删除这些关键内容仍会安全失败，而不是覆盖当前数据。
     */
    fun validateChecksums(root: File, files: JSONObject) {
        val rootPath = root.canonicalPath
        val keys = files.keys()
        while (keys.hasNext()) {
            val path = keys.next()
            require(isSafePath(path)) { "Unsafe manifest path: " + path }
            val info = files.getJSONObject(path)
            require(info.optLong("size", -1L) >= 0L) {
                "Invalid manifest size for " + path
            }
            require(info.optString("sha256").matches(Regex("(?i)[0-9a-f]{64}"))) {
                "Invalid manifest checksum for " + path
            }
            // A manifest entry may now be absent because users can delete files from the ZIP.
            val candidate = File(root, path)
            if (candidate.exists()) {
                val file = candidate.canonicalFile
                require(file.path.startsWith(rootPath + File.separator) && file.isFile) {
                    "Unsafe or invalid archive file: " + path
                }
            }
        }

        // New files are allowed, but every extracted file must still have a safe canonical path.
        root.walkTopDown().filter { it.isFile && it != File(root, "manifest.json") }.forEach { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            require(isSafePath(path)) { "Unsafe archive path: " + path }
            val canonical = file.canonicalFile
            require(canonical.path.startsWith(rootPath + File.separator)) {
                "Unsafe archive path: " + path
            }
        }
    }

    /** 流式解压到独立目录，拒绝越界路径、重复条目和超过恢复限额的内容。 */
    fun extractArchive(context: Context, source: Uri, stage: File) {
        val input = context.contentResolver.openInputStream(source)
            ?: error("Could not read the selected archive.")
        input.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var entries = 0
                var totalBytes = 0L
                val seenPaths = mutableSetOf<String>()
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    require(entries <= MAX_ENTRIES) { "The archive contains too many files." }
                    require(isSafePath(entry.name)) { "Unsafe archive path: " + entry.name }
                    require(seenPaths.add(entry.name)) { "The archive contains a duplicate path: " + entry.name }
                    // 规范化后的目标仍须位于暂存目录，不能只检查原始路径片段。
                    if (entry.isDirectory) {
                        val target = File(stage, entry.name).canonicalFile
                        require(target.path == stage.canonicalPath ||
                            target.path.startsWith(stage.canonicalPath + File.separator)) {
                            "Unsafe archive path: " + entry.name
                        }
                        target.mkdirs()
                    } else {
                        val target = File(stage, entry.name).canonicalFile
                        require(target.path.startsWith(stage.canonicalPath + File.separator)) {
                            "Unsafe archive path: " + entry.name
                        }
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                totalBytes += count
                                require(totalBytes <= MAX_BYTES) { "The archive exceeds the 2 GiB restore limit." }
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun isSafePath(name: String): Boolean {
        if (name.isBlank() || name.startsWith("/") || name.startsWith("\\") || name.contains('\\')) return false
        return name.removeSuffix("/").split('/').none { it.isBlank() || it == "." || it == ".." }
    }

    /** 输出单条 UTF-8 JSONL 记录，换行由写入器统一追加。 */
    fun writeJsonLine(json: JSONObject, writer: Writer) {
        writer.write(json.toString())
        writer.write("\n")
    }

    /** 创建父目录后写入 UTF-8 文档。 */
    fun writeText(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }

    /** 流式计算文件摘要，避免将附件整体读入内存。 */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 复制存在的普通文件；原文件缺省时保留缺省状态。 */
    fun copyFile(source: File, destination: File) {
        if (!source.isFile) return
        destination.parentFile?.mkdirs()
        source.copyTo(destination, overwrite = true)
    }

    /** 复制目录树和空目录，供快照与回滚使用。 */
    fun copyDirectory(source: File, destination: File) {
        if (!source.isDirectory) return
        source.walkTopDown().forEach { file ->
            val target = File(destination, file.relativeTo(source).path)
            if (file.isDirectory) target.mkdirs()
            else {
                target.parentFile?.mkdirs()
                file.copyTo(target, overwrite = true)
            }
        }
    }

    /** 以已校验文件替换目标，跨卷时回退为复制。 */
    fun replaceFile(source: File, destination: File) {
        require(source.isFile) { "Restore archive is missing " + source.name }
        destination.parentFile?.mkdirs()
        if (destination.exists() && !destination.delete()) error("Could not replace " + destination.name)
        if (!source.renameTo(destination)) source.copyTo(destination, overwrite = true)
    }

    /** 完整替换目录，防止旧附件或设置残留。 */
    fun replaceDirectory(source: File, destination: File) {
        require(source.isDirectory) { "Restore archive is missing " + source.path }
        if (destination.exists() && !destination.deleteRecursively()) error("Could not replace " + destination.name)
        destination.parentFile?.mkdirs()
        if (!source.renameTo(destination)) copyDirectory(source, destination)
    }

    /**
     * 替换数据库时清除旧 WAL、SHM 和回滚日志，防止旧日志覆盖或破坏恢复的数据。
     */
    fun deleteSidecars(databaseFile: File) {
        File(databaseFile.path + "-wal").delete()
        File(databaseFile.path + "-shm").delete()
        File(databaseFile.path + "-journal").delete()
    }
}
