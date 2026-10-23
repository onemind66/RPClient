package me.kafuuneko.rpclient.libs.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Xml
import com.google.gson.Gson
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.xmlpull.v1.XmlSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.kafuuneko.rpclient.libs.backup.BackupArchiveFiles.copyDirectory
import me.kafuuneko.rpclient.libs.backup.BackupArchiveFiles.copyFile
import me.kafuuneko.rpclient.libs.backup.BackupArchiveFiles.extractArchive
import me.kafuuneko.rpclient.libs.backup.BackupArchiveFiles.sha256
import me.kafuuneko.rpclient.libs.backup.BackupArchiveFiles.validateEditableInventory
import me.kafuuneko.rpclient.libs.backup.BackupArchiveFiles.writeJsonLine
import me.kafuuneko.rpclient.libs.backup.BackupArchiveFiles.writeText
import me.kafuuneko.rpclient.libs.backup.BackupArchiveFormat.BUFFER_SIZE
import me.kafuuneko.rpclient.libs.backup.BackupArchiveFormat.FORMAT
import me.kafuuneko.rpclient.libs.backup.BackupArchiveFormat.LEGACY_VERSION
import me.kafuuneko.rpclient.libs.backup.BackupArchiveFormat.LOGS_DB
import me.kafuuneko.rpclient.libs.backup.BackupArchiveFormat.PRIMARY_DB
import me.kafuuneko.rpclient.libs.backup.BackupArchiveFormat.TABLE_ROWS_KEY
import me.kafuuneko.rpclient.libs.backup.BackupArchiveFormat.VERSION
import me.kafuuneko.rpclient.libs.backup.BackupDatabaseCodec.collectTableRows
import me.kafuuneko.rpclient.libs.backup.BackupDatabaseCodec.readLegacyRows
import me.kafuuneko.rpclient.libs.backup.BackupDatabaseCodec.readRows
import me.kafuuneko.rpclient.libs.backup.BackupDatabaseCodec.reconstructDatabase
import me.kafuuneko.rpclient.libs.backup.BackupDatabaseCodec.schemaJson
import me.kafuuneko.rpclient.libs.backup.BackupDatabaseCodec.tableNames
import me.kafuuneko.rpclient.libs.backup.BackupDatabaseCodec.tableRows
import me.kafuuneko.rpclient.libs.character.CharacterCardMapper
import me.kafuuneko.rpclient.libs.character.LorebookCodec
import me.kafuuneko.rpclient.libs.regex.RegexScriptCodec
import me.kafuuneko.rpclient.libs.room.AppDatabase
import me.kafuuneko.rpclient.libs.room.RequestLogDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * 按功能组织可移植备份，在 JSON/JSONL 中保存完整 Room 数据行，
 * 使关系与文件引用可以重建，而无需依赖原始 SQLite 文件。
 */
