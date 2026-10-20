package me.kafuuneko.rpclient.libs.backup

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import androidx.sqlite.db.SupportSQLiteDatabase
import android.net.Uri
import android.util.Base64
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.kafuuneko.rpclient.libs.room.AppDatabase
import me.kafuuneko.rpclient.libs.room.RequestLogDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * Portable full-data backup: SQLite rows are represented as readable JSONL, while original
 * preference XML and binary files are preserved. The archive is intentionally unencrypted.
 */
class PortableBackupRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val requestLogDatabase: RequestLogDatabase
) {
    companion object {
        private const val FORMAT = "rpclient-portable-data"
        private const val VERSION = 1
        private const val PRIMARY_DB = "primary.sqlite"
        private const val LOGS_DB = "request_logs.sqlite"
        private const val BUFFER_SIZE = 32 * 1024
        private const val MAX_ENTRIES = 50_000
        private const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    }

    suspend fun exportArchive(destination: Uri) = withContext(Dispatchers.IO) {
        val stage = File(context.cacheDir, "portable-export-" + System.nanoTime())
        check(stage.mkdirs()) { "Could not create temporary backup workspace." }
        try {
            val primaryDb = database.openHelper.writableDatabase
            primaryDb.beginTransaction()
            try {
                exportDatabase(primaryDb, File(stage, "database/primary"))
                primaryDb.setTransactionSuccessful()
            } finally {
                primaryDb.endTransaction()
            }

            val requestLogsDb = requestLogDatabase.openHelper.writableDatabase
            requestLogsDb.beginTransaction()
            try {
                exportDatabase(requestLogsDb, File(stage, "database/request-logs"))
                requestLogsDb.setTransactionSuccessful()
            } finally {
                requestLogsDb.endTransaction()
            }
            File(stage, "settings/shared_prefs").mkdirs()
            copyDirectory(File(context.dataDir, "shared_prefs"), File(stage, "settings/shared_prefs"))
            copyDirectory(context.getDir("repository", Context.MODE_PRIVATE), File(stage, "attachments/repository"))
            copyDirectory(context.filesDir, File(stage, "app-files"))

            val preferenceFiles = JSONObject()
            File(stage, "settings/shared_prefs").listFiles().orEmpty()
                .filter { it.isFile }.forEach { file ->
                    preferenceFiles.put(file.name, file.readText(Charsets.UTF_8))
                }
            val settings = JSONObject()
                .put("format", FORMAT)
                .put("preferencesXml", preferenceFiles)
                .put("providerCredentialsIncluded", true)
                .put("encrypted", false)
                .put("note", "Original preference XML files are also preserved byte-for-byte under settings/shared_prefs.")
            writeText(File(stage, "settings.json"), settings.toString(2))

            val files = JSONObject()
            stage.walkTopDown().filter { it.isFile }.toList().forEach { file ->
                files.put(file.relativeTo(stage).invariantSeparatorsPath,
                    JSONObject().put("size", file.length()).put("sha256", sha256(file)))
            }
            val manifest = JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("createdAt", System.currentTimeMillis())
                .put("appPackage", context.packageName)
                .put("encrypted", false)
                .put("restoreMode", "full-replacement")
                .put("components", JSONObject()
                    .put("settings", true).put("characters", true).put("stories", true)
                    .put("chats", true).put("groupChats", true).put("worldBooks", true)
                    .put("regexScripts", true).put("providersAndApiKeys", true)
                    .put("tokenUsage", true).put("requestLogs", true).put("binaryAssets", true))
                .put("files", files)
                .put("notes", "All Room tables, request logs, preferences and private files are included. Not encrypted.")
            writeText(File(stage, "manifest.json"), manifest.toString(2))

            val output = context.contentResolver.openOutputStream(destination, "wt")
                ?: error("Could not open the selected destination.")
            output.use { raw ->
                ZipOutputStream(raw.buffered()).use { zip ->
                    stage.walkTopDown().filter { it.isFile }.forEach { file ->
                        zip.putNextEntry(ZipEntry(file.relativeTo(stage).invariantSeparatorsPath))
                        FileInputStream(file).use { it.copyTo(zip, BUFFER_SIZE) }
                        zip.closeEntry()
                    }
                }
            }
        } finally {
            stage.deleteRecursively()
        }
    }

    suspend fun restoreArchive(source: Uri) = withContext(Dispatchers.IO) {
        val stage = File(context.cacheDir, "portable-restore-" + System.nanoTime())
        val rollback = File(context.cacheDir, "portable-rollback-" + System.nanoTime())
        check(stage.mkdirs()) { "Could not create temporary restore workspace." }
        try {
            val input = context.contentResolver.openInputStream(source)
                ?: error("Could not read the selected archive.")
            input.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var entries = 0
                    var totalBytes = 0L
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries++
                        require(entries <= MAX_ENTRIES) { "The archive contains too many files." }
                        require(isSafePath(entry.name)) { "Unsafe archive path: " + entry.name }
                        if (!entry.isDirectory) {
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

            val manifestFile = File(stage, "manifest.json")
            require(manifestFile.isFile) { "This ZIP is missing manifest.json." }
            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            if (manifest.optString("format") == "rpclient-backup") {
                BackupArchiveRepository(context, database).restoreArchive(source)
                return@withContext
            }
            require(manifest.optString("format") == FORMAT) { "This is not an RPClient portable data backup." }
            require(manifest.optInt("version", -1) == VERSION) {
                "Unsupported portable backup version: " + manifest.optInt("version", -1)
            }
            validateChecksums(stage, manifest.optJSONObject("files")
                ?: error("The manifest does not list archive files."))

            val primarySnapshot = File(stage, "database/primary")
            val logsSnapshot = File(stage, "database/request-logs")
            require(File(primarySnapshot, "schema.json").isFile) { "Primary database snapshot is missing." }
            require(File(logsSnapshot, "schema.json").isFile) { "Request-log database snapshot is missing." }
            val stagedPrimary = File(stage, PRIMARY_DB)
            val stagedLogs = File(stage, LOGS_DB)
            reconstructDatabase(primarySnapshot, stagedPrimary)
            reconstructDatabase(logsSnapshot, stagedLogs)

            val livePrimary = context.getDatabasePath(PRIMARY_DB)
            val liveLogs = context.getDatabasePath(LOGS_DB)
            val livePrefs = File(context.dataDir, "shared_prefs")
            val liveRepository = context.getDir("repository", Context.MODE_PRIVATE)
            val liveFiles = context.filesDir
            rollback.mkdirs()
            copyFile(livePrimary, File(rollback, PRIMARY_DB))
            copyFile(liveLogs, File(rollback, LOGS_DB))
            copyDirectory(livePrefs, File(rollback, "shared_prefs"))
            copyDirectory(liveRepository, File(rollback, "repository"))
            copyDirectory(liveFiles, File(rollback, "files"))

            database.close()
            requestLogDatabase.close()
            try {
                replaceFile(stagedPrimary, livePrimary)
                replaceFile(stagedLogs, liveLogs)
                deleteSidecars(livePrimary)
                deleteSidecars(liveLogs)
                replaceDirectory(File(stage, "settings/shared_prefs"), livePrefs)
                replaceDirectory(File(stage, "attachments/repository"), liveRepository)
                replaceDirectory(File(stage, "app-files"), liveFiles)
            } catch (error: Throwable) {
                copyFile(File(rollback, PRIMARY_DB), livePrimary)
                copyFile(File(rollback, LOGS_DB), liveLogs)
                replaceDirectoryIfPresent(File(rollback, "shared_prefs"), livePrefs)
                replaceDirectoryIfPresent(File(rollback, "repository"), liveRepository)
                replaceDirectoryIfPresent(File(rollback, "files"), liveFiles)
                deleteSidecars(livePrimary)
                deleteSidecars(liveLogs)
                throw BackupRestoreException(
                    "Portable restore failed. Previous data was recovered; restart the app to reopen its databases.",
                    restartRequired = true,
                    cause = error
                )
            }
        } finally {
            stage.deleteRecursively()
            rollback.deleteRecursively()
        }
    }

    private fun exportDatabase(db: SupportSQLiteDatabase, destination: File) {
        check(destination.mkdirs()) { "Could not create database export directory." }
        val objects = JSONArray()
        db.query(
            "SELECT type, name, tbl_name, sql FROM sqlite_master " +
                "WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' " +
                "ORDER BY CASE type WHEN 'table' THEN 0 ELSE 1 END, name", emptyArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                objects.put(JSONObject().put("type", cursor.getString(0)).put("name", cursor.getString(1))
                    .put("table", cursor.getString(2)).put("sql", cursor.getString(3)))
            }
        }
        writeText(File(destination, "schema.json"),
            JSONObject().put("userVersion", db.version).put("objects", objects).toString(2))

        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", emptyArray()
        ).use { tables ->
            while (tables.moveToNext()) {
                val table = tables.getString(0)
                val output = File(destination, "tables/" + safeTableName(table) + ".jsonl")
                output.parentFile?.mkdirs()
                output.bufferedWriter(Charsets.UTF_8).use { writer ->
                    db.query("SELECT * FROM " + quote(table), emptyArray()).use { rows ->
                        val columns = (0 until rows.columnCount).map(rows::getColumnName)
                        while (rows.moveToNext()) {
                            val row = JSONObject()
                            columns.forEachIndexed { index, column -> row.put(column, cursorValue(rows, index)) }
                            writer.append(row.toString())
                            writer.newLine()
                        }
                    }
                }
            }
        }
    }

    private fun reconstructDatabase(snapshot: File, destination: File) {
        val schema = JSONObject(File(snapshot, "schema.json").readText(Charsets.UTF_8))
        val objects = schema.getJSONArray("objects")
        val db = SQLiteDatabase.openOrCreateDatabase(destination, null)
        try {
            db.execSQL("PRAGMA foreign_keys=OFF")
            for (i in 0 until objects.length()) {
                val item = objects.getJSONObject(i)
                if (item.getString("type") == "table") db.execSQL(item.getString("sql"))
            }
            File(snapshot, "tables").listFiles().orEmpty()
                .filter { it.isFile && it.extension == "jsonl" }.forEach { file ->
                    val table = tableNameForFile(objects, file.nameWithoutExtension)
                    file.forEachLine(Charsets.UTF_8) { line ->
                        if (line.isBlank()) return@forEachLine
                        val row = JSONObject(line)
                        val columns = mutableListOf<String>()
                        val values = mutableListOf<Any?>()
                        val keys = row.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            columns += key
                            values += jsonValue(row.get(key))
                        }
                        val sql = "INSERT INTO " + quote(table) + " (" +
                            columns.joinToString(",") { quote(it) } + ") VALUES (" +
                            columns.joinToString(",") { "?" } + ")"
                        val statement = db.compileStatement(sql)
                        try {
                            values.forEachIndexed { index, value -> bind(statement, index + 1, value) }
                            statement.executeInsert()
                        } finally {
                            statement.close()
                        }
                    }
                }
            for (i in 0 until objects.length()) {
                val item = objects.getJSONObject(i)
                if (item.getString("type") != "table") db.execSQL(item.getString("sql"))
            }
            db.version = schema.optInt("userVersion", 0)
            db.execSQL("PRAGMA foreign_keys=ON")
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                    "Reconstructed database failed SQLite integrity validation."
                }
            }
        } finally {
            db.close()
        }
    }

    private fun tableNameForFile(objects: JSONArray, safeName: String): String {
        for (i in 0 until objects.length()) {
            val item = objects.getJSONObject(i)
            if (item.getString("type") == "table" && safeTableName(item.getString("name")) == safeName) {
                return item.getString("name")
            }
        }
        error("Archive references an unknown database table: " + safeName)
    }

    private fun cursorValue(cursor: Cursor, index: Int): Any? = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
        Cursor.FIELD_TYPE_BLOB -> JSONObject().put("$" + "blobBase64",
            Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP))
        else -> JSONObject.NULL
    }

    private fun jsonValue(value: Any?): Any? {
        if (value == null || value === JSONObject.NULL) return null
        if (value is JSONObject && value.has("$" + "blobBase64")) {
            return Base64.decode(value.getString("$" + "blobBase64"), Base64.DEFAULT)
        }
        return value
    }

    private fun bind(statement: SQLiteStatement, index: Int, value: Any?) {
        when (value) {
            null, JSONObject.NULL -> statement.bindNull(index)
            is ByteArray -> statement.bindBlob(index, value)
            is Boolean -> statement.bindLong(index, if (value) 1 else 0)
            is Int -> statement.bindLong(index, value.toLong())
            is Long -> statement.bindLong(index, value)
            is Double -> statement.bindDouble(index, value)
            is Float -> statement.bindDouble(index, value.toDouble())
            else -> statement.bindString(index, value.toString())
        }
    }

    private fun validateChecksums(root: File, files: JSONObject) {
        val listed = mutableSetOf<String>()
        val keys = files.keys()
        while (keys.hasNext()) {
            val path = keys.next()
            listed += path
            require(isSafePath(path)) { "Unsafe manifest path: " + path }
            val file = File(root, path).canonicalFile
            require(file.path.startsWith(root.canonicalPath + File.separator) && file.isFile) {
                "The archive is missing " + path
            }
            val info = files.getJSONObject(path)
            require(file.length() == info.getLong("size")) { "File size mismatch: " + path }
            require(sha256(file).equals(info.getString("sha256"), ignoreCase = true)) {
                "Checksum mismatch: " + path
            }
        }
        val actual = root.walkTopDown().filter { it.isFile && it.name != "manifest.json" }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
        require(actual == listed) { "The archive contains files not listed in manifest.json." }
    }

    private fun isSafePath(name: String): Boolean {
        if (name.isBlank() || name.startsWith("/") || name.startsWith("\\") || name.contains('\\')) return false
        return name.removeSuffix("/").split('/').none { it.isBlank() || it == "." || it == ".." }
    }

    private fun safeTableName(name: String) = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    private fun quote(value: String) = "\"" + value.replace("\"", "\"\"") + "\""

    private fun writeText(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }

    private fun sha256(file: File): String {
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

    private fun copyFile(source: File, destination: File) {
        if (!source.isFile) return
        destination.parentFile?.mkdirs()
        source.copyTo(destination, overwrite = true)
    }

    private fun copyDirectory(source: File, destination: File) {
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

    private fun replaceFile(source: File, destination: File) {
        require(source.isFile) { "Restore archive is missing " + source.name }
        destination.parentFile?.mkdirs()
        if (destination.exists() && !destination.delete()) error("Could not replace " + destination.name)
        if (!source.renameTo(destination)) source.copyTo(destination, overwrite = true)
    }

    private fun replaceDirectory(source: File, destination: File) {
        require(source.isDirectory) { "Restore archive is missing " + source.path }
        if (destination.exists() && !destination.deleteRecursively()) error("Could not replace " + destination.name)
        destination.parentFile?.mkdirs()
        if (!source.renameTo(destination)) copyDirectory(source, destination)
    }

    private fun replaceDirectoryIfPresent(source: File, destination: File) {
        if (!source.isDirectory) return
        if (destination.exists()) destination.deleteRecursively()
        copyDirectory(source, destination)
    }

    private fun deleteSidecars(file: File) {
        listOf("-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
    }
}