class PortableBackupRepository(
    private val mContext: Context,
    private val mDatabase: AppDatabase,
    private val mRequestLogDatabase: RequestLogDatabase,
    private val mLegacyRepository: BackupArchiveRepository
) {

    /**
     * 导出当前业务数据、设置和附件；完整 ZIP 按明文协议保存凭据。
     * @param destination 用户通过系统选择器创建的 ZIP 文档。
     */
    suspend fun exportArchive(destination: Uri) = withContext(Dispatchers.IO) {
        val stage = File(mContext.cacheDir, "portable-export-" + System.nanoTime())
        check(stage.mkdirs()) { "Could not create temporary backup workspace." }
        try {
            // 业务行与附件在同一主库快照中导出，避免引用与物理文件错位。
            createArchiveDirectories(stage)
            val primaryDb = mDatabase.openHelper.writableDatabase
            val primaryTables = tableNames(primaryDb)
            primaryDb.beginTransaction()
            try {
                writeText(File(stage, "app-data/database-schema.json"), schemaJson(primaryDb).toString(2))
                PortableBackupDocumentWriter(
                    primaryDb, primaryTables, stage,
                    mContext.getDir("repository", Context.MODE_PRIVATE)
                ).writeAll()
                exportAttachments(primaryDb, primaryTables, stage)
                primaryDb.setTransactionSuccessful()
            } finally {
                primaryDb.endTransaction()
            }

            val logsDb = mRequestLogDatabase.openHelper.writableDatabase
            logsDb.beginTransaction()
            try {
                writeText(File(stage, "app-data/request-log-schema.json"), schemaJson(logsDb).toString(2))
                exportRequestLogs(logsDb, stage)
                logsDb.setTransactionSuccessful()
            } finally {
                logsDb.endTransaction()
            }

            // 设置和私有文件保持原始格式，manifest 在全部内容写完后生成。
            exportSettings(stage)
            copyDirectory(mContext.filesDir, File(stage, "app-data/private-files"))

            writeManifest(stage)

            writeArchive(stage, destination)
        } finally {
            stage.deleteRecursively()
        }
    }

    /** 在所有暂存文件完成后生成大小与 SHA-256 索引。 */
    private fun writeManifest(stage: File) {
        val fileIndex = JSONObject()
        stage.walkTopDown().filter { it.isFile }.toList().forEach { file ->
            fileIndex.put(file.relativeTo(stage).invariantSeparatorsPath, JSONObject()
                .put("size", file.length()).put("sha256", sha256(file)))
        }
        // manifest 不索引自身，避免摘要形成自引用。
        val manifest = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("appPackage", mContext.packageName)
            .put("encrypted", false)
            .put("restoreMode", "full-replacement")
            .put("structure", JSONArray()
                .put("characters/character-<id>.json")
                .put("stories/story-<id>.rpstory.json")
                .put("chats/conversation-<id>.jsonl")
                .put("group-chats/group-<id>.jsonl")
                .put("worldbooks/worldbook-<id>.json")
                .put("regex/global-scripts.json")
                .put("regex/character-scripts/character-<id>.json")
                .put("regex/character-authorizations.json")
                .put("providers/provider-configurations.json")
                .put("attachments/characters/")
                .put("attachments/chat-images/")
                .put("attachments/other-files/")
                .put("app-data/token-usage.json"))
            .put("components", JSONObject()
                .put("characters", true).put("stories", true).put("chats", true)
                .put("groupChats", true).put("worldbooks", true).put("regex", true)
                .put("providersAndApiKeys", true).put("settingsAndPrompts", true)
                .put("tokenUsage", true).put("requestLogs", true)
                .put("attachments", true).put("privateAppFiles", true))
            .put("recordCounts", countRecords(stage))
            .put("files", fileIndex)
            .put("notes", "Feature-oriented JSON/JSONL with exact relational rows. Not encrypted; may contain API keys and raw request/response logs.")
        writeText(File(stage, "manifest.json"), manifest.toString(2))
    }

    /** 逐文件写入 ZIP，避免一次性将整个归档加载到堆内存。 */
    private fun writeArchive(stage: File, destination: Uri) {
        val output = mContext.contentResolver.openOutputStream(destination, "wt")
            ?: error("Could not open the selected destination.")
        output.use { raw ->
            ZipOutputStream(raw.buffered()).use { zip ->
                // 保留空目录条目，恢复时可区分空目录与归档缺项。
                stage.walkTopDown().drop(1).forEach { file ->
                    val path = file.relativeTo(stage).invariantSeparatorsPath
                    if (file.isDirectory) {
                        zip.putNextEntry(ZipEntry(path.trimEnd('/') + "/"))
                        zip.closeEntry()
                    } else {
                        zip.putNextEntry(ZipEntry(path))
                        FileInputStream(file).use { it.copyTo(zip, BUFFER_SIZE) }
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    /**
     * 校验并恢复可移植或兼容旧版归档；成功后调用方必须重启应用。
     * @param source 用户确认恢复的 ZIP 文档。
     * @throws BackupRestoreException 数据库关闭后的失败，携带重启和回滚状态。
     */
    suspend fun restoreArchive(source: Uri) = withContext(Dispatchers.IO) {
        val stage = File(mContext.cacheDir, "portable-restore-" + System.nanoTime())
        val rollback = File(mContext.cacheDir, "portable-rollback-" + System.nanoTime())
        check(stage.mkdirs()) { "Could not create temporary restore workspace." }
        try {
            extractArchive(mContext, source, stage)
            val manifestFile = File(stage, "manifest.json")
            require(manifestFile.isFile) { "This ZIP is missing manifest.json." }
            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            // 格式识别后交给对应解码器，旧格式沿用既有恢复范围。
            if (manifest.optString("format") == "rpclient-backup") {
                mLegacyRepository.restoreArchive(source)
                return@withContext
            }
            require(manifest.optString("format") == FORMAT) { "This is not an RPClient portable data backup." }
            val version = manifest.optInt("version", -1)
            require(version == VERSION || version == LEGACY_VERSION) {
                "Unsupported portable backup version: " + version
            }
            validateChecksums(stage, manifest.optJSONObject("files")
                ?: error("The manifest does not list archive files."))

            if (version == LEGACY_VERSION && File(stage, "database/primary/schema.json").isFile) {
                val legacyPrimary = File(stage, "database/primary")
                val legacyLogs = File(stage, "database/request-logs")
                val stagedPrimary = File(stage, PRIMARY_DB)
                val stagedLogs = File(stage, LOGS_DB)
                reconstructDatabase(
                    JSONObject(File(legacyPrimary, "schema.json").readText(Charsets.UTF_8)),
                    readLegacyRows(legacyPrimary), stagedPrimary
                )
                reconstructDatabase(
                    JSONObject(File(legacyLogs, "schema.json").readText(Charsets.UTF_8)),
                    readLegacyRows(legacyLogs), stagedLogs
                )
                replaceLiveData(
                    rollback, stagedPrimary, stagedLogs,
                    File(stage, "settings/shared_prefs"),
                    File(stage, "attachments/repository"),
                    File(stage, "app-files")
                )
                return@withContext
            }

            require(version == VERSION) { "Legacy archive layout is incomplete." }
            val primarySchemaFile = File(stage, "app-data/database-schema.json")
            val logsSchemaFile = File(stage, "app-data/request-log-schema.json")
            require(primarySchemaFile.isFile) { "The primary database schema is missing." }
            require(logsSchemaFile.isFile) { "The request-log database schema is missing." }
            applyEditableCharacterDocuments(stage)
            applyEditableWorldbookDocuments(stage)
            applyEditableRegexDocuments(stage)
            applyEditableStoryDocuments(stage)
            applyEditableChatDocuments(stage)
            applyEditableGroupChatDocuments(stage)
            applyEditableRawRowDocuments(stage)
            applyEditableAttachmentDocuments(stage)
            applyEditableSettings(stage)
            val primaryRows = reconcileMessageImageRows(
                collectTableRows(stage, excludePath = "app-data/request-logs.jsonl")
            )
            val requestRows = collectTableRows(stage, includePath = "app-data/request-logs.jsonl")
            val stagedPrimary = File(stage, PRIMARY_DB)
            val stagedLogs = File(stage, LOGS_DB)
            reconstructDatabase(
                JSONObject(primarySchemaFile.readText(Charsets.UTF_8)), primaryRows, stagedPrimary
            )
            reconstructDatabase(
                JSONObject(logsSchemaFile.readText(Charsets.UTF_8)), requestRows, stagedLogs
            )


            replaceLiveData(
                rollback, stagedPrimary, stagedLogs,
                File(stage, "app-data/shared-prefs"),
                File(stage, "attachments/other-files"),
                File(stage, "app-data/private-files")
            )
        } finally {
            stage.deleteRecursively()
        }
    }

    /** 显式写入必要空目录，使空设置或空附件也能完整恢复。 */
    private fun createArchiveDirectories(root: File) {
        listOf(
            "characters", "stories", "chats", "group-chats", "worldbooks",
            "regex/character-scripts", "providers", "attachments/characters",
            "attachments/chat-images", "attachments/other-files", "app-data/shared-prefs",
            "app-data/private-files"
        ).forEach { File(root, it).mkdirs() }
    }

    /** 同时保存可读偏好和原始 XML，恢复使用原始文件以保留类型。 */
    private fun exportSettings(root: File) {
        val sourcePrefs = File(mContext.dataDir, "shared_prefs")
        val targetPrefs = File(root, "app-data/shared-prefs")
        copyDirectory(sourcePrefs, targetPrefs)
        // JSON 便于查看；XML 作为精确恢复的权威数据。
        val preferences = JSONObject()
        targetPrefs.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".xml") }.forEach { xml ->
            val prefsName = xml.name.removeSuffix(".xml")
            preferences.put(prefsName, sharedPreferencesJson(
                mContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            ))
        }
        val preferenceTypes = JSONObject()
        targetPrefs.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".xml") }.forEach { xml ->
            val prefsName = xml.name.removeSuffix(".xml")
            preferenceTypes.put(prefsName, sharedPreferencesTypes(
                mContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            ))
        }
        writeText(File(root, "settings.json"),
            JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("preferences", preferences)
                .put("preferenceTypes", preferenceTypes)
                .put("providerCredentialsIncluded", true)
                .put("encrypted", false)
                .put("rawPreferenceXml", "app-data/shared-prefs/")
                .put("note", "Preferences are provided as readable JSON and preserved as original XML for exact restoration.")
                .toString(2))
    }

    /**
     * Synchronize user-facing Character Card V2 fields back into the exact Room row.
     * The hidden row remains the carrier for RPClient-only fields, IDs, and relationships, but
     * it must not override edits to fields that the standard card format exposes.
     */
    /**
     * message_images cannot declare a conditional foreign key to single or group messages. Remove
     * stale image links explicitly when their owning message was deleted from an editable document.
     */
    private fun reconcileMessageImageRows(
        source: Map<String, List<JSONObject>>
    ): Map<String, List<JSONObject>> {
        val result = source.mapValuesTo(linkedMapOf()) { (_, rows) -> rows.toMutableList() }
        val singleMessageIds = result["chat_messages"].orEmpty()
            .mapTo(mutableSetOf()) { it.optLong("id") }
        val groupMessageIds = result["group_chat_messages"].orEmpty()
            .mapTo(mutableSetOf()) { it.optLong("id") }
        val imageRows = result["message_images"] ?: return result.mapValues { it.value.toList() }
        imageRows.removeAll { image ->
            val messageId = image.optLong("messageId")
            when (image.optString("messageType").lowercase()) {
                "single" -> messageId !in singleMessageIds
                "group" -> messageId !in groupMessageIds
                else -> error("Unknown message type in attachment index: " + image.optString("messageType"))
            }
        }
        return result.mapValues { it.value.toList() }
    }

    private fun applyEditableCharacterDocuments(root: File) {
        val gson = Gson()
        val mapper = CharacterCardMapper(gson)
        val files = File(root, "characters").listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" }
        val usedIds = files.mapNotNull { file ->
            runCatching {
                JSONObject(file.readText(Charsets.UTF_8))
                    .getJSONObject(TABLE_ROWS_KEY).getJSONArray("character")
                    .getJSONObject(0).optLong("id")
            }.getOrNull()?.takeIf { it > 0L }
        }.toMutableSet()
        var nextId = (usedIds.maxOrNull() ?: 0L) + 1L

        files.forEach { file ->
            val document = JSONObject(file.readText(Charsets.UTF_8))
            val tableRows = document.optJSONObject(TABLE_ROWS_KEY) ?: JSONObject().also {
                document.put(TABLE_ROWS_KEY, it)
            }
            val characterRows = tableRows.optJSONArray("character") ?: JSONArray().also {
                tableRows.put("character", it)
            }
            require(characterRows.length() <= 1) {
                "Each character document must contain at most one character row: " + file.name
            }

            val original = characterRows.optJSONObject(0)
            val parsed = mapper.parseCharacter(
                document.toString(),
                avatar = original?.optString("avatar").orEmpty(),
                characterLorebookId = original?.optLong("characterLorebookId") ?: 0L
            ).character
            val id = original?.optLong("id")?.takeIf { it > 0L } ?: nextId++
            require(original == null || usedIds.contains(id)) {
                "Character row ID is inconsistent in " + file.name
            }
            if (original == null) {
                require(usedIds.add(id)) { "Duplicate character ID in " + file.name }
            }
            val updated = parsed.copy(
                id = id,
                backgroundFileUuid = if (original == null || original.isNull("backgroundFileUuid")) {
                    null
                } else {
                    original.optString("backgroundFileUuid")
                }
            )
            if (original == null) characterRows.put(JSONObject(gson.toJson(updated)))
            else characterRows.put(0, JSONObject(gson.toJson(updated)))
            writeText(file, document.toString(2))
        }
    }

    /**
     * Synchronize standard SillyTavern worldbook fields and entries into their preserved Room rows.
     * Entry IDs are retained by their exported order where possible; new entries receive IDs above
     * the current archived maximum so they cannot collide with existing primary keys.
     */
    private fun applyEditableWorldbookDocuments(root: File) {
        val gson = Gson()
        val codec = LorebookCodec(gson)
        val files = File(root, "worldbooks").listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" }
        val usedBookIds = files.mapNotNull { file ->
            runCatching {
                JSONObject(file.readText(Charsets.UTF_8))
                    .getJSONObject(TABLE_ROWS_KEY).getJSONArray("lorebooks")
                    .getJSONObject(0).optLong("id")
            }.getOrNull()?.takeIf { it > 0L }
        }.toMutableSet()
        var nextBookId = (usedBookIds.maxOrNull() ?: 0L) + 1L
        var nextEntryId = files.mapNotNull { file ->
            runCatching {
                JSONObject(file.readText(Charsets.UTF_8))
                    .getJSONObject(TABLE_ROWS_KEY).getJSONArray("lorebook_entries")
                    .objects().maxOfOrNull { it.optLong("id") }
            }.getOrNull()
        }.maxOrNull()?.plus(1L) ?: 1L

        files.forEach { file ->
            val document = JSONObject(file.readText(Charsets.UTF_8))
            val tableRows = document.optJSONObject(TABLE_ROWS_KEY) ?: JSONObject().also {
                document.put(TABLE_ROWS_KEY, it)
            }
            val bookRows = tableRows.optJSONArray("lorebooks") ?: JSONArray().also {
                tableRows.put("lorebooks", it)
            }
            require(bookRows.length() <= 1) {
                "Each worldbook document must contain at most one worldbook row: " + file.name
            }
            val originalBook = bookRows.optJSONObject(0)
            val parsed = codec.parseLorebook(document.toString())
            val bookId = originalBook?.optLong("id")?.takeIf { it > 0L } ?: nextBookId++
            if (originalBook == null) {
                require(usedBookIds.add(bookId)) { "Duplicate worldbook ID in " + file.name }
            }
            bookRows.put(0, JSONObject(gson.toJson(parsed.lorebook.copy(id = bookId))))

            val existingEntries = (tableRows.optJSONArray("lorebook_entries") ?: JSONArray())
                .objects().sortedWith(compareBy<JSONObject> { it.optInt("order") }.thenBy { it.optLong("id") })
            val updatedEntries = JSONArray()
            parsed.entries.forEachIndexed { index, entry ->
                val original = existingEntries.getOrNull(index)
                val id = original?.optLong("id")?.takeIf { it > 0L } ?: nextEntryId++
                updatedEntries.put(JSONObject(gson.toJson(entry.copy(id = id, lorebookId = bookId))))
            }
            tableRows.put("lorebook_entries", updatedEntries)
            writeText(file, document.toString(2))
        }
    }

    /**
     * Synchronize standard RegexScriptData arrays with the app's scoped script rows. The hidden
     * row IDs preserve local identity; visible array order controls script order and deletion.
     */
    private fun applyEditableRegexDocuments(root: File) {
        val gson = Gson()
        val codec = RegexScriptCodec(gson)
        val regexDirectory = File(root, "regex")
        val scriptFiles = buildList {
            add(File(regexDirectory, "global-scripts.json"))
            addAll(File(regexDirectory, "character-scripts").listFiles().orEmpty()
                .filter { it.isFile && it.extension == "json" })
        }.filter { it.isFile }
        var nextRowId = scriptFiles.mapNotNull { file ->
            runCatching {
                JSONObject(file.readText(Charsets.UTF_8)).getJSONObject(TABLE_ROWS_KEY)
                    .getJSONArray("regex_scripts").objects().maxOfOrNull { it.optLong("rowId") }
            }.getOrNull()
        }.maxOrNull()?.plus(1L) ?: 1L

        scriptFiles.forEach { file ->
            val document = JSONObject(file.readText(Charsets.UTF_8))
            val tableRows = document.optJSONObject(TABLE_ROWS_KEY) ?: JSONObject().also {
                document.put(TABLE_ROWS_KEY, it)
            }
            val rowArray = tableRows.optJSONArray("regex_scripts") ?: JSONArray()
            val oldRows = rowArray.objects().sortedBy { it.optInt("sortOrder") }
            val scripts = codec.parseList((document.optJSONArray("scripts") ?: JSONArray()).toString())
            val isGlobal = file.name == "global-scripts.json"
            val characterId = if (isGlobal) null else {
                val explicit = document.optLong("characterId", -1L)
                if (explicit > 0L) explicit else Regex("""character-(\d+)\.json""")
                    .matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()
            }
            require(isGlobal || characterId != null) {
                "Character regex document has no valid character ID: " + file.name
            }
            val updatedRows = JSONArray()
            scripts.forEachIndexed { index, script ->
                val old = oldRows.getOrNull(index)
                val rowId = old?.optLong("rowId")?.takeIf { it > 0L } ?: nextRowId++
                val row = (old ?: JSONObject())
                    .put("rowId", rowId)
                    .put("characterId", characterId ?: JSONObject.NULL)
                    .put("scriptId", script.id)
                    .put("sortOrder", index)
                    .put("scriptName", script.scriptName)
                    .put("findRegex", script.findRegex)
                    .put("replaceString", script.replaceString)
                    .put("trimStringsJson", gson.toJson(script.trimStrings))
                    .put("placementJson", gson.toJson(script.placement))
                    .put("disabled", script.disabled)
                    .put("markdownOnly", script.markdownOnly)
                    .put("promptOnly", script.promptOnly)
                    .put("runOnEdit", script.runOnEdit)
                    .put("substituteRegex", script.substituteRegex)
                    .put("minDepth", script.minDepth ?: JSONObject.NULL)
                    .put("maxDepth", script.maxDepth ?: JSONObject.NULL)
                    .put("rawJson", script.rawJson)
                updatedRows.put(row)
            }
            tableRows.put("regex_scripts", updatedRows)
            writeText(file, document.toString(2))
        }

        val authorizationFile = File(regexDirectory, "character-authorizations.json")
        if (authorizationFile.isFile) {
            val document = JSONObject(authorizationFile.readText(Charsets.UTF_8))
            val ids = document.optJSONArray("characterIds") ?: JSONArray()
            val rows = JSONArray()
            val seen = mutableSetOf<Long>()
            ids.objectsOrValues().forEach { value ->
                val id = (value as? Number)?.toLong()
                    ?: value.toString().toLongOrNull()
                    ?: error("Invalid authorized character ID.")
                require(id > 0L) { "Authorized character IDs must be positive." }
                require(seen.add(id)) { "Duplicate authorized character ID: " + id }
                rows.put(JSONObject().put("characterId", id))
            }
            val tableRows = document.optJSONObject(TABLE_ROWS_KEY) ?: JSONObject().also {
                document.put(TABLE_ROWS_KEY, it)
            }
            tableRows.put("regex_character_authorizations", rows)
            document.put("characterIds", JSONArray().apply { seen.sorted().forEach { put(it) } })
            writeText(authorizationFile, document.toString(2))
        }
    }

    /**
     * Synchronize the standard RPClient story archive payload into its Room rows. Array order in
     * the editable document is authoritative for volumes and chapters; IDs are retained by position
     * where possible, and new records receive unique IDs.
     */
    private fun applyEditableStoryDocuments(root: File) {
        val files = File(root, "stories").listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
        fun allRows(table: String): List<JSONObject> = files.flatMap { file ->
            runCatching {
                JSONObject(file.readText(Charsets.UTF_8)).getJSONObject(TABLE_ROWS_KEY)
                    .optJSONArray(table)?.objects().orEmpty()
            }.getOrDefault(emptyList())
        }
        val usedStoryIds = allRows("stories").map { it.optLong("id") }.filter { it > 0L }.toMutableSet()
        var nextStoryId = (usedStoryIds.maxOrNull() ?: 0L) + 1L
        var nextVolumeId = (allRows("story_volumes").maxOfOrNull { it.optLong("id") } ?: 0L) + 1L
        var nextChapterId = (allRows("story_chapters").maxOfOrNull { it.optLong("id") } ?: 0L) + 1L

        files.forEach { file ->
            val document = JSONObject(file.readText(Charsets.UTF_8))
            val archivedStory = document.optJSONObject("story")
                ?: error("Story document is missing its story object: " + file.name)
            val tableRows = document.optJSONObject(TABLE_ROWS_KEY) ?: JSONObject().also {
                document.put(TABLE_ROWS_KEY, it)
            }
            val storyRows = tableRows.optJSONArray("stories") ?: JSONArray().also {
                tableRows.put("stories", it)
            }
            require(storyRows.length() <= 1) { "Story document must contain at most one story row." }
            val oldStory = storyRows.optJSONObject(0)
            val storyId = oldStory?.optLong("id")?.takeIf { it > 0L } ?: nextStoryId++
            if (oldStory == null) require(usedStoryIds.add(storyId)) {
                "Duplicate story ID in " + file.name
            }
            val storyRow = oldStory ?: JSONObject()
            storyRow.put("id", storyId)
                .put("title", archivedStory.optString("title"))
                .put("memory", archivedStory.optString("memory"))
                .put("summary", archivedStory.optString("summary"))
                .put("authorNote", archivedStory.optString("authorNote"))
                .put("includeUserPersona", archivedStory.optBoolean("includeUserPersona"))
            if (oldStory == null) {
                val now = System.currentTimeMillis()
                storyRow.put("worldInfoGenerationStep", 0)
                    .put("revision", 0L)
                    .put("createTime", now)
                    .put("latestTime", now)
                storyRows.put(storyRow)
            } else {
                storyRows.put(0, storyRow)
            }

            val oldVolumes = (tableRows.optJSONArray("story_volumes") ?: JSONArray())
                .objects().sortedBy { it.optInt("sortOrder") }
            val oldChapters = (tableRows.optJSONArray("story_chapters") ?: JSONArray()).objects()
            val oldUngrouped = oldChapters.filter { it.isNull("volumeId") }
                .sortedBy { it.optInt("sortOrder") }
            val visibleVolumes = archivedStory.optJSONArray("volumes") ?: JSONArray()
            val visibleUngrouped = archivedStory.optJSONArray("ungroupedChapters") ?: JSONArray()
            val updatedVolumes = JSONArray()
            val volumeIds = mutableListOf<Long>()
            for (index in 0 until visibleVolumes.length()) {
                val visible = visibleVolumes.getJSONObject(index)
                val old = oldVolumes.getOrNull(index)
                val volumeId = old?.optLong("id")?.takeIf { it > 0L } ?: nextVolumeId++
                val row = (old ?: JSONObject())
                    .put("id", volumeId)
                    .put("storyId", storyId)
                    .put("title", visible.optString("title"))
                    .put("sortOrder", index)
                updatedVolumes.put(row)
                volumeIds += volumeId
            }

            val updatedChapters = JSONArray()
            fun appendChapters(visibleChapters: JSONArray, oldGroup: List<JSONObject>, volumeId: Long?) {
                for (index in 0 until visibleChapters.length()) {
                    val visible = visibleChapters.getJSONObject(index)
                    val old = oldGroup.getOrNull(index)
                    val now = System.currentTimeMillis()
                    val row = (old ?: JSONObject())
                        .put("id", old?.optLong("id")?.takeIf { it > 0L } ?: nextChapterId++)
                        .put("storyId", storyId)
                        .put("volumeId", volumeId ?: JSONObject.NULL)
                        .put("title", visible.optString("title"))
                        .put("content", visible.optString("content"))
                        .put("continuationGuidance", visible.optString("continuationGuidance"))
                        .put("sortOrder", index)
                    if (old == null) {
                        row.put("contentRevision", 0L).put("createTime", now).put("latestTime", now)
                    }
                    updatedChapters.put(row)
                }
            }
            appendChapters(visibleUngrouped, oldUngrouped, null)
            for (index in 0 until visibleVolumes.length()) {
                val visible = visibleVolumes.getJSONObject(index)
                val oldVolume = oldVolumes.getOrNull(index)
                val oldVolumeId = oldVolume?.optLong("id")
                val oldGroup = if (oldVolumeId == null) emptyList() else oldChapters
                    .filter { !it.isNull("volumeId") && it.optLong("volumeId") == oldVolumeId }
                    .sortedBy { it.optInt("sortOrder") }
                appendChapters(
                    visible.optJSONArray("chapters") ?: JSONArray(),
                    oldGroup,
                    volumeIds[index]
                )
            }
            tableRows.put("story_volumes", updatedVolumes)
            tableRows.put("story_chapters", updatedChapters)
            writeText(file, document.toString(2))
        }
    }

    /**
     * Synchronize visible SillyTavern JSONL fields into the hidden lossless Room rows. The file is
     * processed in two streaming passes so large conversations are not retained in memory.
     */
    private fun applyEditableChatDocuments(root: File) {
        val files = File(root, "chats").listFiles().orEmpty()
            .filter { it.isFile && it.extension == "jsonl" }
        var nextSessionId = 1L
        var nextMessageId = 1L
        files.forEach { file ->
            file.forEachLine(Charsets.UTF_8) { line ->
                if (line.isBlank()) return@forEachLine
                val document = JSONObject(line)
                val tableRows = document.optJSONObject(TABLE_ROWS_KEY) ?: return@forEachLine
                tableRows.optJSONArray("chat_sessions")?.objects()?.forEach {
                    nextSessionId = maxOf(nextSessionId, it.optLong("id") + 1L)
                }
                tableRows.optJSONArray("chat_messages")?.objects()?.forEach {
                    nextMessageId = maxOf(nextMessageId, it.optLong("id") + 1L)
                }
            }
        }
        val characterRows = File(root, "characters").listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .flatMap { file ->
                runCatching {
                    JSONObject(file.readText(Charsets.UTF_8)).getJSONObject(TABLE_ROWS_KEY)
                        .getJSONArray("character").objects()
                }.getOrDefault(emptyList())
            }

        files.forEach { file ->
            val temporary = File.createTempFile("portable-chat-", ".jsonl", file.parentFile)
            try {
                val headerText = file.bufferedReader(Charsets.UTF_8).use { it.readLine() }
                    ?: error("Chat document is empty: " + file.name)
                val header = JSONObject(headerText)
                val headerExtension = header.optJSONObject("chat_metadata")
                    ?.optJSONObject("rpclient")
                val existingSession = header.optJSONObject(TABLE_ROWS_KEY)
                    ?.optJSONArray("chat_sessions")?.optJSONObject(0)
                val isNewSession = existingSession == null
                val session = existingSession ?: run {
                    val characterName = headerExtension?.optJSONObject("character")?.optString("name")
                        .orEmpty().ifBlank { header.optString("character_name") }
                    val matches = characterRows.filter { it.optString("name") == characterName }
                    require(matches.size == 1) {
                        "Added chat document must identify exactly one existing character: " + file.name
                    }
                    JSONObject()
                        .put("id", nextSessionId++)
                        .put("characterId", matches.single().optLong("id"))
                        .put("createTime", headerExtension?.optLong("create_time") ?: System.currentTimeMillis())
                        .put("latestTime", headerExtension?.optLong("latest_time") ?: System.currentTimeMillis())
                        .put("lorebookEntrySet", headerExtension?.optString("lorebook_entry_set") ?: "[]")
                        .put("title", headerExtension?.optString("title") ?: file.nameWithoutExtension)
                        .put("userNote", headerExtension?.optString("user_note") ?: "")
                        .put("userName", headerExtension?.optString("user_name")
                            ?: header.optString("user_name", "User"))
                        .put("userDescription", headerExtension?.optString("user_description") ?: "")
                        .put("creatorNotes", headerExtension?.optString("creator_notes") ?: JSONObject.NULL)
                        .put("worldInfoStateJson", headerExtension?.optString("world_info_state") ?: "{}")
                        .put("autoSummaryPaused", headerExtension?.optBoolean("auto_summary_paused") ?: false)
                }
                val oldHeaderRows = header.optJSONObject(TABLE_ROWS_KEY)
                val oldSummary = oldHeaderRows?.optJSONArray("chat_messages")?.objects()
                    ?.firstOrNull { it.optString("source").equals("Summary", true) }

                val messageIds = mutableListOf<Long>()
                file.bufferedReader(Charsets.UTF_8).use { reader ->
                    reader.readLine()
                    reader.forEachLine { line ->
                        if (line.isBlank()) return@forEachLine
                        val message = JSONObject(line)
                        val oldRow = message.optJSONObject(TABLE_ROWS_KEY)
                            ?.optJSONArray("chat_messages")?.optJSONObject(0)
                        val id = if (!isNewSession && oldRow != null && oldRow.optLong("id") > 0L) {
                            oldRow.optLong("id")
                        } else nextMessageId++
                        messageIds += id
                    }
                }

                val summaryMetadata = headerExtension?.optJSONObject("summary")
                val summaryRow = if (summaryMetadata != null) {
                    val coveredIndex = summaryMetadata.optInt("covered_message_index", -1)
                    val coveredId = if (coveredIndex in messageIds.indices) messageIds[coveredIndex] else 0L
                    (oldSummary ?: JSONObject().put("id", nextMessageId++))
                        .put("sessionId", session.optLong("id"))
                        .put("createTime", summaryMetadata.optLong("create_time"))
                        .put("source", "Summary")
                        .put("content", summaryMetadata.optString("content"))
                        .put("coveredMessageId", coveredId)
                } else null

                fun setString(key: String, column: String) {
                    val value = headerExtension?.optString(key) ?: return
                    session.put(column, value)
                }
                fun setLong(key: String, column: String) {
                    if (headerExtension?.has(key) == true) session.put(column, headerExtension.optLong(key))
                }
                fun setBoolean(key: String, column: String) {
                    if (headerExtension?.has(key) == true) session.put(column, headerExtension.optBoolean(key))
                }
                setString("title", "title")
                setLong("create_time", "createTime")
                setLong("latest_time", "latestTime")
                setString("user_name", "userName")
                setString("user_description", "userDescription")
                setString("user_note", "userNote")
                setString("creator_notes", "creatorNotes")
                setString("lorebook_entry_set", "lorebookEntrySet")
                setString("world_info_state", "worldInfoStateJson")
                setBoolean("auto_summary_paused", "autoSummaryPaused")

                val outputHeaderRows = JSONObject()
                    .put("chat_sessions", JSONArray().put(session))
                    .put("chat_messages", JSONArray().apply { summaryRow?.let { put(it) } })
                header.put(TABLE_ROWS_KEY, outputHeaderRows)
                temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writeJsonLine(header, writer)
                    file.bufferedReader(Charsets.UTF_8).use { reader ->
                        reader.readLine()
                        var messageIndex = 0
                        reader.forEachLine { line ->
                            if (line.isBlank()) return@forEachLine
                            val document = JSONObject(line)
                            val oldRow = document.optJSONObject(TABLE_ROWS_KEY)
                                ?.optJSONArray("chat_messages")?.optJSONObject(0)
                            val isUser = document.optBoolean("is_user")
                            val extraType = document.optJSONObject("extra")?.optString("type")
                            val source = when {
                                isUser -> "User"
                                document.optBoolean("is_system") ||
                                    extraType.equals("narrator", ignoreCase = true) -> "System"
                                else -> "Char"
                            }
                            val visibleTime = runCatching {
                                Instant.parse(document.optString("send_date")).toEpochMilli()
                            }.getOrNull()
                            val row = (oldRow ?: JSONObject())
                                .put("id", messageIds[messageIndex])
                                .put("sessionId", session.optLong("id"))
                                .put("createTime", visibleTime ?: oldRow?.optLong("createTime") ?: System.currentTimeMillis())
                                .put("source", source)
                                .put("content", document.optString("message"))
                                .put("coveredMessageId", JSONObject.NULL)
                            document.put(TABLE_ROWS_KEY, tableRows("chat_messages" to JSONArray().put(row)))
                            writeJsonLine(document, writer)
                            messageIndex++
                        }
                    }
                }
                require(file.delete()) { "Could not replace chat document: " + file.name }
                require(temporary.renameTo(file)) { "Could not finalize chat document: " + file.name }
            } finally {
                temporary.delete()
            }
        }
    }

    /**
     * For documents whose app-native format already exposes database-shaped records, use the
     * visible arrays/objects as the row source instead of stale duplicate _tableRows snapshots.
     */
    private fun applyEditableRawRowDocuments(root: File) {
        fun syncArrays(path: String, mappings: List<Pair<String, String>>) {
            val file = File(root, path)
            if (!file.isFile) return
            val document = JSONObject(file.readText(Charsets.UTF_8))
            val tableRows = document.optJSONObject(TABLE_ROWS_KEY) ?: JSONObject().also {
                document.put(TABLE_ROWS_KEY, it)
            }
            mappings.forEach { (visibleKey, table) ->
                val rows = document.optJSONArray(visibleKey) ?: JSONArray()
                tableRows.put(table, rows)
            }
            writeText(file, document.toString(2))
        }

        syncArrays(
            "providers/provider-configurations.json",
            listOf(
                "providers" to "llm_providers",
                "characterAssociations" to "character_llm_provider_associations"
            )
        )
        syncArrays("app-data/token-usage.json", listOf("records" to "llm_token_usage_records"))
        syncArrays("app-data/message-images.json", listOf("images" to "message_images"))
        syncArrays("app-data/files-index.json", listOf("files" to "files"))

        File(root, "stories").listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .forEach { file ->
                val document = JSONObject(file.readText(Charsets.UTF_8))
                val tableRows = document.optJSONObject(TABLE_ROWS_KEY) ?: return@forEach
                document.optJSONArray("characterRelations")?.let {
                    tableRows.put("story_characters", it)
                }
                document.optJSONArray("worldBookEntryStates")?.let {
                    tableRows.put("story_lorebook_entries", it)
                }
                writeText(file, document.toString(2))
            }

        listOf(
            "app-data/other-tables.jsonl" to "row",
            "app-data/request-logs.jsonl" to "record"
        ).forEach { (relativePath, rowKey) ->
            val file = File(root, relativePath)
            if (!file.isFile) return@forEach
            val temporary = File.createTempFile("portable-rows-", ".jsonl", file.parentFile)
            try {
                temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
                    file.bufferedReader(Charsets.UTF_8).use { reader ->
                        reader.forEachLine { line ->
                            if (line.isBlank()) return@forEachLine
                            val document = JSONObject(line)
                            val table = document.optString("table")
                            val row = document.optJSONObject(rowKey)
                            if (table.isNotBlank() && row != null) {
                                document.put(TABLE_ROWS_KEY, tableRows(table to JSONArray().put(row)))
                            }
                            writeJsonLine(document, writer)
                        }
                    }
                }
                require(file.delete()) { "Could not replace editable row document: " + file.name }
                require(temporary.renameTo(file)) {
                    "Could not finalize editable row document: " + file.name
                }
            } finally {
                temporary.delete()
            }
        }
    }

    /**
     * Keep the group-chat document's visible session/member/summary/message objects authoritative
     * over their duplicate row snapshots, so edits and removed JSONL message lines are respected.
     */
    private fun applyEditableGroupChatDocuments(root: File) {
        File(root, "group-chats").listFiles().orEmpty()
            .filter { it.isFile && it.extension == "jsonl" }
            .forEach { file ->
                val temporary = File.createTempFile("portable-group-chat-", ".jsonl", file.parentFile)
                var hasHeader = false
                try {
                    temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
                        file.bufferedReader(Charsets.UTF_8).use { reader ->
                            var isHeader = true
                            reader.forEachLine { line ->
                                if (line.isBlank()) return@forEachLine
                                val document = JSONObject(line)
                                val tableRows = document.optJSONObject(TABLE_ROWS_KEY) ?: JSONObject().also {
                                    document.put(TABLE_ROWS_KEY, it)
                                }
                                if (isHeader) {
                                    hasHeader = true
                                    val session = document.optJSONObject("session")
                                        ?: error("Group-chat header is missing its session: " + file.name)
                                    tableRows.put("group_chat_sessions", JSONArray().put(session))
                                    val members = document.optJSONArray("members") ?: JSONArray()
                                    val summaries = document.optJSONArray("summaries") ?: JSONArray()
                                    tableRows.put("group_chat_members", members)
                                    tableRows.put("group_chat_summaries", summaries)
                                    document.put("members", members).put("summaries", summaries)
                                    isHeader = false
                                } else {
                                    val message = document.optJSONObject("message")
                                        ?: error("Group-chat message line is malformed: " + file.name)
                                    tableRows.put("group_chat_messages", JSONArray().put(message))
                                }
                                document.put(TABLE_ROWS_KEY, tableRows)
                                writeJsonLine(document, writer)
                            }
                        }
                    }
                    require(hasHeader) { "Group-chat document is empty: " + file.name }
                    require(file.delete()) { "Could not replace group-chat document: " + file.name }
                    require(temporary.renameTo(file)) {
                        "Could not finalize group-chat document: " + file.name
                    }
                } finally {
                    temporary.delete()
                }
            }
    }

    /**
     * Reconcile edited attachment bytes with the content-addressed repository and file index.
     * Category files are convenient aliases; when one is edited, its new digest becomes the
     * authoritative hash referenced by the corresponding Room file row.
     */
    private fun applyEditableAttachmentDocuments(root: File) {
        val indexFile = File(root, "app-data/files-index.json")
        require(indexFile.isFile) { "The portable file index is missing." }
        val indexDocument = JSONObject(indexFile.readText(Charsets.UTF_8))
        val indexRows = indexDocument.getJSONObject(TABLE_ROWS_KEY).getJSONArray("files")
        val rowsByUuid = linkedMapOf<String, JSONObject>()
        val originalHashByUuid = linkedMapOf<String, String>()
        val rowsByHash = linkedMapOf<String, MutableList<JSONObject>>()
        indexRows.objects().forEach { row ->
            val uuid = row.optString("uuid")
            val hash = row.optString("hash")
            require(uuid.isNotBlank() && hash.matches(Regex("(?i)[0-9a-f]{64}"))) {
                "The portable file index contains an invalid file row."
            }
            rowsByUuid[uuid] = row
            originalHashByUuid[uuid] = hash.lowercase()
            rowsByHash.getOrPut(hash.lowercase()) { mutableListOf() }.add(row)
        }

        val canonical = File(root, "attachments/other-files").apply { mkdirs() }
        val digestByUuid = mutableMapOf<String, String>()
        fun adoptAttachment(file: File, uuid: String, oldHash: String) {
            val fileRow = rowsByUuid[uuid] ?: error("Attachment reference is missing from the file index.")
            val digest = BackupArchiveFiles.sha256(file)
            val previous = digestByUuid[uuid]
            if (previous != null && previous.equals(oldHash, ignoreCase = true) &&
                !digest.equals(oldHash, ignoreCase = true)
            ) {
                digestByUuid[uuid] = digest
            } else if (previous != null && digest.equals(oldHash, ignoreCase = true)) {
                // Ignore an unchanged categorized duplicate when the canonical copy was edited.
                return
            } else {
                require(previous == null || previous == digest) {
                    "Conflicting edited copies exist for attachment " + uuid
                }
                digestByUuid[uuid] = digest
            }
            fileRow.put("hash", digest)
            val destination = File(canonical, digest)
            if (!destination.isFile || BackupArchiveFiles.sha256(destination) != digest) {
                BackupArchiveFiles.copyFile(file, destination)
            }
            // Keep the old hash copy if another entity still references it; unused bytes are harmless.
            if (oldHash.equals(digest, ignoreCase = true)) {
                val original = File(canonical, oldHash)
                if (!original.isFile) BackupArchiveFiles.copyFile(file, original)
            }
        }

        // Canonical files are editable too: the filename identifies the original row even when
        // the edited content no longer matches that original content-addressed filename.
        canonical.listFiles().orEmpty().filter { it.isFile && it.name.matches(Regex("(?i)[0-9a-f]{64}")) }
            .toList().forEach { file ->
                val oldHash = file.name.lowercase()
                rowsByHash[oldHash].orEmpty().forEach { row ->
                    val digest = BackupArchiveFiles.sha256(file)
                    val uuid = row.optString("uuid")
                    if (!digest.equals(oldHash, ignoreCase = true)) {
                        val previous = digestByUuid.putIfAbsent(uuid, digest)
                        require(previous == null || previous == digest) {
                            "Conflicting edited copies exist for attachment " + uuid
                        }
                        row.put("hash", digest)
                        val destination = File(canonical, digest)
                        if (destination.canonicalFile != file.canonicalFile) {
                            if (!destination.isFile || BackupArchiveFiles.sha256(destination) != digest) {
                                BackupArchiveFiles.copyFile(file, destination)
                            }
                        }
                    }
                }
            }

        File(root, "characters").listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .forEach { documentFile ->
                val document = JSONObject(documentFile.readText(Charsets.UTF_8))
                val row = document.optJSONObject(TABLE_ROWS_KEY)
                    ?.optJSONArray("character")?.optJSONObject(0) ?: return@forEach
                val backgroundUuid = row.optString("backgroundFileUuid")
                if (backgroundUuid.isNotBlank() && backgroundUuid !in rowsByUuid) {
                    row.put("backgroundFileUuid", JSONObject.NULL)
                    writeText(documentFile, document.toString(2))
                }
                val avatarUuid = row.optString("avatar")
                if (avatarUuid.isBlank()) return@forEach
                val fileRow = rowsByUuid[avatarUuid]
                if (fileRow == null) {
                    row.put("avatar", "")
                    writeText(documentFile, document.toString(2))
                    return@forEach
                }
                val oldHash = originalHashByUuid[avatarUuid] ?: fileRow.optString("hash")
                val categoryFile = File(
                    root,
                    "attachments/characters/character-${row.optLong("id")}-avatar-$oldHash"
                )
                if (categoryFile.isFile) {
                    adoptAttachment(categoryFile, avatarUuid, oldHash)
                } else {
                    // Removing the categorized avatar removes its reference, not unrelated shared files.
                    row.put("avatar", "")
                    writeText(documentFile, document.toString(2))
                }
            }

        val imageIndex = File(root, "app-data/message-images.json")
        if (imageIndex.isFile) {
            val imageDocument = JSONObject(imageIndex.readText(Charsets.UTF_8))
            val tableRows = imageDocument.optJSONObject(TABLE_ROWS_KEY)
                ?: error("The portable message-image row data is missing.")
            val imageRows = tableRows.optJSONArray("message_images") ?: JSONArray()
            val retainedImages = JSONArray()
            imageRows.objects().forEach { image ->
                val uuid = image.optString("imageUuid")
                if (uuid.isBlank()) {
                    retainedImages.put(image)
                    return@forEach
                }
                val fileRow = rowsByUuid[uuid] ?: return@forEach
                val oldHash = originalHashByUuid[uuid] ?: fileRow.optString("hash")
                val categoryFile = File(
                    root,
                    "attachments/chat-images/${image.optString("messageType")}-" +
                        "${image.optLong("messageId")}-${image.optInt("position")}-$oldHash"
                )
                if (categoryFile.isFile) {
                    adoptAttachment(categoryFile, uuid, oldHash)
                    retainedImages.put(image)
                }
                // A missing categorized image is an explicit delete from the portable document.
            }
            tableRows.put("message_images", retainedImages)
            imageDocument.put("images", retainedImages)
            writeText(imageIndex, imageDocument.toString(2))
        }
        rowsByUuid.forEach { (uuid, row) ->
            val hash = row.optString("hash")
            val asset = File(canonical, hash)
            require(asset.isFile && BackupArchiveFiles.sha256(asset).equals(hash, ignoreCase = true)) {
                "An edited attachment is missing or has an invalid digest: " + uuid
            }
        }
        indexDocument.put("files", indexRows)
        writeText(indexFile, indexDocument.toString(2))
    }

    /**
     * Apply the editable settings.json as the source of truth before replacing live preferences.
     * Existing XML is retained as an exact export reference only; relying on it would silently
     * ignore user edits made to the readable JSON document.
     */
    private fun applyEditableSettings(root: File) {
        val settingsFile = File(root, "settings.json")
        require(settingsFile.isFile) { "The editable settings.json file is missing." }
        val settings = JSONObject(settingsFile.readText(Charsets.UTF_8))
        require(settings.optString("format") == FORMAT) { "Unsupported settings document format." }
        val preferences = settings.optJSONObject("preferences")
            ?: error("The settings document has no preferences object.")
        val preferenceTypes = settings.optJSONObject("preferenceTypes")
        val prefsDirectory = File(root, "app-data/shared-prefs")
        require(prefsDirectory.isDirectory) { "Backup preference files are missing." }
        prefsDirectory.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "xml" }
            .forEach { require(it.delete()) { "Could not replace staged preference file: " + it.name } }

        val names = preferences.keys()
        while (names.hasNext()) {
            val name = names.next()
            require(name.matches(Regex("[A-Za-z0-9_.-]{1,180}"))) {
                "Invalid preference file name: " + name
            }
            val values = preferences.getJSONObject(name)
            val target = File(prefsDirectory, "$name.xml")
            target.outputStream().buffered().use { output ->
                val serializer: XmlSerializer = Xml.newSerializer()
                serializer.setOutput(output, Charsets.UTF_8.name())
                serializer.startDocument(Charsets.UTF_8.name(), true)
                serializer.startTag(null, "map")
                val keys = values.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    require(key.isNotEmpty()) { "Preference keys must not be empty." }
                    when (val value = values.get(key)) {
                        is String -> {
                            serializer.startTag(null, "string")
                            serializer.attribute(null, "name", key)
                            serializer.text(value)
                            serializer.endTag(null, "string")
                        }
                        is Boolean -> {
                            serializer.startTag(null, "boolean")
                            serializer.attribute(null, "name", key)
                            serializer.attribute(null, "value", value.toString())
                            serializer.endTag(null, "boolean")
                        }
                        is Number -> {
                            val numeric = value.toDouble()
                            require(numeric.isFinite()) { "Non-finite preference number: " + key }
                            val isIntegral = numeric % 1.0 == 0.0
                            val preferredType = preferenceTypes?.optJSONObject(name)?.optString(key).orEmpty()
                            val tag = when (preferredType) {
                                "float" -> "float"
                                "int" -> {
                                    require(isIntegral && numeric >= Int.MIN_VALUE.toDouble() &&
                                        numeric <= Int.MAX_VALUE.toDouble()) {
                                        "Preference value no longer fits its original int type: " + key
                                    }
                                    "int"
                                }
                                "long" -> {
                                    require(isIntegral && numeric >= Long.MIN_VALUE.toDouble() &&
                                        numeric <= Long.MAX_VALUE.toDouble()) {
                                        "Preference value no longer fits its original long type: " + key
                                    }
                                    "long"
                                }
                                else -> when {
                                    !isIntegral -> "float"
                                    numeric >= Int.MIN_VALUE.toDouble() && numeric <= Int.MAX_VALUE.toDouble() -> "int"
                                    numeric >= Long.MIN_VALUE.toDouble() && numeric <= Long.MAX_VALUE.toDouble() -> "long"
                                    else -> error("Preference number is out of range: " + key)
                                }
                            }
                            serializer.startTag(null, tag)
                            serializer.attribute(null, "name", key)
                            serializer.attribute(null, "value", when (tag) {
                                "float" -> numeric.toFloat().toString()
                                else -> value.toString()
                            })
                            serializer.endTag(null, tag)
                        }
                        is JSONArray -> {
                            val items = (0 until value.length()).map { index ->
                                value.get(index).also {
                                    require(it is String) { "Preference set must contain only strings: " + key }
                                } as String
                            }
                            serializer.startTag(null, "set")
                            serializer.attribute(null, "name", key)
                            items.forEach { item ->
                                serializer.startTag(null, "string")
                                serializer.text(item)
                                serializer.endTag(null, "string")
                            }
                            serializer.endTag(null, "set")
                        }
                        else -> error("Unsupported editable preference value for key: " + key)
                    }
                }
                serializer.endTag(null, "map")
                serializer.endDocument()
                serializer.flush()
                output.flush()
            }
        }
    }

    /** Preserve original SharedPreferences value types while keeping settings.json readable. */
    private fun sharedPreferencesTypes(preferences: SharedPreferences): JSONObject {
        val types = JSONObject()
        preferences.all.toSortedMap().forEach { (key, value) ->
            val type = when (value) {
                is String -> "string"
                is Boolean -> "boolean"
                is Int -> "int"
                is Long -> "long"
                is Float -> "float"
                is Set<*> -> "set"
                else -> error("Unsupported SharedPreferences value type for key: " + key)
            }
            types.put(key, type)
        }
        return types
    }

    private fun JSONArray.objectsOrValues(): List<Any?> =
        (0 until length()).map { get(it) }

    /** 将 Android 偏好类型转为便于人工读取的 JSON。 */
    private fun sharedPreferencesJson(preferences: SharedPreferences): JSONObject {
        val json = JSONObject()
        // 字符串集合排序只影响可读输出，不改变实际偏好类型。
        preferences.all.toSortedMap().forEach { (key, value) ->
            when (value) {
                null -> json.put(key, JSONObject.NULL)
                is String -> json.put(key, value)
                is Boolean -> json.put(key, value)
                is Int -> json.put(key, value)
                is Long -> json.put(key, value)
                is Float -> json.put(key, value.toDouble())
                is Set<*> -> json.put(key, JSONArray().apply {
                    value.filterIsInstance<String>().sorted().forEach { put(it) }
                })
                else -> json.put(key, value.toString())
            }
        }
        return json
    }

    /** 保存仓库的标准附件，并为角色和消息生成易于识别的分类副本。 */
    private fun exportAttachments(db: SupportSQLiteDatabase, tables: List<String>, root: File) {
        val fileRows = if ("files" in tables) readRows(db, "files").objects() else emptyList()
        val fileByUuid = fileRows.associateBy { it.optString("uuid") }
        val repository = mContext.getDir("repository", Context.MODE_PRIVATE)
        val otherFiles = File(root, "attachments/other-files")
        otherFiles.mkdirs()
        // 文件索引里的引用必须存在，缺失附件不能导出为看似完整的备份。
        fileRows.forEach { row ->
            val hash = row.optString("hash")
            require(hash.matches(Regex("[0-9a-fA-F]{64}"))) {
                "Invalid stored attachment hash for " + row.optString("uuid")
            }
            val source = File(repository, hash)
            require(source.isFile) { "An attachment is missing from storage: " + hash }
            copyFile(source, File(otherFiles, hash))
        }
        repository.listFiles().orEmpty().filter {
            it.isFile && it.name.matches(Regex("[0-9a-fA-F]{64}"))
        }.forEach { copyFile(it, File(otherFiles, it.name)) }

        if ("character" in tables) {
            readRows(db, "character").objects().forEach { character ->
                val fileRecord = fileByUuid[character.optString("avatar")] ?: return@forEach
                val hash = fileRecord.optString("hash")
                val asset = File(repository, hash)
                if (asset.isFile) copyFile(asset,
                    File(root, "attachments/characters/character-" + character.optLong("id") + "-avatar-" + hash))
            }
        }
        if ("message_images" in tables) {
            readRows(db, "message_images").objects().forEach { image ->
                val fileRecord = fileByUuid[image.optString("imageUuid")] ?: return@forEach
                val hash = fileRecord.optString("hash")
                val asset = File(repository, hash)
                if (asset.isFile) {
                    val type = image.optString("messageType")
                    val messageId = image.optLong("messageId")
                    val position = image.optInt("position")
                    copyFile(asset, File(root,
                        "attachments/chat-images/" + type + "-" + messageId + "-" + position + "-" + hash))
                }
            }
        }
    }

    /** 按行输出请求日志及日志库的扩展表。 */
    private fun exportRequestLogs(db: SupportSQLiteDatabase, root: File) {
        File(root, "app-data/request-logs.jsonl").bufferedWriter(Charsets.UTF_8).use { writer ->
            tableNames(db).forEach { table ->
                readRows(db, table).objects().forEach { row ->
                    val isRequestLog = table == "llm_request_logs"
                    writeJsonLine(JSONObject()
                        .put("type", if (isRequestLog) "request-log" else "internal-table")
                        .put("table", table)
                        .put("record", row)
                        .put(TABLE_ROWS_KEY, tableRows(table to JSONArray().put(row))), writer)
                }
            }
        }
    }

    /** 校验两个暂存库，再通过统一替换器保存快照并覆盖全部数据。 */
    private fun replaceLiveData(
        rollback: File,
        stagedPrimary: File,
        stagedLogs: File,
        stagedPrefs: File,
        stagedRepository: File,
        stagedFiles: File
    ) {
        require(stagedPrefs.isDirectory) { "Backup preference files are missing." }
        require(stagedRepository.isDirectory) { "Backup attachments are missing." }
        require(stagedFiles.isDirectory) { "Backup app-private files are missing." }
        // 先在暂存库执行正常迁移；不兼容的新版本不能进入覆盖流程。
        BackupDatabaseValidator.validatePrimary(mContext, stagedPrimary)
        BackupDatabaseValidator.validateLogs(mContext, stagedLogs)
        BackupDataReplacer.replace(rollback, listOf(
            BackupReplacement(stagedPrimary, mContext.getDatabasePath(PRIMARY_DB), isDatabase = true),
            BackupReplacement(stagedLogs, mContext.getDatabasePath(LOGS_DB), isDatabase = true),
            BackupReplacement(stagedPrefs, File(mContext.dataDir, "shared_prefs")),
            BackupReplacement(stagedRepository, mContext.getDir("repository", Context.MODE_PRIVATE)),
            BackupReplacement(stagedFiles, mContext.filesDir)
        )) {
            mDatabase.close()
            mRequestLogDatabase.close()
        }
    }

    /** 统计功能文档数量，供 manifest 展示归档内容规模。 */
    private fun countRecords(root: File): JSONObject {
        val counts = JSONObject()
        val folders = listOf(
            "characters" to "characters", "stories" to "stories",
            "chats" to "chats", "groupChats" to "group-chats",
            "worldbooks" to "worldbooks", "providers" to "providers"
        )
        folders.forEach { (key, path) ->
            counts.put(key, File(root, path).walkTopDown().count { it.isFile })
        }
        // 请求日志文件也携带内部表，统计时只计业务日志条目。
        var requestLogs = 0
        File(root, "app-data/request-logs.jsonl").takeIf { it.isFile }?.forEachLine(Charsets.UTF_8) { line ->
            if (line.isNotBlank() && runCatching { JSONObject(line).optString("type") == "request-log" }.getOrDefault(false)) {
                requestLogs++
            }
        }
        counts.put("requestLogs", requestLogs)
        return counts
    }

}
